package com.codexusage.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatGptAuthParserTest {
    private val now = 1_800_000_000_000L
    private val sessionId = "active-session"
    private val prefix = "sessions.${sessionId.length}:$sessionId."
    private val session = """{"v":1,"d":{"user_id":"active-user","is_anonymous":false,"auth_session_id":"$sessionId"}}""".toByteArray()

    private fun jwt(account: String? = "active-account", expiry: Long = now / 1000 + 3600): String {
        fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        val payload = JSONObject().put("exp", expiry).put("https://api.openai.com/auth", JSONObject().apply {
            if (account != null) put("chatgpt_account_id", account)
        })
        return "${encode("""{"alg":"RS256","typ":"JWT"}""")}.${encode(payload.toString())}.fake-signature"
    }

    private fun xml(token: String = jwt(), expiry: Long = now + 3600000, user: String = "active-user", extra: String = ""): ByteArray = """
        <?xml version="1.0" encoding="utf-8"?>
        <map>
          <string name="${prefix}user_id">$user</string>
          <string name="${prefix}com.auth0.access_token">$token</string>
          <string name="${prefix}com.auth0.refresh_token">must-not-return</string>
          <string name="${prefix}com.auth0.id_token">must-not-return-either</string>
          <string name="${prefix}com.auth0.token_type">DPoP</string>
          <long name="${prefix}com.auth0.expires_at" value="$expiry" />
          $extra
        </map>
    """.trimIndent().trimStart().toByteArray()

    private fun parse(bytes: ByteArray, index: ByteArray = session) = ChatGptAuthParser.parse(bytes, index, now)

    @Test fun usesCurrentSessionEvenWithLegacyAndNewerUnrelatedCredentials() {
        val token = jwt()
        val credentials = parse(xml(token, extra = """
            <string name="users.11:active-user.com.auth0.access_token">expired-legacy-token</string>
            <string name="sessions.5:other.com.auth0.access_token">unrelated-token</string>
            <long name="sessions.5:other.com.auth0.expires_at" value="${now + 7200000}" />
        """.trimIndent()))
        assertEquals(token, credentials.accessToken)
        assertEquals("active-account", credentials.accountId)
        assertFalse(credentials.toString().contains(token))
        assertFalse(credentials.toString().contains("active-account"))
    }

    @Test fun sourceTokenUpdateIsReadOnNextParse() {
        assertEquals("active-account", parse(xml()).accountId)
        assertEquals("updated-account", parse(xml(jwt("updated-account"))).accountId)
    }

    @Test fun loggedOutOrMissingActiveSessionNeverFallsBackToOtherTokens() {
        val loggedOut = """{"d":{"is_anonymous":true}}""".toByteArray()
        assertTrue(assertThrows(UserError::class.java) { parse(xml(), loggedOut) }.message!!.contains("登录"))
        assertThrows(UserError::class.java) { parse(xml(user = "another-user")) }
        assertThrows(UserError::class.java) { parse("<map/>".toByteArray()) }
        assertThrows(UserError::class.java) { parse(xml(), "{}".toByteArray()) }
    }

    @Test fun checksBothStoredAndJwtExpiry() {
        assertTrue(assertThrows(UserError::class.java) { parse(xml(expiry = now)) }.message!!.contains("过期"))
        assertTrue(assertThrows(UserError::class.java) { parse(xml(jwt(expiry = now / 1000))) }.message!!.contains("过期"))
    }

    @Test fun rejectsMalformedJwtMissingAccountAndHeaderInjection() {
        for (token in listOf("not-a-jwt", jwt(null), jwt("bad\r\nheader"), "a.invalid-base64.c")) {
            assertThrows(UserError::class.java) { parse(xml(token)) }
        }
    }

    @Test fun rejectsDoctypeMalformedXmlDuplicateFieldsAndOversizedSources() {
        val doctype = """<!DOCTYPE map [<!ENTITY test SYSTEM "file:///must-not-read">]><map/>""".toByteArray()
        assertThrows(UserError::class.java) { parse(doctype) }
        assertThrows(UserError::class.java) { parse("<map>".toByteArray()) }
        assertThrows(UserError::class.java) {
            parse(xml(extra = """<string name="${prefix}com.auth0.access_token">duplicate</string>"""))
        }
        assertThrows(UserError::class.java) { parse(ByteArray(AUTH_MAX_BYTES + 1)) }
        assertThrows(UserError::class.java) { parse(xml(), ByteArray(AUTH_MAX_BYTES + 1)) }
    }

    @Test fun defaultDiscoveryPrefersChatGptAndRecognizesBothAndroidPathAliases() {
        assertTrue(RootCommands.discover.indexOf(CHATGPT_AUTH_PATH) < RootCommands.discover.indexOf("/files/home/.codex/auth.json"))
        assertEquals("/data/user/0/com.openai.chatgpt/files/datastore/global_session.json", ChatGptAuthParser.sessionPath(CHATGPT_AUTH_PATH))
        assertEquals("/data/data/com.openai.chatgpt/files/datastore/global_session.json", ChatGptAuthParser.sessionPath(CHATGPT_AUTH_PATH.replace("/data/user/0/", "/data/data/")))
        assertNull(ChatGptAuthParser.sessionPath("/test/auth.json"))
    }
}
