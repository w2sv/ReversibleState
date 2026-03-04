package com.w2sv.reversiblestate

import com.w2sv.reversiblestate.internal.logIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

typealias ReversibleStates = List<ReversibleState>

open class ReversibleStateComposition(
    private val reversibleStates: ReversibleStates,
    private val scope: CoroutineScope,
    private val onStateSynced: suspend (ReversibleStates) -> Unit = {},
    private val onStateReset: (ReversibleStates) -> Unit = {},
    private val log: (() -> String) -> Unit = {}
) : ReversibleState,
    ReversibleStates by reversibleStates {

    override val statesDissimilar: StateFlow<Boolean> = combine(reversibleStates.map { it.statesDissimilar }) { flags -> flags.any { it } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val changedStateInstances: List<ReversibleState>
        get() = reversibleStates.filter { it.statesDissimilar.value }

    override suspend fun sync() {
        log { "Syncing $logIdentifier" }

        changedStateInstances.forEach { it.sync() }
        onStateSynced(this)
    }

    override fun reset() {
        log { "Resetting $logIdentifier" }

        changedStateInstances.forEach { it.reset() }
        onStateReset(this)
    }

    fun launchSync(): Job =
        scope.launch { sync() }
}
