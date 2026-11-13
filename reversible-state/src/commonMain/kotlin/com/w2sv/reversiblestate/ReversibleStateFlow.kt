package com.w2sv.reversiblestate

import com.w2sv.kotlinutils.coroutines.flow.mapState
import com.w2sv.reversiblestate.internal.logIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Controls how applied-state emissions affect the editable value. */
enum class AppliedStateSyncPolicy {
    /** Copies every emission into the editor, potentially discarding local edits. */
    Always,

    /** Copies trustworthy emissions only while no newer local edit exists. */
    WhenUnmodified,

    /** Never copies emissions into the editor. */
    Never
}

/** The previous trusted persisted value and the editable snapshot submitted by one commit. */
data class StateCommit<T>(val previous: T, val current: T)

/**
 * Immutable snapshot of the editable state, its persisted baseline, and the metadata
 * used to safely synchronize changes from the applied-state flow.
 */
private data class EditorState<T>(
    /** The current editable value, which may contain uncommitted changes. */
    val editable: T,

    /** The latest reliably known persisted value, used for dirty checks, commits, and reverts. */
    val persisted: T,

    /** Increments whenever the editable value changes locally, including through a revert. */
    val editRevision: Long = 0,

    /** The [editRevision] at the last safe synchronization point or explicit revert. */
    val syncRevision: Long = 0,

    /**
     * Whether applied-state emissions may update [persisted].
     *
     * Disabled after the first persistence attempt because subsequent emissions
     * cannot reliably be distinguished from delayed results of earlier writes.
     */
    val acceptAppliedEmissions: Boolean = true
)

/**
 * An editable [StateFlow] with atomic [value] and [update] operations.
 * [appliedState].value must be a genuine loaded initial baseline, not a loading placeholder.
 *
 * [isDirty], [commit], [StateCommit.previous], and [revert] all use the trusted persisted baseline. Before the first
 * persistence attempt, applied emissions advance that baseline; [AppliedStateSyncPolicy.WhenUnmodified] copies them into
 * the editor only while no local edit has occurred since the last safe synchronization point. After a persistence attempt,
 * an ordinary [StateFlow] cannot identify which write produced an emission. Successful local commits advance the
 * trusted baseline directly, while later emissions cannot regress it. Revert always restores that trusted baseline.
 *
 * [commitState] should complete when persistence itself succeeds. A callback that writes and then fails or is
 * cancelled may be retried, because this class cannot confirm or roll back that external write. Keep fallible
 * post-persistence effects outside the callback. [commits] is a transient, replay-free notification stream; it does
 * not guarantee delivery of critical side effects to inactive or slow collectors.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
class ReversibleStateFlow<T>(
    private val scope: CoroutineScope,
    val appliedState: StateFlow<T>,
    private val commitState: suspend (StateCommit<T>) -> Unit,
    private val onRevert: (T) -> Unit = {},
    private val syncPolicy: AppliedStateSyncPolicy = AppliedStateSyncPolicy.WhenUnmodified,
    private val log: (() -> String) -> Unit = {}
) : StateFlow<T>,
    ReversibleState {

    private val state = MutableStateFlow(appliedState.value.let { EditorState(it, it) })
    private val editableFlow = state.mapState { it.editable }
    override val isDirty: StateFlow<Boolean> = state.mapState { it.editable != it.persisted }
    private val commitMutex = Mutex()

    /** Successful commits in completion order, with no replay or durable delivery. */
    val commits: SharedFlow<StateCommit<T>>
        field = MutableSharedFlow<StateCommit<T>>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

    init {
        scope.launch { appliedState.collect { observeApplied(it) } }
    }

    /**
     * The current editable value.
     *
     * Assignments delegate to [update], ensuring atomic updates and incrementing
     * the edit revision only when the value actually changes.
     */
    override var value: T
        get() = state.value.editable
        set(value) = update { value }

    /**
     * Atomically transforms the editable value, incrementing its revision only
     * when the result differs from the current value.
     *
     * [transform] may be evaluated multiple times if concurrent updates occur.
     */
    fun update(transform: (T) -> T) {
        state.update { old ->
            val updated = transform(old.editable)
            if (updated == old.editable) old else old.copy(editable = updated, editRevision = old.editRevision + 1)
        }
    }

    override val replayCache: List<T>
        get() = editableFlow.replayCache

    override suspend fun collect(collector: FlowCollector<T>): Nothing =
        editableFlow.collect(collector)

    private fun observeApplied(applied: T) {
        state.update { old ->
            val synchronize = when (syncPolicy) {
                AppliedStateSyncPolicy.Always -> true
                AppliedStateSyncPolicy.Never -> false
                AppliedStateSyncPolicy.WhenUnmodified -> old.acceptAppliedEmissions && old.editRevision == old.syncRevision
            }
            old.copy(
                editable = if (synchronize) applied else old.editable,
                persisted = if (old.acceptAppliedEmissions) applied else old.persisted
            )
        }
    }

    /** Returns `true` for a new successful write, `false` for a no-op; failures and cancellation propagate. */
    override suspend fun commit(): Boolean =
        commitMutex.withLock {
            val captured = state.updateAndGet { old ->
                if (old.editable == old.persisted) old else old.copy(acceptAppliedEmissions = false)
            }
            if (captured.editable == captured.persisted) return@withLock false
            val transition = StateCommit(captured.persisted, captured.editable)
            log { "Committing $logIdentifier state ${transition.current}" }
            commitState(transition)
            currentCoroutineContext().ensureActive()
            state.update { it.copy(persisted = transition.current) }
            commits.tryEmit(transition)
            true
        }

    override fun launchCommit(): Job =
        scope.launch { commit() }

    /** Atomically restores the latest trusted persisted value, including a local write whose emission is delayed. */
    override fun revert() {
        val reverted = state.updateAndGet { old ->
            val revision = if (old.editable == old.persisted) old.editRevision else old.editRevision + 1
            old.copy(
                editable = old.persisted,
                editRevision = revision,
                syncRevision = if (old.acceptAppliedEmissions) revision else old.syncRevision
            )
        }
        log { "Resetting $logIdentifier" }
        onRevert(reverted.editable)
    }
}
