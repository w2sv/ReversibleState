package com.w2sv.reversiblestate

import kotlinx.coroutines.flow.StateFlow

interface ReversibleState {
    val statesDissimilar: StateFlow<Boolean>

    suspend fun sync()
    fun reset()
}