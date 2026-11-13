package com.w2sv.reversiblestate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ReversibleStateFlowTest {
    @Test
    fun `editing and dirty reads are synchronous and collections suppress equal values`() =
        runTest {
            val applied = MutableStateFlow("A")
            val state = ReversibleStateFlow(backgroundScope, applied, {})
            val values = mutableListOf<String>()
            val dirty = mutableListOf<Boolean>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.take(3).toList(values) }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.isDirty.take(3).toList(dirty) }

            state.value = "B"
            assertEquals("B", state.value)
            assertTrue(state.isDirty.value)
            state.value = "B"
            state.update { it }
            state.update { "A" }
            assertFalse(state.isDirty.value)
            assertEquals(listOf("A", "B", "A"), values)
            assertEquals(listOf(false, true, false), dirty)
        }

    @Test
    fun `update is atomic under contention`() =
        runTest {
            val state = ReversibleStateFlow(backgroundScope, MutableStateFlow(0), {})
            List(8) {
                async(Dispatchers.Default) { repeat(500) { state.update { it + 1 } } }
            }.awaitAll()
            assertEquals(4000, state.value)
            assertTrue(state.isDirty.value)
        }

    @Test
    fun `collectors stop on cancellation and new commit collectors receive no replay`() =
        runTest {
            val applied = MutableStateFlow("A")
            val editor = ReversibleStateFlow(backgroundScope, applied, {})
            val values = mutableListOf<String>()
            val collecting = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                editor.collect { values += it }
            }
            editor.value = "B"
            collecting.cancel()
            editor.value = "C"
            assertEquals(listOf("A", "B"), values)
            editor.commit()
            assertTrue(editor.commits.replayCache.isEmpty())
            val events = mutableListOf<StateCommit<String>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { editor.commits.collect { events += it } }
            assertTrue(events.isEmpty())
            editor.value = "D"
            editor.commit()
            assertEquals(listOf(StateCommit("C", "D")), events)
        }

    @Test
    fun `policies handle clean and dirty external changes`() =
        runTest {
            for (policy in AppliedStateSyncPolicy.entries) {
                val applied = MutableStateFlow("A")
                val state = ReversibleStateFlow(backgroundScope, applied, {}, syncPolicy = policy)
                runCurrent()
                applied.value = "B"
                runCurrent()
                assertEquals(if (policy == AppliedStateSyncPolicy.Never) "A" else "B", state.value)
                state.value = "local"
                applied.value = "C"
                runCurrent()
                assertEquals(if (policy == AppliedStateSyncPolicy.Always) "C" else "local", state.value)
                assertEquals(state.value != "C", state.isDirty.value)
                state.revert()
                assertEquals("C", state.value)
                assertFalse(state.isDirty.value)
            }
        }

    @Test
    fun `Always copies ambiguous emissions without changing the trusted baseline`() =
        runTest {
            val applied = MutableStateFlow("A")
            val state = ReversibleStateFlow(backgroundScope, applied, {}, syncPolicy = AppliedStateSyncPolicy.Always)
            runCurrent()
            state.value = "B"
            assertTrue(state.commit())
            applied.value = "C"
            runCurrent()
            assertEquals("C", state.value)
            assertTrue(state.isDirty.value)
            state.revert()
            assertEquals("B", state.value)
            assertFalse(state.isDirty.value)
        }

    @Test
    fun `edit before applied collection starts is preserved`() =
        runTest {
            val applied = MutableStateFlow("A")
            val state = ReversibleStateFlow(backgroundScope, applied, {})
            state.value = "local"
            applied.value = "external"
            runCurrent()
            assertEquals("local", state.value)
            assertTrue(state.isDirty.value)
            state.revert()
            assertEquals("external", state.value)
        }

    @Test
    fun `editing back to previous applied during commit survives acknowledgement`() =
        runTest {
            val applied = MutableStateFlow("A")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val state = ReversibleStateFlow(backgroundScope, applied, { commit ->
                entered.complete(Unit)
                release.await()
                applied.value = commit.current
            })
            runCurrent()
            state.value = "B"
            val job = backgroundScope.launch { state.commit() }
            entered.await()
            state.value = "A"
            release.complete(Unit)
            job.join()
            runCurrent()
            assertEquals("A", state.value)
            assertEquals("B", applied.value)
            assertTrue(state.isDirty.value)
        }

    @Test
    fun `revert during commit survives delayed acknowledgement and later revert uses new baseline`() =
        runTest {
            val applied = MutableStateFlow("A")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val state = ReversibleStateFlow(backgroundScope, applied, {
                entered.complete(Unit)
                release.await()
            })
            runCurrent()
            state.value = "B"
            val job = backgroundScope.launch { state.commit() }
            entered.await()
            state.revert()
            release.complete(Unit)
            job.join()
            applied.value = "B"
            runCurrent()
            assertEquals("A", state.value)
            assertTrue(state.isDirty.value)
            state.revert()
            assertEquals("B", state.value)
            assertFalse(state.isDirty.value)
        }

    @Test
    fun `commits serialize and skip repeated snapshots before applied emission`() =
        runTest {
            val applied = MutableStateFlow("A")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val writes = mutableListOf<StateCommit<String>>()
            val events = mutableListOf<StateCommit<String>>()
            val state = ReversibleStateFlow(backgroundScope, applied, { commit ->
                entered.complete(Unit)
                release.await()
                writes += commit
            })
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.commits.collect { events += it } }
            state.value = "B"
            val first = backgroundScope.async { state.commit() }
            entered.await()
            val second = backgroundScope.async { state.commit() }
            release.complete(Unit)
            assertTrue(first.await())
            assertFalse(second.await())
            assertFalse(state.commit())
            assertEquals(listOf(StateCommit("A", "B")), writes)
            assertEquals(writes, events)
            assertFalse(state.isDirty.value) // Observed applied state is still A.
        }

    @Test
    fun `new edits during persistence commit in order with local previous state`() =
        runTest {
            val applied = MutableStateFlow("A")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val writes = mutableListOf<StateCommit<String>>()
            val events = mutableListOf<StateCommit<String>>()
            val state = ReversibleStateFlow(backgroundScope, applied, { commit ->
                if (writes.isEmpty()) {
                    entered.complete(Unit)
                    release.await()
                }
                writes += commit
            })
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.commits.collect { events += it } }
            state.value = "B"
            val first = backgroundScope.launch { state.commit() }
            entered.await()
            state.value = "C"
            val second = backgroundScope.launch { state.commit() }
            release.complete(Unit)
            first.join()
            second.join()
            assertEquals(listOf(StateCommit("A", "B"), StateCommit("B", "C")), writes)
            assertEquals(writes, events)
            assertEquals("C", state.value)
            assertFalse(state.isDirty.value)
        }

    @Test
    fun `a successful commit leaves a newer draft dirty without waiting for applied emission`() =
        runTest {
            val applied = MutableStateFlow("A")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val state = ReversibleStateFlow(backgroundScope, applied, {
                entered.complete(Unit)
                release.await()
            })
            state.value = "B"
            val committing = backgroundScope.launch { state.commit() }
            entered.await()
            state.value = "C"
            release.complete(Unit)
            committing.join()
            assertEquals("C", state.value)
            assertTrue(state.isDirty.value)
            assertEquals("A", applied.value)
        }

    @Test
    fun `older applied emission after consecutive commits does not trigger duplicate persistence`() =
        runTest {
            val applied = MutableStateFlow("A")
            val writes = mutableListOf<StateCommit<String>>()
            val state = ReversibleStateFlow(backgroundScope, applied, { writes += it })
            runCurrent()
            state.value = "B"
            state.commit()
            state.value = "C"
            state.commit()

            applied.value = "B"
            runCurrent()
            state.commit()
            assertEquals(listOf(StateCommit("A", "B"), StateCommit("B", "C")), writes)
            assertEquals("C", state.value)
            assertFalse(state.isDirty.value)

            applied.value = "C"
            runCurrent()
            assertFalse(state.isDirty.value)
            state.value = "D"
            state.commit()
            assertEquals(StateCommit("C", "D"), writes.last())
        }

    @Test
    fun `failed and cancelled commits have no events and can retry`() =
        runTest {
            val applied = MutableStateFlow("A")
            val events = mutableListOf<StateCommit<String>>()
            var fail = true
            val state = ReversibleStateFlow(backgroundScope, applied, { if (fail) error("failed") })
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.commits.collect { events += it } }
            assertFalse(state.commit())
            state.value = "B"
            assertFailsWith<IllegalStateException> { state.commit() }
            assertTrue(events.isEmpty())
            assertTrue(state.isDirty.value)
            fail = false
            assertTrue(state.commit())
            assertEquals(listOf(StateCommit("A", "B")), events)

            val gate = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val cancelled = ReversibleStateFlow(backgroundScope, applied, {
                entered.complete(Unit)
                gate.await()
            })
            val cancelledEvents = mutableListOf<StateCommit<String>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                cancelled.commits.collect { cancelledEvents += it }
            }
            cancelled.value = "C"
            val job = backgroundScope.launch { cancelled.commit() }
            entered.await()
            job.cancel(CancellationException("cancel"))
            job.join()
            assertTrue(cancelledEvents.isEmpty())
            assertEquals("C", cancelled.value)
            assertTrue(cancelled.isDirty.value)
        }

    @Test
    fun `older equal emission cannot make a failed repeated-value commit look successful`() =
        runTest {
            val applied = MutableStateFlow("A")
            val thirdStarted = CompletableDeferred<Unit>()
            val releaseThird = CompletableDeferred<Unit>()
            val attempts = mutableListOf<StateCommit<String>>()
            val events = mutableListOf<StateCommit<String>>()
            var failThird = true
            val state = ReversibleStateFlow(backgroundScope, applied, { transition ->
                attempts += transition
                if (attempts.size >= 3 && failThird) {
                    thirdStarted.complete(Unit)
                    releaseThird.await()
                    error("third write failed")
                }
            })
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.commits.collect { events += it } }
            runCurrent()
            state.value = "B"
            state.commit()
            state.value = "C"
            state.commit()
            state.value = "B"
            val failed = backgroundScope.async { runCatching { state.commit() } }
            thirdStarted.await()
            applied.value = "B" // The first write's delayed emission has the third write's target value.
            runCurrent()
            releaseThird.complete(Unit)
            assertTrue(failed.await().isFailure)
            assertTrue(state.isDirty.value)

            failThird = false
            state.commit()
            assertEquals(
                listOf(StateCommit("A", "B"), StateCommit("B", "C"), StateCommit("C", "B"), StateCommit("C", "B")),
                attempts
            )
            assertEquals(attempts.filterIndexed { index, _ -> index != 2 }, events)
            assertFalse(state.isDirty.value)
        }

    @Test
    fun `conflated repeated values cannot regress a later successful commit`() =
        runTest {
            val applied = MutableStateFlow("A")
            val writes = mutableListOf<StateCommit<String>>()
            val state = ReversibleStateFlow(backgroundScope, applied, { writes += it })
            runCurrent()
            repeat(100) {
                state.value = "B"
                state.commit()
                state.value = "A"
                state.commit()
            }
            assertFalse(state.isDirty.value)
            assertEquals(200, writes.size)
            applied.value = "B" // Other emissions may have been conflated or never delivered.
            runCurrent()
            assertEquals("A", state.value)
            assertFalse(state.isDirty.value)
            state.commit()
            assertEquals(200, writes.size)
            state.revert()
            assertEquals("A", state.value)
            assertFalse(state.isDirty.value)
        }

    @Test
    fun `editing back before synchronization begins preserves local intent`() =
        runTest {
            val applied = MutableStateFlow("A")
            val state = ReversibleStateFlow(backgroundScope, applied, {})
            state.value = "B"
            state.value = "A"
            applied.value = "C"
            runCurrent()
            assertEquals("A", state.value)
            assertTrue(state.isDirty.value)
            state.revert()
            assertEquals("C", state.value)
            assertFalse(state.isDirty.value)
            applied.value = "D"
            runCurrent()
            assertEquals("D", state.value)
            assertFalse(state.isDirty.value)
        }

    @Test
    fun `cancellation after external write but before callback confirmation keeps commit retryable`() =
        runTest {
            val applied = MutableStateFlow("A")
            val events = mutableListOf<StateCommit<String>>()
            val state = ReversibleStateFlow(backgroundScope, applied, { transition ->
                applied.value = transition.current
                currentCoroutineContext().cancel()
            })
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.commits.collect { events += it } }
            state.value = "B"
            val job = backgroundScope.launch { state.commit() }
            job.join()
            runCurrent()
            assertTrue(job.isCancelled)
            assertEquals("B", applied.value)
            assertTrue(state.isDirty.value)
            assertTrue(events.isEmpty())
        }

    @Test
    fun `launchCommit persists asynchronously`() =
        runTest {
            val applied = MutableStateFlow("A")
            val state = ReversibleStateFlow(backgroundScope, applied, { applied.value = it.current })
            state.value = "B"
            state.launchCommit().join()
            runCurrent()
            assertEquals("B", applied.value)
            assertFalse(state.isDirty.value)
        }
}
