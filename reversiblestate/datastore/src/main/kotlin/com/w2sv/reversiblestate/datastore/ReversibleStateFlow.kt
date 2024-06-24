package com.w2sv.reversiblestate.datastore

import com.w2sv.datastoreutils.datastoreflow.DataStoreFlow
import com.w2sv.datastoreutils.datastoreflow.DataStoreStateFlow
import com.w2sv.reversiblestate.ReversibleStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted

fun <T> DataStoreFlow<T>.toReversibleStateFlow(
    scope: CoroutineScope,
    started: SharingStarted = SharingStarted.Eagerly,
    onStateReset: (T) -> Unit = {},
    doAppliedStateBasedStateAlignmentPostInit: Boolean = true
): ReversibleStateFlow<T> = ReversibleStateFlow(
    scope = scope,
    appliedStateFlow = this.stateIn(scope, started),
    syncState = this.save,
    onStateReset = onStateReset,
    doAppliedStateBasedStateAlignmentPostInit = doAppliedStateBasedStateAlignmentPostInit
)

fun <T> DataStoreStateFlow<T>.toReversibleStateFlow(
    scope: CoroutineScope,
    onStateReset: (T) -> Unit = {},
    doAppliedStateBasedStateAlignmentPostInit: Boolean = true
): ReversibleStateFlow<T> = ReversibleStateFlow(
    scope = scope,
    appliedStateFlow = this,
    syncState = this.save,
    onStateReset = onStateReset,
    doAppliedStateBasedStateAlignmentPostInit = doAppliedStateBasedStateAlignmentPostInit
)