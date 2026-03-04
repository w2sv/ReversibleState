@file:Suppress("unused")

package com.w2sv.reversiblestate

import com.w2sv.kotlinutils.coroutines.flow.collectOn
import com.w2sv.kotlinutils.coroutines.flow.combineStates
import com.w2sv.reversiblestate.internal.logIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * A [ReversibleState] implementation backed by a [StateFlow] that supports
 * tracking, committing, and reverting changes.
 *
 * This class maintains an editable state internally, which can differ from
 * the [appliedState]. Changes can be committed via [commit] reverted via [revert].
 *
 * @param scope The [CoroutineScope] used for collecting flows and launching operations.
 * @param appliedState The persisted state that changes will be committed to or reverted from.
 * @param commitState A suspending function that applies the current editable state to the [appliedState].
 * @param onRevert Optional callback invoked when the editable state is reverted to the [appliedState].
 * @param autoSyncWithAppliedState If true, the editable state will automatically track changes emitted by [appliedState] after
 * initialization.
 * @param log Optional logging function for debug output.
 */
class ReversibleStateFlow<T>(
    private val scope: CoroutineScope,
    val appliedState: StateFlow<T>,
    private val commitState: suspend (T) -> Unit,
    private val onRevert: (T) -> Unit = {},
    autoSyncWithAppliedState: Boolean = true,
    private val log: (() -> String) -> Unit = {}
) : ReversibleState,
    MutableStateFlow<T> by MutableStateFlow(appliedState.value) {

    override val isDirty = combineStates(appliedState, this) { a, b -> a != b }

    init {
        if (autoSyncWithAppliedState) {
            appliedState.collectOn(scope) { emit(it) }
        }
    }

    override suspend fun commit() {
        log { "Committing $logIdentifier state $value" }
        commitState(value)
    }

    override fun launchCommit(): Job =
        scope.launch { commit() }

    override fun revert() {
        log { "Resetting $logIdentifier" }
        value = appliedState.value
        onRevert(value)
    }
}
