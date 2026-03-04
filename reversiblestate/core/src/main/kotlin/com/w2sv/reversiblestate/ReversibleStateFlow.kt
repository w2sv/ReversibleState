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
    val appliedState: StateFlow<T>,
    private val syncState: suspend (T) -> Unit,
    private val onStateReset: (T) -> Unit = {},
    autoSyncWithAppliedState: Boolean = true,
    private val log: (() -> String) -> Unit = {}
) : ReversibleState,
    MutableStateFlow<T> by MutableStateFlow(appliedState.value) {

    override val statesDissimilar = combine(this, appliedState) { editable, applied -> editable != applied }
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            false
        )

    init {
        if (autoSyncWithAppliedState) {
            appliedState.collectOn(scope) { value = it }
        }
    }

    override suspend fun sync() {
        log { "Syncing $logIdentifier" }
        syncState(value)
    }

    override fun launchSync(): Job =
        scope.launch { sync() }

    override fun reset() {
        log { "Resetting $logIdentifier" }
        onStateReset(value)
    }
}
