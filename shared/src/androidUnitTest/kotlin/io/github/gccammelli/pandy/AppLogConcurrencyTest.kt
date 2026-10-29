package io.github.gccammelli.pandy

import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class AppLogConcurrencyTest {
    @Test fun simultaneousWritersKeepEveryEntryWithinTheLimit() {
        AppLog.clear()
        val workers = (0 until 4).map { worker ->
            thread { repeat(50) { AppLog.log("INFO", "test", "$worker:$it") } }
        }
        workers.forEach { it.join() }
        assertEquals(200, AppLog.snapshot().size)
        assertEquals(200, AppLog.snapshot().toSet().size)
        AppLog.clear()
    }
}
