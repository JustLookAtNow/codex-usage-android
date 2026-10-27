package com.codexusage.android

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.security.MessageDigest
import java.util.UUID

// Serializes credential changes, root reads and HTTP calls across workers and the activity.
object RepositoryLock
// Short configuration/widget operations must never wait for a root prompt or network request.
object ConfigurationLock

// Workers need durable cache/backoff state before completion; check disk errors explicitly.
private fun SharedPreferences.Editor.save() {
    if (!commit()) throw UserError("无法保存查询结果，请检查设备存储空间")
}

class UsageRepository(
    private val context: Context,
    private val loadCredentials: () -> Credentials = { CredentialStore(context).load() },
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    private val prefs = context.getSharedPreferences("usage", Context.MODE_PRIVATE)
    val body: String? get() = prefs.getString("body", null)
    val updatedAt: Long get() = prefs.getLong("updated_at", 0)
    val error: String? get() = prefs.getString("error", null)
    val credits: Int? get() = prefs.getInt("credits", -1).takeIf { it >= 0 }
    val creditsError: String? get() = prefs.getString("credits_error", null)
    val creditsUpdatedAt: Long get() = prefs.getLong("credits_updated_at", 0)
    val resetPending: Boolean get() = prefs.contains("reset_request_id")
    val resetMessage: String? get() = prefs.getString("reset_message", null)
    fun resetCredits(): ResetCredits? = try {
        prefs.getString("credits_body", null)?.let(ResetCreditsParser::parse)
    } catch (_: Exception) { null }
    fun usage(): Usage? = try { body?.let { UsageParser.parse(it) } } catch (_: Exception) { null }

    fun clear() {
        if (!prefs.edit().clear().commit()) throw UserError("无法清除额度缓存，请重试")
    }

    fun refresh() = synchronized(RepositoryLock) {
        if (CredentialStore(context).mode == null) return@synchronized
        try {
            val credentials = loadCredentials()
            val usageBody = request("usage", credentials)
            // Persist only public display fields, never raw server responses.
            val cached = UsageParser.displayJson(UsageParser.parse(usageBody))
            prefs.edit().putString("body", cached).putLong("updated_at", System.currentTimeMillis())
                .remove("error").save()
            try {
                fetchCredits(credentials)
            } catch (error: UserError) {
                prefs.edit().putString("credits_error", error.message).save()
            } catch (_: Exception) {
                prefs.edit().putString("credits_error", "重置点响应无法解析，保留上次结果").save()
            }
        } catch (error: UserError) {
            prefs.edit().putString("error", error.message).save()
        } catch (_: Exception) {
            prefs.edit().putString("error", "查询失败，请检查网络或重新导入凭据").save()
        }
    }

    private fun fetchCredits(credentials: Credentials): ResetCredits {
        val value = ResetCreditsParser.parse(request("rate-limit-reset-credits", credentials))
        prefs.edit().putInt("credits", value.availableCount)
            .putString("credits_body", ResetCreditsParser.displayJson(value))
            .putLong("credits_updated_at", System.currentTimeMillis()).remove("credits_error").save()
        return value
    }

    // Invoked only by a confirmed foreground tap. Never run or retry this from a worker.
    fun consumeReset(creditId: String? = null): String = synchronized(RepositoryLock) {
        if (CredentialStore(context).mode == null) throw UserError("请先配置凭据")
        val credentials = loadCredentials()
        val account = MessageDigest.getInstance("SHA-256").digest(credentials.accountId.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val previousId = prefs.getString("reset_request_id", null)
        if (previousId != null && prefs.getString("reset_account", null) != account)
            throw UserError("当前账号与待确认的重置请求不同，请切回原账号重试，或重新配置凭据")
        val previousCredit = prefs.getString("reset_credit_id", null)
        if (previousId != null && creditId != null && creditId != previousCredit)
            throw UserError("请先确认上次重置请求的结果，再使用其他重置点")
        val selectedCredit = if (previousId != null) previousCredit else creditId
        if (previousId == null) {
            val current = fetchCredits(credentials)
            if (current.availableCount <= 0) throw UserError("当前没有可用重置点")
            if (selectedCredit != null && (selectedCredit.isBlank() || current.credits?.any {
                    it.id == selectedCredit && it.status == "available"
                } != true)) throw UserError("该重置点已不可用，请刷新详情")
        }
        val requestId = previousId ?: UUID.randomUUID().toString()
        // Save before sending so a timeout or process restart can safely reuse this attempt.
        prefs.edit().putString("reset_request_id", requestId).putString("reset_account", account)
            .putString("reset_credit_id", selectedCredit)
            .putString("reset_message", "正在确认重置结果…").save()
        val result = try {
            val response = JSONObject(request("rate-limit-reset-credits/consume", credentials,
                JSONObject().put("redeem_request_id", requestId).put("credit_id", selectedCredit).toString()))
            when (response.optString("code")) {
                "reset" -> "重置成功，已使用 1 个重置点"
                "already_redeemed" -> "本次重置已完成，已确认结果"
                "nothing_to_reset" -> "当前没有符合条件的额度需要重置，未使用重置点"
                "no_credit" -> "当前没有可用重置点，未执行重置"
                else -> throw UserError("服务返回了无法识别的重置结果")
            }
        } catch (error: Exception) {
            val detail = (error as? UserError)?.message ?: "重置响应无法解析"
            val message = "$detail\n重置结果未确认，请展开重置点详情并重试本次重置；重试会复用同一次请求"
            prefs.edit().putString("reset_message", message).save()
            throw UserError(message)
        }
        prefs.edit().remove("reset_request_id").remove("reset_account").remove("reset_credit_id").putString("reset_message", result)
            .remove("credits").remove("credits_body").remove("credits_updated_at").save()
        // The server owns which windows reset. Always fetch authoritative usage and credit data.
        refresh()
        result
    }

    private fun request(endpoint: String, credentials: Credentials, postBody: String? = null): String {
        val now = System.currentTimeMillis()
        val notBefore = prefs.getLong("retry_$endpoint", 0)
        if (notBefore > now) throw UserError("请求受限，约 ${(notBefore - now + 999) / 1000} 秒后重试")
        val connection = openConnection(URL("https://chatgpt.com/backend-api/wham/$endpoint"))
        try {
            connection.requestMethod = if (postBody == null) "GET" else "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            connection.setRequestProperty("Authorization", "Bearer ${credentials.accessToken}")
            connection.setRequestProperty("ChatGPT-Account-Id", credentials.accountId)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "codex-usage-android/0.1")
            connection.setRequestProperty("originator", "codex_cli_rs")
            if (postBody != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val bytes = postBody.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            when (val status = connection.responseCode) {
                200 -> return connection.inputStream.use { readBounded(it, RESPONSE_MAX_BYTES) }.toString(Charsets.UTF_8)
                401 -> throw UserError("凭据已失效：Root 模式请在来源应用更新登录，导入模式请重新导入")
                403 -> throw UserError("访问受限（403），请检查网络或账号权限")
                429 -> {
                    val delay = retrySeconds(connection.getHeaderField("Retry-After"), Instant.now())
                    prefs.edit().putLong("retry_$endpoint", System.currentTimeMillis() + delay * 1000).save()
                    throw UserError("请求受限，约 $delay 秒后重试")
                }
                in 300..399 -> throw UserError("服务地址发生跳转，已停止请求")
                else -> throw UserError("服务暂不可用（HTTP $status）")
            }
        } catch (error: UserError) { throw error }
        catch (_: java.net.SocketTimeoutException) { throw UserError("连接超时，请稍后刷新") }
        catch (_: Exception) { throw UserError("网络连接失败，请检查网络或手机 VPN") }
        finally { connection.disconnect() }
    }
}
