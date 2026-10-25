package com.codexusage.android

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant

class CoreTest {
    private fun auth(text: String) = AuthParser.parse(text.toByteArray())

    @Test fun importedCredentialsRequireSubscriptionTokensAndRemainRedacted() {
        val credentials = auth("""{"tokens":{"access_token":"secret-token","account_id":"account"}}""")
        assertEquals("secret-token", credentials.accessToken)
        assertFalse(credentials.toString().contains("secret-token"))
        assertFalse(credentials.toString().contains("account"))
        assertThrows(UserError::class.java) { auth("""{"OPENAI_API_KEY":"sk-example"}""") }
        assertThrows(UserError::class.java) { auth("""{"tokens":{"access_token":123,"account_id":"x"}}""") }
        assertThrows(UserError::class.java) { auth("""{"tokens":{"access_token":"x\r\nInjected: y","account_id":"x"}}""") }
        assertThrows(UserError::class.java) { auth("not json") }
        assertThrows(UserError::class.java) { AuthParser.parse(ByteArray(AUTH_MAX_BYTES + 1)) }
    }

    @Test fun boundedReadRejectsOversizedAndAcceptsExactLimit() {
        assertArrayEquals(byteArrayOf(1, 2), readBounded(ByteArrayInputStream(byteArrayOf(1, 2)), 2))
        assertThrows(UserError::class.java) { readBounded(ByteArrayInputStream(ByteArray(3)), 2) }
    }

    @Test fun usageKeepsAdditionalLimitsSeparateAndClampsPercentages() {
        val usage = UsageParser.parse("""{
            "plan_type":"plus",
            "rate_limit":{
              "primary_window":{"used_percent":25,"limit_window_seconds":18000,"reset_at":1800000000},
              "secondary_window":{"used_percent":120,"limit_window_seconds":604800,"reset_at":"2026-10-24T00:00:00Z"}},
            "additional_rate_limits":[{"limit_name":"Model","rate_limit":{"primary_window":{"used_percent":-10}}}]
        }""")
        assertEquals("plus", usage.plan)
        assertEquals(listOf("5h", "Weekly", "Model · 主周期"), usage.windows.map { it.label })
        assertEquals(listOf(75.0, 0.0, 100.0), usage.windows.map { it.remaining })
        assertFalse(usage.windows[0].additional)
        assertTrue(usage.windows[2].additional)
        assertEquals(Instant.parse("2026-10-24T00:00:00Z").epochSecond, usage.windows[1].resetAt)
    }

    @Test fun missingAndMalformedDisplayFieldsAreNotZeroQuota() {
        val usage = UsageParser.parse("""{"rate_limit":{"primary_window":{"used_percent":"25","reset_at":-1}}}""")
        assertNull(usage.windows[0].remaining)
        assertNull(usage.windows[0].resetAt)
        assertThrows(UserError::class.java) { UsageParser.parse("{}") }
    }

    @Test fun cacheOnlyStoresDisplayFields() {
        val usage = UsageParser.parse("""{"access_token":"should-not-be-cached","rate_limit":{"private":"secret","primary_window":{"used_percent":20,"limit_window_seconds":18000,"reset_at":1800000000}}}""")
        val cached = UsageParser.displayJson(usage)
        assertFalse(cached.contains("secret"))
        assertFalse(cached.contains("access_token"))
        assertEquals(usage, UsageParser.parse(cached))
    }

    @Test fun weeklyOnlyResponseSurvivesCacheInEitherWindowPosition() {
        for (key in listOf("primary_window", "secondary_window")) {
            val other = if (key == "primary_window") "secondary_window" else "primary_window"
            val usage = UsageParser.parse("""{"plan_type":"plus","rate_limit":{
                "$key":{"used_percent":0,"limit_window_seconds":604800,"reset_at":1800000000},
                "$other":null}}""")
            val restored = UsageParser.parse(UsageParser.displayJson(usage))
            assertEquals(usage, restored)
            assertEquals("Weekly", restored.windows.single().label)
            assertEquals(100.0, restored.windows.single().remaining!!, 0.0)
            assertTrue(weeklyOnly(widgetWindows(restored)))
        }
    }

    @Test fun rootPathsCannotInjectShellCommands() {
        assertEquals("'/data/a'\"'\"'b;${'$'}(id)/auth.json'", RootCommands.quotePath("/data/a'b;${'$'}(id)/auth.json"))
        assertThrows(UserError::class.java) { RootCommands.quotePath("relative/auth.json") }
        assertThrows(UserError::class.java) { RootCommands.quotePath("/data/a\ncat /secret") }
        assertTrue(RootCommands.read("/data/auth.json").contains("head -c ${AUTH_MAX_BYTES + 1}"))
        assertFalse(RootCommands.discover.contains("find /"))
    }

    @Test fun resetCreditDetailsKeepUnknownsAndCacheOnlyAllowedFields() {
        val details = ResetCreditsParser.parse("""{"available_count":2,"total_earned_count":4,
            "private":"secret","credits":[{"id":"test-credit","reset_type":"codex_rate_limits",
            "status":"available","granted_at":"2026-10-24T00:00:00Z","expires_at":"2026-11-24T00:00:00Z",
            "title":"Full reset","description":"Weekly + 5h","access_token":"secret"},
            {"status":"future_status","reset_type":"future_type","granted_at":"bad","expires_at":null}]}""")
        assertEquals(2, details.availableCount)
        assertEquals(4, details.totalEarnedCount)
        assertEquals(Instant.parse("2026-10-24T00:00:00Z").epochSecond, details.credits!![0].grantedAt)
        assertNull(details.credits[1].grantedAt)
        assertNull(details.credits[1].expiresAt)
        assertEquals("future_status", details.credits[1].status)
        val cached = ResetCreditsParser.displayJson(details)
        assertFalse(cached.contains("secret"))
        assertFalse(cached.contains("access_token"))
        assertEquals(details, ResetCreditsParser.parse(cached))
        assertNull(ResetCreditsParser.parse("""{"available_count":2}""").credits)
        assertTrue(ResetCreditsParser.parse("""{"available_count":0,"credits":[]}""").credits!!.isEmpty())
        for (count in listOf("-1", "1.5", "2147483648", "null", "\"2\"")) {
            assertThrows(UserError::class.java) { ResetCreditsParser.parse("""{"available_count":$count}""") }
        }
    }

    @Test fun rateLimitsRespectSecondsAndHttpDates() {
        val now = Instant.parse("2026-10-24T00:00:00Z")
        assertEquals(90L, retrySeconds("90", now))
        assertEquals(2L, retrySeconds("1.2", now))
        assertEquals(1L, retrySeconds("0", now))
        assertEquals(60L, retrySeconds("Sat, 24 Oct 2026 00:01:00 GMT", now))
        assertEquals(60L, retrySeconds("bad header", now))
        assertEquals(60L, retrySeconds("NaN", now))
        assertEquals(315360000L, retrySeconds("99999999999999", now))
        assertEquals(315360000L, retrySeconds("Fri, 31 Dec 9999 00:00:00 GMT", now))
    }
}
