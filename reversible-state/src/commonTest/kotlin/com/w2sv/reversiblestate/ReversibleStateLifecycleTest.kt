package com.w2sv.reversiblestate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ReversibleStateLifecycleTest {
    @Test
    fun `Revert after successful commit uses trusted value despite delayed emission`() =
        runTest {
            val applied = MutableStateFlow("A")
            val editor = ReversibleStateFlow(backgroundScope, applied, {})
            editor.value = "B"
            assertTrue(editor.commit())
            assertEquals("A", applied.value)
            assertFalse(editor.isDirty.value)
            editor.value = "C"
            editor.revert()
            assertEquals("B", editor.value)
            assertFalse(editor.isDirty.value)
            applied.value = "A-old"
            runCurrent()
            assertEquals("B", editor.value)
            assertFalse(editor.isDirty.value)
        }

    @Test
    fun `Revert during pending commit stays reverted when commit succeeds`() =
        runTest {
            val applied = MutableStateFlow("A")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val editor = ReversibleStateFlow(backgroundScope, applied, {
                entered.complete(Unit)
                release.await()
            })
            editor.value = "B"
            val committing = backgroundScope.launch { editor.commit() }
            entered.await()
            editor.revert()
            assertEquals("A", editor.value)
            assertFalse(editor.isDirty.value)
            release.complete(Unit)
            committing.join()
            assertEquals("A", editor.value)
            assertTrue(editor.isDirty.value)
            applied.value = "B"
            runCurrent()
            assertEquals("A", editor.value)
            editor.revert()
            assertEquals("B", editor.value)
            assertFalse(editor.isDirty.value)
        }

    @Test
    fun `failed commit remains retryable and Revert uses trusted baseline`() =
        runTest {
            val applied = MutableStateFlow("A")
            var fail = true
            val writes = mutableListOf<StateCommit<String>>()
            val editor = ReversibleStateFlow(backgroundScope, applied, { transition ->
                writes += transition
                if (fail) error("failed")
            })
            editor.value = "B"
            assertFailsWith<IllegalStateException> { editor.commit() }
            applied.value = "B" // Equal to failed target, but not a confirmed write from this instance.
            runCurrent()
            assertTrue(editor.isDirty.value)
            editor.revert()
            assertEquals("A", editor.value)
            assertFalse(editor.isDirty.value)
            editor.value = "B"
            fail = false
            assertTrue(editor.commit())
            assertEquals(listOf(StateCommit("A", "B"), StateCommit("A", "B")), writes)
        }

    @Test
    fun `multiple confirmed commits remain the Revert baseline despite old observations`() =
        runTest {
            val applied = MutableStateFlow("A")
            val writes = mutableListOf<StateCommit<String>>()
            val editor = ReversibleStateFlow(backgroundScope, applied, { writes += it })
            editor.value = "B"
            editor.commit()
            editor.value = "C"
            editor.commit()
            applied.value = "B"
            runCurrent()
            editor.value = "D"
            editor.revert()
            assertEquals("C", editor.value)
            assertFalse(editor.isDirty.value)
            assertEquals(listOf(StateCommit("A", "B"), StateCommit("B", "C")), writes)
        }

    @Test
    fun `external emissions after first attempt cannot replace draft or trusted baseline`() =
        runTest {
            val applied = MutableStateFlow("A")
            val writes = mutableListOf<StateCommit<String>>()
            val editor = ReversibleStateFlow(backgroundScope, applied, { writes += it })
            runCurrent()
            applied.value = "external-before"
            runCurrent()
            assertEquals("external-before", editor.value)
            editor.value = "B"
            editor.commit()
            applied.value = "external-after"
            runCurrent()
            assertEquals("B", editor.value)
            assertFalse(editor.isDirty.value)
            editor.value = "C"
            editor.revert()
            assertEquals("B", editor.value)
            editor.value = "D"
            editor.commit()
            assertEquals(StateCommit("B", "D"), writes.last())
        }
}
