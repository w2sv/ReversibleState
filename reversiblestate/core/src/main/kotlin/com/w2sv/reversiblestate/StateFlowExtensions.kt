package com.w2sv.reversiblestate

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

fun <T> StateFlow<T>.reversibleStateFlow(
    scope: CoroutineScope,
    syncState: suspend (T) -> Unit,
    onStateReset: (T) -> Unit = {},
    doAppliedStateBasedStateAlignmentPostInit: Boolean = true,
    log: (() -> String) -> Unit = {}
): ReversibleStateFlow<T> =
    ReversibleStateFlow(
        scope = scope,
        appliedStateFlow = this,
        syncState = syncState,
        onStateReset = onStateReset,
        doAppliedStateBasedStateAlignmentPostInit = doAppliedStateBasedStateAlignmentPostInit,
        log = log
    )
