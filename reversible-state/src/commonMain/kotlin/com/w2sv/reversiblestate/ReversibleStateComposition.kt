package com.w2sv.reversiblestate

import com.w2sv.reversiblestate.internal.logIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A [ReversibleState] implementation that aggregates multiple child
 * [ReversibleState] instances into a single composable state.
 *
 * Tracks whether any child states have pending changes via [isDirty], and
 * provides [commit] and [revert] operations that propagate
 * only to child states that are currently dirty.
 *
 * @param states The list of child [ReversibleState] instances to compose.
 * @param scope The [CoroutineScope] used for combining flows and launching operations.
 * @param onCommit Optional callback invoked after committing all dirty child states.
 * @param onRevert Optional callback invoked after reverting all dirty child states.
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

    override val isDirty: StateFlow<Boolean> = combine(states.map { it.isDirty }) { flags -> flags.any { it } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val dirtyStates: List<ReversibleState>
        get() = states.filter { it.isDirty.value }

    override suspend fun commit() {
        log { "Syncing $logIdentifier" }

        dirtyStates.forEach { it.commit() }
        onCommit(this)
    }

    override fun revert() {
        log { "Resetting $logIdentifier" }

        dirtyStates.forEach { it.revert() }
        onRevert(this)
    }

    override fun launchCommit(): Job =
        scope.launch { commit() }
}
