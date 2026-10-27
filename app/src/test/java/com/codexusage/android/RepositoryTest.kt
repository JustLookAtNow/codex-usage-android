package com.codexusage.android

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RepositoryTest {
    private lateinit var context: Context
    private val usageBody = """{"rate_limit":{"primary_window":{"used_percent":25,"limit_window_seconds":18000,"reset_at":1800000000},"secondary_window":{"used_percent":50,"limit_window_seconds":604800,"reset_at":1800000000}}}"""

    @Before fun reset() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().clear().putString("mode", "import").commit()
        UsageRepository(context).clear()
    }

    private class Response(url: URL, private val code: Int, private val body: String, private val retry: String?) : HttpURLConnection(url) {
        var disconnected = false
        val sent = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getInputStream() = body.byteInputStream()
        override fun getOutputStream() = sent
        override fun getHeaderField(name: String?) = if (name == "Retry-After") retry else null
    }

    @Test fun refreshRereadsSourceAndFailureKeepsPreviousDataAndTimestamp() {
        var reads = 0
        var failure = false
        val connections = mutableListOf<Response>()
        val repository = UsageRepository(context, {
            reads++
            Credentials("test-token-$reads", "test-account")
        }, { url ->
            Response(url, if (failure) 401 else 200,
                if (url.path.endsWith("/usage")) usageBody else """{"available_count":3}""", null)
                .also { connections += it }
        })
        repository.refresh()
        val cached = repository.body
        val updated = repository.updatedAt
        assertEquals(3, repository.credits)
        assertEquals(75.0, repository.usage()!!.windows.first().remaining!!, 0.0)
        failure = true
        repository.refresh()
        assertEquals(2, reads)
        assertEquals(cached, repository.body)
        assertEquals(updated, repository.updatedAt)
        assertEquals(3, repository.credits)
        assertTrue(repository.error!!.contains("凭据已失效"))
        assertTrue(connections.all { it.disconnected && !it.instanceFollowRedirects })
        assertEquals("Bearer test-token-2", connections.last().getRequestProperty("Authorization"))
    }

    @Test fun timeoutKeepsLastUsageAndUsesOneSecondTimeouts() {
        var timeout = false
        val connections = mutableListOf<HttpURLConnection>()
        val repository = UsageRepository(context, { Credentials("test-token", "test-account") }, { url ->
            (if (timeout) object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode(): Int = throw java.net.SocketTimeoutException()
            } else Response(url, 200, if (url.path.endsWith("/usage")) usageBody else """{"available_count":3}""", null))
                .also { connections += it }
        })
        repository.refresh()
        val cached = repository.body
        val updated = repository.updatedAt
        timeout = true
        repository.refresh()
        assertEquals(cached, repository.body)
        assertEquals(updated, repository.updatedAt)
        assertEquals("连接超时，请稍后刷新", repository.error)
        assertTrue(connections.all { it.connectTimeout == 1000 && it.readTimeout == 1000 })
    }

    @Test fun rateLimitPersistsAcrossRepositoryInstancesAndManualRefreshCannotBypassIt() {
        var requests = 0
        val repository = UsageRepository(context, { Credentials("test-token", "test-account") }, { url ->
            requests++
            Response(url, 429, "", "120")
        })
        repository.refresh()
        repository.refresh()
        UsageRepository(context, { Credentials("test-token", "test-account") }, { error("must wait before opening another connection") }).refresh()
        assertEquals(1, requests)
        assertTrue(repository.error!!.contains("请求受限"))
        repository.clear()
        assertNull(repository.body)
        assertEquals(0, repository.updatedAt)
        assertFalse(context.getSharedPreferences("usage", Context.MODE_PRIVATE).contains("retry_usage"))
    }

    @Test fun failedCreditsQueryKeepsOldCountAndDoesNotDiscardSuccessfulUsage() {
        var failure = false
        val repository = UsageRepository(context, { Credentials("test-token", "test-account") }, { url ->
            val credits = url.path.endsWith("/rate-limit-reset-credits")
            Response(url, if (failure && credits) 403 else 200,
                if (credits) """{"available_count":4,"credits":[{"id":"test-credit","title":"Test reset"}]}""" else usageBody, null)
        })
        repository.refresh()
        val details = repository.resetCredits()
        val creditsUpdated = repository.creditsUpdatedAt
        failure = true
        repository.refresh()
        assertEquals(4, repository.credits)
        assertNotNull(repository.creditsError)
        assertNull(repository.error)
        assertNotNull(repository.usage())
        assertEquals(details, repository.resetCredits())
        assertEquals(creditsUpdated, repository.creditsUpdatedAt)
    }

    @Test fun noConfigurationNeverReadsCredentialsOrConnects() {
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().clear().commit()
        UsageRepository(context, { error("must not load credentials") }, { error("must not connect") }).refresh()
        assertNull(UsageRepository(context).body)
        assertNull(UsageRepository(context).error)
        assertThrows(UserError::class.java) {
            UsageRepository(context, { error("must not load credentials") }, { error("must not connect") }).consumeReset()
        }
    }

    @Test fun consumingUsesPostThenFetchesAuthoritativeWindowsAndCredits() {
        var consumed = false
        val calls = mutableListOf<Response>()
        val repository = UsageRepository(context, { Credentials("test-token", "test-account") }, { url ->
            val body = when {
                url.path.endsWith("/consume") -> { consumed = true; """{"code":"reset","windows_reset":2}""" }
                url.path.endsWith("/usage") -> usageBody.replace("25", "0")
                else -> """{"available_count":${if (consumed) 1 else 2},"credits":[{"id":"test-credit-1","status":"available"},{"id":"test-credit-2","status":"${if (consumed) "consumed" else "available"}"}]}"""
            }
            Response(url, 200, body, null).also { calls += it }
        })
        assertTrue(repository.consumeReset("test-credit-2").contains("重置成功"))
        val post = calls.single { it.requestMethod == "POST" }
        assertTrue(post.url.path.endsWith("/rate-limit-reset-credits/consume"))
        assertEquals("application/json", post.getRequestProperty("Content-Type"))
        assertTrue(org.json.JSONObject(post.sent.toString("UTF-8")).getString("redeem_request_id").isNotBlank())
        assertEquals("test-credit-2", org.json.JSONObject(post.sent.toString("UTF-8")).getString("credit_id"))
        assertEquals(1, repository.credits)
        assertEquals(100.0, repository.usage()!!.windows.first().remaining!!, 0.0)
        assertFalse(repository.resetPending)
        assertTrue(calls.all { it.disconnected })
    }

    @Test fun uncertainResponseKeepsRequestIdAcrossRestartAndRefreshNeverConsumes() {
        val payloads = mutableListOf<String>()
        var available = 1
        val open: (URL) -> HttpURLConnection = { url ->
            if (url.path.endsWith("/consume")) object : HttpURLConnection(url) {
                private val sent = ByteArrayOutputStream()
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getOutputStream() = sent
                override fun getResponseCode(): Int {
                    payloads += sent.toString("UTF-8")
                    if (payloads.size == 1) { available = 0; throw java.net.SocketTimeoutException() }
                    return 200
                }
                override fun getInputStream() = """{"code":"already_redeemed"}""".byteInputStream()
            } else Response(url, 200, if (url.path.endsWith("/usage")) usageBody
                else """{"available_count":$available,"credits":[{"id":"test-credit-1","status":"available"}]}""", null)
        }
        val repository = UsageRepository(context, { Credentials("test-token", "test-account") }, open)
        assertThrows(UserError::class.java) { repository.consumeReset("test-credit-1") }
        assertTrue(repository.resetPending)
        repository.refresh()
        assertEquals(1, payloads.size)
        assertEquals(0, repository.credits)
        assertThrows(UserError::class.java) { repository.consumeReset("test-credit-2") }
        assertEquals(1, payloads.size)
        val restarted = UsageRepository(context, { Credentials("new-token", "test-account") }, open)
        assertTrue(restarted.consumeReset().contains("已完成"))
        assertEquals(payloads[0], payloads[1])
        assertEquals("test-credit-1", org.json.JSONObject(payloads[1]).getString("credit_id"))
        assertFalse(restarted.resetPending)
    }

    @Test fun zeroCreditsPreventsPostAndTerminalOutcomesDoNotClaimSuccess() {
        var count = 0
        var code = "nothing_to_reset"
        var posts = 0
        val repository = UsageRepository(context, { Credentials("test-token", "test-account") }, { url ->
            val body = when {
                url.path.endsWith("/consume") -> { posts++; """{"code":"$code"}""" }
                url.path.endsWith("/usage") -> usageBody
                else -> """{"available_count":$count}"""
            }
            Response(url, 200, body, null)
        })
        assertThrows(UserError::class.java) { repository.consumeReset() }
        assertEquals(0, posts)
        count = 1
        assertThrows(UserError::class.java) { repository.consumeReset("missing-credit") }
        assertEquals(0, posts)
        assertTrue(repository.consumeReset().contains("没有符合条件"))
        assertFalse(repository.resetPending)
        code = "no_credit"
        assertTrue(repository.consumeReset().contains("没有可用"))
        assertFalse(repository.resetPending)
    }

    @Test fun pendingResetCannotMoveToAnotherAccountAndUnknownResultStaysPending() {
        var account = "first"
        var posts = 0
        val repository = UsageRepository(context, { Credentials("test-token", account) }, { url ->
            val body = if (url.path.endsWith("/consume")) { posts++; """{"code":"future_code"}""" }
                else """{"available_count":2}"""
            Response(url, 200, body, null)
        })
        assertThrows(UserError::class.java) { repository.consumeReset() }
        assertTrue(repository.resetPending)
        account = "second"
        assertThrows(UserError::class.java) { repository.consumeReset() }
        assertEquals(1, posts)
        repository.clear()
        assertFalse(repository.resetPending)
        assertNull(repository.resetMessage)
    }

    @Test fun resetRateLimitPersistsAndRetryDoesNotSendAnotherPostDuringBackoff() {
        var posts = 0
        val repository = UsageRepository(context, { Credentials("test-token", "test-account") }, { url ->
            if (url.path.endsWith("/consume")) {
                posts++
                Response(url, 429, "", "120")
            } else Response(url, 200, """{"available_count":2}""", null)
        })
        assertThrows(UserError::class.java) { repository.consumeReset() }
        val pending = context.getSharedPreferences("usage", Context.MODE_PRIVATE).getString("reset_request_id", null)
        assertThrows(UserError::class.java) { repository.consumeReset() }
        assertEquals(1, posts)
        assertTrue(repository.resetPending)
        assertEquals(pending, context.getSharedPreferences("usage", Context.MODE_PRIVATE).getString("reset_request_id", null))
    }

    @Test fun oversizedOrInvalidImportDoesNotReplaceAnExistingConfiguration() {
        val prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("mode", "root").putString("path", "/test/auth.json").commit()
        assertThrows(UserError::class.java) { CredentialStore(context).import("invalid-json".toByteArray()) }
        assertThrows(UserError::class.java) { CredentialStore(context).import(ByteArray(AUTH_MAX_BYTES + 1)) }
        assertEquals("root", CredentialStore(context).mode)
        assertEquals("/test/auth.json", CredentialStore(context).rootPath)
        CredentialStore(context).clear()
        assertTrue(prefs.all.isEmpty())
    }
}
