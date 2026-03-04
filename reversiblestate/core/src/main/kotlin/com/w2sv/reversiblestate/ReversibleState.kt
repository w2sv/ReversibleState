package com.w2sv.reversiblestate

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

interface ReversibleState {
    val statesDissimilar: StateFlow<Boolean>

    suspend fun sync()
    fun launchSync(): Job
    fun reset()
}
