package com.w2sv.reversiblestate

import com.w2sv.kotlinutils.coroutines.flow.combineStates
import com.w2sv.reversiblestate.internal.logIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [ReversibleState] implementation that aggregates multiple child
 * [ReversibleState] instances into a single composable state.
 *
 * Tracks whether any child states have pending changes via [isDirty], and
 * provides [commit] and [revert] operations for children that are dirty when each operation starts.
 * Child commits are serialized within this composition, but independently persisted children do not form
 * one atomic transaction. If a child fails, earlier child commits remain persisted and [onCommit] is not called.
 *
 * @param states The list of child [ReversibleState] instances to compose.
 * @param scope The [CoroutineScope] used for combining flows and launching operations.
 * @param onCommit Called once with children whose [ReversibleState.commit] returned `true` after all selected children succeed.
 * @param onRevert Called with the children selected for reverting.
 * @param log Optional logging function for debug output.
 */
class ReversibleStateComposition(
    private val states: List<ReversibleState>,
    private val scope: CoroutineScope,
    private val onCommit: suspend (List<ReversibleState>) -> Unit = {},
    private val onRevert: (List<ReversibleState>) -> Unit = {},
    private val log: (() -> String) -> Unit = {}
) : ReversibleState,
    List<ReversibleState> by states {

    override val isDirty: StateFlow<Boolean> = if (states.isEmpty()) {
        MutableStateFlow(false)
    } else {
        combineStates(states.map { it.isDirty }) { flags -> flags.any { it } }
    }
    private val commitMutex = Mutex()

    private val dirtyStates: List<ReversibleState>
        get() = states.filter { it.isDirty.value }

    override suspend fun commit(): Boolean =
        commitMutex.withLock {
            log { "Syncing $logIdentifier" }
            val selected = dirtyStates
            val committed = mutableListOf<ReversibleState>()
            selected.forEach { child ->
                if (child.commit()) committed += child
            }
            if (committed.isEmpty()) return@withLock false
            onCommit(committed)
            true
        }

    override fun revert() {
        log { "Resetting $logIdentifier" }

        val selected = dirtyStates
        selected.forEach { it.revert() }
        onRevert(selected)
    }

    override fun launchCommit(): Job =
        scope.launch { commit() }
}
