package com.w2sv.reversiblestate

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

typealias ReversibleStates = List<AbstractReversibleState>

open class ReversibleStatesComposition(
    private val reversibleStates: ReversibleStates,
    private val scope: CoroutineScope,
    private val onStateSynced: suspend (ReversibleStates) -> Unit = {},
    private val onStateReset: (ReversibleStates) -> Unit = {},
    private val log: (() -> String) -> Unit = {}
) : ReversibleStates by reversibleStates,
    AbstractReversibleState() {

    private val changedStateInstanceIndices = mutableSetOf<Int>()
    private val changedStateInstances
        get() = changedStateInstanceIndices.map(::get)

    init {
        // Update [changedStateInstanceIndices] and [_statesDissimilar] upon change of one of
        // the held element's [statesDissimilar]
        scope.launch {
            mapIndexed { i, instance ->
                instance.statesDissimilar.map { i to it }
            }
                .merge()
                .collect { (i, statesDissimilar) ->
                    if (statesDissimilar) {
                        changedStateInstanceIndices.add(i)
                    } else {
                        changedStateInstanceIndices.remove(i)
                    }

                    _statesDissimilar.value = changedStateInstanceIndices.isNotEmpty()
                }
        }
    }

    override suspend fun sync() {
        log { "Syncing $logIdentifier" }

        changedStateInstances.forEach {
            it.sync()
        }
        onStateSynced(this)
    }

    override fun reset() {
        log { "Resetting $logIdentifier" }

        changedStateInstances.forEach {
            it.reset()
        }

        onStateReset(this)
    }

    fun launchSync(): Job =
        scope.launch { sync() }
}