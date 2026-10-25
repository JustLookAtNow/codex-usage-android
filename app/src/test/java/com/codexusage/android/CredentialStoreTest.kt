package com.codexusage.android

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Base64
import java.util.TimeZone
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CredentialStoreTest {
    private lateinit var context: Context
    // Only key acquisition is replaced; encryption, preferences and decryption are real.
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val source = """{"tokens":{"access_token":"test-access-token","account_id":"test-account-id","refresh_token":"must-not-persist","id_token":"also-private"},"last_refresh":"unused"}""".toByteArray()

    @Before fun reset() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun encryptedImportCanBeLoadedByAnotherStoreWithoutPersistingSourceFields() {
        CredentialStore(context) { key }.import(source)
        val prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)
        assertEquals(setOf("mode", "iv", "data"), prefs.all.keys)
        val saved = prefs.all.values.joinToString()
        for (secret in listOf("test-access-token", "test-account-id", "must-not-persist", "also-private", "refresh_token", "id_token")) {
            assertFalse(saved.contains(secret))
        }
        val loaded = CredentialStore(context) { key }.load()
        assertEquals("test-access-token", loaded.accessToken)
        assertEquals("test-account-id", loaded.accountId)
    }

    @Test fun eachImportUsesANewIvAndWrongKeyOrTamperedCiphertextCannotDecrypt() {
        val store = CredentialStore(context) { key }
        val prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)
        store.import(source)
        val firstIv = prefs.getString("iv", null)
        store.import(source)
        assertNotEquals(firstIv, prefs.getString("iv", null))
        val wrongKey = SecretKeySpec(ByteArray(32) { 42 }, "AES")
        assertThrows(UserError::class.java) { CredentialStore(context) { wrongKey }.load() }
        prefs.edit().putString("data", "AAAA").commit()
        val error = assertThrows(UserError::class.java) { store.load() }
        assertEquals("无法解密凭据，请重新导入", error.message)
        store.clear()
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun existingEncryptedImportShowsExpiryInTheCurrentLocalTimezone() {
        val expiry = Instant.parse("2026-10-31T12:35:32Z").epochSecond
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"exp":$expiry}""".toByteArray())
        CredentialStore(context) { key }.import("""{"tokens":{
            "access_token":"e30.$payload.signature","account_id":"test-account"}}""".toByteArray())
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val restored = CredentialStore(context) { key }
            assertEquals(expiry, restored.load().expiresAt)
            assertEquals("已手工导入 · 加密保存在本机\n过期时间：2026-10-31 20:35:32", restored.description())
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertTrue(restored.description().endsWith("2026-10-31 12:35:32"))
            assertEquals(setOf("mode", "iv", "data"),
                context.getSharedPreferences("credentials", Context.MODE_PRIVATE).all.keys)
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    @Test fun missingOrMalformedExpiryDoesNotPreventImportOrDescription() {
        val payloads = listOf("{}", """{"exp":null}""", """{"exp":"invalid"}""",
            """{"exp":-1}""", """{"exp":1e100}""", "not json")
        val tokens = listOf("opaque-token", "e30.!.signature") + payloads.map {
            "e30.${Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray())}.signature"
        }
        for (token in tokens) {
            val store = CredentialStore(context) { key }
            store.import("""{"tokens":{"access_token":"$token","account_id":"test-account"}}""".toByteArray())
            assertEquals(token, store.load().accessToken)
            assertNull(store.load().expiresAt)
            assertEquals("已手工导入 · 加密保存在本机\n过期时间：未知", store.description())
        }
    }
}
