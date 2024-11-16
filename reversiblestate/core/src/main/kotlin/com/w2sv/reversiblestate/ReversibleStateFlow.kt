@file:Suppress("unused")

package com.w2sv.reversiblestate

import com.w2sv.kotlinutils.coroutines.collectFromFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ReversibleStateFlow<T>(
    private val scope: CoroutineScope,
    val appliedStateFlow: StateFlow<T>,
    private val syncState: suspend (T) -> Unit,
    private val onStateReset: (T) -> Unit = {},
    doAppliedStateBasedStateAlignmentPostInit: Boolean = true,
    private val log: (() -> String) -> Unit = {}
) : AbstractReversibleState(),
    MutableStateFlow<T> by MutableStateFlow(appliedStateFlow.value) {

    init {
        if (doAppliedStateBasedStateAlignmentPostInit) {
            scope.collectFromFlow(appliedStateFlow) {
                value = it // Triggers statesDissimilar update
            }
        }

        // Update [statesDissimilar] whenever a new value is collected
        scope.collectFromFlow(this) {
            _statesDissimilar.value = it != appliedStateFlow.value
        }
    }

    override suspend fun sync() {
        log { "Syncing $logIdentifier" }

        syncState(value)
        _statesDissimilar.value = false
    }

    fun launchSync(): Job =
        scope.launch { sync() }

    override fun reset() {
        log { "Resetting $logIdentifier" }

        value = appliedStateFlow.value // Triggers statesDissimilar update
        onStateReset(value)
    }
}
