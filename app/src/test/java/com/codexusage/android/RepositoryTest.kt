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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
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
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getInputStream() = body.byteInputStream()
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
                if (credits) """{"available_count":4}""" else usageBody, null)
        })
        repository.refresh()
        failure = true
        repository.refresh()
        assertEquals(4, repository.credits)
        assertNotNull(repository.creditsError)
        assertNull(repository.error)
        assertNotNull(repository.usage())
    }

    @Test fun noConfigurationNeverReadsCredentialsOrConnects() {
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().clear().commit()
        UsageRepository(context, { error("must not load credentials") }, { error("must not connect") }).refresh()
        assertNull(UsageRepository(context).body)
        assertNull(UsageRepository(context).error)
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
