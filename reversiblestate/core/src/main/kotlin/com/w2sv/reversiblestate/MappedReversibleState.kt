package com.w2sv.reversiblestate

abstract class MappedReversibleState<K, V> : AbstractReversibleState() {

    /**
     * Keys whose values have changed.
     */
    protected val dissimilarKeysMutable = mutableSetOf<K>()

    val dissimilarKeys: Set<K>
        get() = dissimilarKeysMutable

    protected fun resetDissimilarityTrackers() {
        dissimilarKeysMutable.clear()
        statesDissimilarMutable.value = false
    }
}
