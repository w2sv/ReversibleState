package com.w2sv.reversiblestate.datastore.android

import com.w2sv.datastoreutils.preferences.map.DataStoreFlowMap
import com.w2sv.datastoreutils.preferences.map.DataStoreStateFlowMap
import com.w2sv.reversiblestate.ReversibleStateMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted

fun <K, V> DataStoreFlowMap<K, V>.reversibleStateMap(
    scope: CoroutineScope,
    makeMap: (Map<K, V>) -> MutableMap<K, V>,
    onStateSynced: suspend (Map<K, V>) -> Unit = {},
    onStateReset: (Map<K, V>) -> Unit = {},
    appliedStateMapBasedStateAlignment: Boolean = false
): ReversibleStateMap<K, V> =
    ReversibleStateMap(
        appliedStateMap = stateIn(scope, SharingStarted.Eagerly),
        makeMap = makeMap,
        syncState = { save(it) },
        onStateSynced = onStateSynced,
        onStateReset = onStateReset,
        appliedStateMapBasedStateAlignmentScope = if (appliedStateMapBasedStateAlignment) scope else null
    )

fun <K, V> DataStoreStateFlowMap<K, V>.reversibleStateMap(
    makeMap: (Map<K, V>) -> MutableMap<K, V>,
    onStateSynced: suspend (Map<K, V>) -> Unit = {},
    onStateReset: (Map<K, V>) -> Unit = {},
    appliedStateMapBasedStateAlignmentScope: CoroutineScope? = null
): ReversibleStateMap<K, V> =
    ReversibleStateMap(
        appliedStateMap = this,
        makeMap = makeMap,
        syncState = { save(it) },
        onStateSynced = onStateSynced,
        onStateReset = onStateReset,
        appliedStateMapBasedStateAlignmentScope = appliedStateMapBasedStateAlignmentScope
    )