package com.w2sv.reversiblestate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ReversibleStateCompositionTest {
    @Test
    fun `aggregate dirty reads immediately reflect child edits and reverts`() =
        runTest {
            val first = ReversibleStateFlow(backgroundScope, MutableStateFlow("A"), {})
            val second = ReversibleStateFlow(backgroundScope, MutableStateFlow("X"), {})
            val composition = ReversibleStateComposition(listOf(first, second), backgroundScope)
            assertFalse(composition.isDirty.value)
            second.value = "Y"
            assertTrue(composition.isDirty.value)
            composition.revert()
            assertEquals("X", second.value)
            assertFalse(composition.isDirty.value)
        }

    @Test
    fun `concurrent composition commits persist and notify only once`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val writes = mutableListOf<String>()
            val callbacks = mutableListOf<List<ReversibleState>>()
            val first = ReversibleStateFlow(backgroundScope, MutableStateFlow("A"), {
                entered.complete(Unit)
                release.await()
                writes += it.current
            })
            val second = ReversibleStateFlow(backgroundScope, MutableStateFlow("X"), { writes += it.current })
            val composition = ReversibleStateComposition(listOf(first, second), backgroundScope, onCommit = { callbacks += it })
            first.value = "B"
            val one = backgroundScope.launch { composition.commit() }
            entered.await()
            val two = backgroundScope.launch { composition.commit() }
            release.complete(Unit)
            one.join()
            two.join()
            assertEquals(listOf("B"), writes)
            assertEquals(1, callbacks.size)
            assertEquals(listOf(first), callbacks[0])
            assertFalse(composition.isDirty.value)
            assertFalse(composition.commit())
            assertEquals(1, callbacks.size)
        }

    @Test
    fun `callback excludes a child committed directly after composition selection`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val callbacks = mutableListOf<List<ReversibleState>>()
            val first = ReversibleStateFlow(backgroundScope, MutableStateFlow("A"), {
                entered.complete(Unit)
                release.await()
            })
            val secondWrites = mutableListOf<StateCommit<String>>()
            val second = ReversibleStateFlow(backgroundScope, MutableStateFlow("X"), { secondWrites += it })
            val composition = ReversibleStateComposition(listOf(first, second), backgroundScope, onCommit = { callbacks += it })
            first.value = "B"
            second.value = "Y"
            val committing = backgroundScope.launch { composition.commit() }
            entered.await()
            second.commit()
            release.complete(Unit)
            committing.join()

            assertEquals(listOf(StateCommit("X", "Y")), secondWrites)
            assertEquals(listOf<ReversibleState>(first), callbacks.single())
            assertFalse(composition.isDirty.value)
        }

    @Test
    fun `failure leaves earlier child persistence intact and suppresses callback`() =
        runTest {
            val firstApplied = MutableStateFlow("A")
            val first = ReversibleStateFlow(backgroundScope, firstApplied, { firstApplied.value = it.current })
            var failSecond = true
            val second = ReversibleStateFlow(backgroundScope, MutableStateFlow("X"), {
                if (failSecond) error("failed")
            })
            val callbacks = mutableListOf<List<ReversibleState>>()
            val composition = ReversibleStateComposition(listOf(first, second), backgroundScope, onCommit = { callbacks += it })
            first.value = "B"
            second.value = "Y"
            assertFailsWith<IllegalStateException> { composition.commit() }
            assertEquals("B", firstApplied.value)
            assertEquals("Y", second.value)
            assertTrue(callbacks.isEmpty())
            failSecond = false
            composition.commit()
            assertEquals(listOf<ReversibleState>(second), callbacks.single())
        }

    @Test
    fun `nested composition reports custom children without implementation type checks`() =
        runTest {
            val custom = object : ReversibleState {
                private val dirty = MutableStateFlow(true)
                override val isDirty: StateFlow<Boolean> = dirty
                var writes = 0

                override suspend fun commit(): Boolean {
                    if (!dirty.value) return false
                    writes++
                    dirty.value = false
                    return true
                }

                override fun launchCommit(): Job =
                    backgroundScope.launch { commit() }

                override fun revert() {
                    dirty.value = false
                }
            }
            val other = ReversibleStateFlow(backgroundScope, MutableStateFlow("A"), {})
            other.value = "B"
            val innerCallbacks = mutableListOf<List<ReversibleState>>()
            val inner = ReversibleStateComposition(listOf(custom), backgroundScope, onCommit = { innerCallbacks += it })
            val outerCallbacks = mutableListOf<List<ReversibleState>>()
            val outer = ReversibleStateComposition(listOf(inner, other), backgroundScope, onCommit = { outerCallbacks += it })

            assertTrue(outer.isDirty.value)
            assertTrue(outer.commit())
            assertEquals(1, custom.writes)
            assertEquals(listOf<ReversibleState>(custom), innerCallbacks.single())
            assertEquals(listOf<ReversibleState>(inner, other), outerCallbacks.single())
            assertFalse(outer.isDirty.value)
            assertFalse(outer.commit())
            assertEquals(1, outerCallbacks.size)
        }

    @Test
    fun `composition callback failure does not undo confirmed child persistence`() =
        runTest {
            val writes = mutableListOf<StateCommit<String>>()
            val child = ReversibleStateFlow(backgroundScope, MutableStateFlow("A"), { writes += it })
            val composition = ReversibleStateComposition(listOf(child), backgroundScope, onCommit = { error("reaction failed") })
            child.value = "B"
            assertFailsWith<IllegalStateException> { composition.commit() }
            assertFalse(child.isDirty.value)
            assertFalse(composition.commit())
            assertEquals(listOf(StateCommit("A", "B")), writes)
        }
}
