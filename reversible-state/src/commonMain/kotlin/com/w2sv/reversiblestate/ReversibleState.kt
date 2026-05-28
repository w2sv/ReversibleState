package com.w2sv.reversiblestate

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

/**
 * Represents a state holder that distinguishes between an editable state and an
 * applied (persisted or committed) state.
 *
 * A [ReversibleState] can:
 * - expose whether its editable state differs from the applied state via [isDirty],
 * - commit its current editable state using [commit],
 * - launch synchronization asynchronously via [launchCommit],
 * - revert the editable state back to the applied state via [revert].
 */
interface ReversibleState {

    /**
     * Emits `true` whenever the editable state differs from the applied state.
     */
    val isDirty: StateFlow<Boolean>

    /**
     * Commits the current editable state to the applied state.
     */
    suspend fun commit()

    /**
     * Launches [commit] asynchronously.
     */
    fun launchCommit(): Job

    /**
     * Reverts the editable state back to the applied state.
     */
    fun revert()
}
