package com.codexusage.android

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialStore(context: Context, private val providedKey: (() -> SecretKey)? = null) {
    private val prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)
    val mode: String? get() = prefs.getString("mode", null)
    val rootPath: String? get() = prefs.getString("path", null)
    fun description(): String = when (mode) {
        "root" -> "Root 自动读取\n${rootPath.orEmpty()}"
        "import" -> "已手工导入 · 加密保存在本机"
        else -> "尚未配置凭据"
    }

    private fun key(): SecretKey {
        providedKey?.let { return it() }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("codex_usage_credentials_v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("codex_usage_credentials_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun import(bytes: ByteArray) {
        val credentials = AuthParser.parse(bytes)
        // Store only the two required fields, never a refresh token or the whole source file.
        val normalized = JSONObject().put("tokens", JSONObject().put("access_token", credentials.accessToken).put("account_id", credentials.accountId)).toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        if (!prefs.edit().clear().putString("mode", "import").putString("iv", encode(cipher.iv)).putString("data", encode(data)).commit())
            throw UserError("无法保存凭据，请重试")
    }

    fun selectRoot(path: String) {
        RootAccess.credentials(path)
        if (!prefs.edit().clear().putString("mode", "root").putString("path", path).commit())
            throw UserError("无法保存凭据路径，请重试")
    }

    fun load(): Credentials = when (mode) {
        "root" -> RootAccess.credentials(rootPath ?: throw UserError("请重新选择 Root 凭据"))
        "import" -> try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, decode(prefs.getString("iv", "")!!)))
            AuthParser.parse(cipher.doFinal(decode(prefs.getString("data", "")!!)))
        } catch (_: Exception) { throw UserError("无法解密凭据，请重新导入") }
        else -> throw UserError("请先通过 Root 获取或手工导入 auth.json")
    }

    fun clear() {
        if (!prefs.edit().clear().commit()) throw UserError("无法清除凭据，请重试")
        // Removing the ciphertext is sufficient even if a device's Keystore is unavailable.
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("codex_usage_credentials_v1") }
    }
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)
}

object RootAccess {
    internal fun execute(
        command: String,
        timeoutMillis: Long = 25000,
        // Android app data isolation can hide other apps' files in our mount namespace.
        // Magisk/KernelSU -M performs the read in the global mount namespace.
        start: (String) -> Process = { ProcessBuilder("su", "-M", "-c", it).redirectError(File("/dev/null")).start() }
    ): ByteArray {
        val process = try {
            start(command)
        } catch (_: Exception) { throw UserError("无法调用 su，请确认手机已 Root 并安装权限管理器") }
        val reader = Executors.newSingleThreadExecutor { task ->
            Thread(task, "codex-root-reader").apply { isDaemon = true }
        }
        val future = reader.submit<ByteArray> { process.inputStream.use { readBounded(it, AUTH_MAX_BYTES) } }
        try {
            val result = future.get(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0)
                throw UserError("Root 未授权，或文件不可读；请检查权限与路径")
            return result
        } catch (error: UserError) { throw error }
        catch (_: Exception) { throw UserError("Root 读取失败或超时，请检查授权和文件大小") }
        finally {
            process.destroyForcibly()
            future.cancel(true)
            reader.shutdownNow()
            // The reader owns and closes stdout. Closing it here can block on a child
            // inheriting the pipe after su is killed, defeating the authorization timeout.
        }
    }

    @SuppressLint("SdCardPath") // Root paths intentionally refer to other apps' private directories.
    fun discover(): List<String> {
        val lines = execute(RootCommands.discover).toString(Charsets.UTF_8).lines()
        if (lines.firstOrNull() != "ROOT_OK") throw UserError("没有获得 Root 权限")
        // /data/data and /data/user/0 generally refer to the same directory.
        return lines.drop(1).filter { it.startsWith('/') && (it.endsWith("/auth.json") || ChatGptAuthParser.sessionPath(it) != null) }
            .map { it.replaceFirst(Regex("^/data/data/"), "/data/user/0/") }.distinct().take(32)
    }

    fun read(path: String): ByteArray = execute(RootCommands.read(path))

    fun credentials(path: String): Credentials {
        val sessionPath = ChatGptAuthParser.sessionPath(path) ?: return AuthParser.parse(read(path))
        val session = read(sessionPath)
        val auth = read(path)
        // Avoid mixing login state if ChatGPT switches accounts while we read it.
        if (!session.contentEquals(read(sessionPath))) throw UserError("ChatGPT 会话已变化，请重新刷新")
        return ChatGptAuthParser.parse(auth, session)
    }
}
