package com.codexusage.android

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
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
}
