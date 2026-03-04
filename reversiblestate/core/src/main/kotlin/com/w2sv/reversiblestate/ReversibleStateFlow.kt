@file:Suppress("unused")

package com.w2sv.reversiblestate

import com.w2sv.kotlinutils.coroutines.flow.collectOn
import com.w2sv.reversiblestate.internal.logIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReversibleStateFlow<T>(
    private val scope: CoroutineScope,
    val appliedStateFlow: StateFlow<T>,
    private val syncState: suspend (T) -> Unit,
    private val onStateReset: (T) -> Unit = {},
    doAppliedStateBasedStateAlignmentPostInit: Boolean = true,
    private val log: (() -> String) -> Unit = {}
) : ReversibleState,
    MutableStateFlow<T> by MutableStateFlow(appliedStateFlow.value) {

    override val statesDissimilar = combine(this, appliedStateFlow) { editable, applied -> editable != applied }
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            false
        )

    init {
        if (doAppliedStateBasedStateAlignmentPostInit) {
            appliedStateFlow.collectOn(scope) { value = it }
        }
    }

    override suspend fun sync() {
        log { "Syncing $logIdentifier" }
        syncState(value)
    }

    fun launchSync(): Job =
        scope.launch { sync() }

    override fun reset() {
        log { "Resetting $logIdentifier" }
        onStateReset(value)
    }
}
