package com.w2sv.reversiblestate

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

/**
 * Represents a state holder that distinguishes editable and known persisted state.
 *
 * A [ReversibleState] can:
 * - expose whether its editable state differs from the known persisted state via [isDirty],
 * - commit its current editable state using [commit],
 * - launch a commit asynchronously via [launchCommit],
 * - revert the editable state back to its trusted persisted baseline via [revert].
 */
interface ReversibleState {

    /**
     * Reflects whether the editable state differs from the latest persisted baseline known to this instance.
     */
    val isDirty: StateFlow<Boolean>

    /**
     * Persists the current editable state if needed. Returns `true` only when a new persistence operation succeeded.
     * Returns `false` when no persistence was needed. Failures and cancellation propagate.
     */
    suspend fun commit(): Boolean

    /**
     * Launches [commit] asynchronously.
     */
    fun launchCommit(): Job

    /**
     * Reverts the editable state to the latest trusted persisted baseline.
     */
    fun revert()
}
