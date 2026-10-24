package com.codexusage.android

import org.junit.Assert.*
import org.junit.Test

class RootAccessTest {
    private fun execute(command: String, timeout: Long = 1000) = RootAccess.execute(command, timeout) {
        ProcessBuilder("sh", "-c", it).start()
    }

    @Test fun successfulRootReadReturnsOnlyStdout() {
        assertEquals("example", execute("printf example").toString(Charsets.UTF_8))
    }

    @Test fun deniedRootAndMissingSuHaveReadableErrors() {
        val denied = assertThrows(UserError::class.java) { execute("exit 13") }
        assertTrue(denied.message!!.contains("未授权"))
        val missing = assertThrows(UserError::class.java) {
            RootAccess.execute("ignored") { throw java.io.IOException("missing") }
        }
        assertTrue(missing.message!!.contains("无法调用 su"))
    }

    @Test(timeout = 5000) fun rootTimeoutStopsWaitingAndReportsFailure() {
        val error = assertThrows(UserError::class.java) { execute("exec sleep 20", 100) }
        assertTrue(error.message!!.contains("超时"))
    }

    @Test fun oversizedRootReadIsRejected() {
        assertThrows(UserError::class.java) { execute("head -c ${AUTH_MAX_BYTES + 1} /dev/zero") }
    }

    @Test(timeout = 5000) fun inheritedChildPipeCannotBlockTimeoutCleanup() {
        val started = System.nanoTime()
        assertThrows(UserError::class.java) { execute("sleep 3", 100) }
        assertTrue("Timeout cleanup must not wait for the inherited pipe", (System.nanoTime() - started) / 1_000_000 < 1500)
    }
}
