package com.w2sv.reversiblestate.datastore

import com.w2sv.datastoreutils.datastoreflow.DataStoreFlow
import com.w2sv.datastoreutils.datastoreflow.DataStoreStateFlow
import com.w2sv.reversiblestate.ReversibleStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted

fun <T> DataStoreFlow<T>.reversibleStateFlow(
    scope: CoroutineScope,
    started: SharingStarted = SharingStarted.Eagerly,
    onStateRevert: (T) -> Unit = {},
    autoSyncWithAppliedState: Boolean = true,
    log: (() -> String) -> Unit = {}
): ReversibleStateFlow<T> =
    stateIn(scope, started).reversibleStateFlow(
        scope = scope,
        onStateRevert = onStateRevert,
        autoSyncWithAppliedState = autoSyncWithAppliedState,
        log = log
    )

fun <T> DataStoreStateFlow<T>.reversibleStateFlow(
    scope: CoroutineScope,
    onStateRevert: (T) -> Unit = {},
    autoSyncWithAppliedState: Boolean = true,
    log: (() -> String) -> Unit = {}
): ReversibleStateFlow<T> =
    ReversibleStateFlow(
        appliedState = this,
        scope = scope,
        commitState = save,
        onRevert = onStateRevert,
        autoSyncWithAppliedState = autoSyncWithAppliedState,
        log = log
    )
