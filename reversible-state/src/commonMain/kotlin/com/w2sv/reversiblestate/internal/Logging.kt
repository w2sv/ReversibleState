package com.w2sv.reversiblestate.internal

internal val Any.logIdentifier: String
    get() = this::class.simpleName ?: "Unknown"
