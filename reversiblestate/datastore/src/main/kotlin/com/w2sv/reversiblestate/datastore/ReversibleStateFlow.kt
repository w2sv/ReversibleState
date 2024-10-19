package com.w2sv.reversiblestate.datastore

import com.w2sv.datastoreutils.datastoreflow.DataStoreFlow
import com.w2sv.datastoreutils.datastoreflow.DataStoreStateFlow
import com.w2sv.reversiblestate.ReversibleStateFlow
import com.w2sv.reversiblestate.reversibleStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted

fun <T> DataStoreFlow<T>.reversibleStateFlow(
    scope: CoroutineScope,
    started: SharingStarted = SharingStarted.Eagerly,
    onStateReset: (T) -> Unit = {},
    doAppliedStateBasedStateAlignmentPostInit: Boolean = true,
    log: (() -> String) -> Unit = {}
): ReversibleStateFlow<T> =
    stateIn(scope, started)
        .reversibleStateFlow(
            scope = scope,
            onStateReset = onStateReset,
            doAppliedStateBasedStateAlignmentPostInit = doAppliedStateBasedStateAlignmentPostInit,
            log = log
        )

fun <T> DataStoreStateFlow<T>.reversibleStateFlow(
    scope: CoroutineScope,
    onStateReset: (T) -> Unit = {},
    doAppliedStateBasedStateAlignmentPostInit: Boolean = true,
    log: (() -> String) -> Unit = {}
): ReversibleStateFlow<T> =
    reversibleStateFlow(
        scope = scope,
        syncState = save,
        onStateReset = onStateReset,
        doAppliedStateBasedStateAlignmentPostInit = doAppliedStateBasedStateAlignmentPostInit,
        log = log
    )