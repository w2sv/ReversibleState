package com.w2sv.reversiblestate

import app.cash.turbine.test
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ReversibleStateFlowTest {

    private val appliedState = MutableStateFlow("initial")
    private val scope = TestScope()

    @Test
    fun `editable state changes set isDirty`() =
        runTest {
            val stateFlow = ReversibleStateFlow(
                scope = scope,
                appliedState = appliedState,
                commitState = {}
            )

            stateFlow.value = "changed"
            stateFlow.isDirty.test { assertTrue(awaitItem()) }

            stateFlow.value = "another change"
            stateFlow.isDirty.test { assertTrue(awaitItem()) }

            stateFlow.value = "initial"
            stateFlow.isDirty.test { assertFalse(awaitItem()) }
        }

    @Test
    fun `autoSyncWithAppliedState updates editable state`() =
        runTest {
            val stateFlow = ReversibleStateFlow(
                scope = scope,
                appliedState = appliedState,
                commitState = {},
                autoSyncWithAppliedState = true
            )

            stateFlow.value = "temp"
            appliedState.value = "updated"

            scope.advanceUntilIdle()

            // Wait for flow propagation
            stateFlow.test { assertEquals("updated", awaitItem()) }
            stateFlow.isDirty.test { assertFalse(awaitItem()) }
        }

    @Test
    fun `commit calls commitState with current value`() =
        runTest {
            val stateFlow = ReversibleStateFlow(
                scope = scope,
                appliedState = appliedState,
                commitState = { appliedState.value = it }
            )

            stateFlow.value = "newValue"
            stateFlow.commit()

            advanceUntilIdle()

            appliedState.test { assertEquals("newValue", awaitItem()) }
            stateFlow.isDirty.test { assertFalse(awaitItem()) }
        }

    @Ignore
    @Test
    fun `launchCommit launches commit asynchronously`() =
        runTest {
            val stateFlow = ReversibleStateFlow(
                scope = scope,
                appliedState = appliedState,
                commitState = { appliedState.value = it },
                autoSyncWithAppliedState = false,
                log = { println(it()) }
            )

            stateFlow.value = "asyncValue"
            stateFlow.launchCommit().join()

            appliedState.test { assertEquals("asyncValue", awaitItem()) }
        }

    @Test
    fun `revert calls onStateRevert callback`() =
        runTest {
            var onRevertCalled = false
            val stateFlow = ReversibleStateFlow(
                scope = scope,
                appliedState = appliedState,
                commitState = {},
                onRevert = { onRevertCalled = true }
            )

            stateFlow.value = "something"
            stateFlow.revert()

            assertEquals("initial", stateFlow.value)
            assertTrue(onRevertCalled)
        }
}
