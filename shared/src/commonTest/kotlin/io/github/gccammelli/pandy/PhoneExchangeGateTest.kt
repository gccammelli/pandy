package io.github.gccammelli.pandy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PhoneExchangeGateTest {
    @Test fun overlappingRequestsAreRejectedAndCancellationAllowsRetry() = runTest {
        val gate = PhoneExchangeGate()
        val waiting = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { gate.run { waiting.await() } }
        assertFailsWith<ApiException> { gate.run { "second" } }
        first.cancelAndJoin()
        assertEquals("retry", gate.run { "retry" })
        assertFailsWith<IllegalStateException> { gate.run { error("failed") } }
        assertEquals("next", gate.run { "next" })
    }
}
