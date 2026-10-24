package com.codexusage.android

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

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
                val rawCount = JSONObject(request("rate-limit-reset-credits", credentials)).opt("available_count")
                val number = (rawCount as? Number)?.toDouble()
                val count = number?.takeIf { it.isFinite() && it >= 0 && it <= Int.MAX_VALUE && it % 1.0 == 0.0 }?.toInt()
                if (count == null) throw UserError("接口未提供重置点数量，保留上次结果")
                prefs.edit().putInt("credits", count).remove("credits_error").save()
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

    private fun request(endpoint: String, credentials: Credentials): String {
        val now = System.currentTimeMillis()
        val notBefore = prefs.getLong("retry_$endpoint", 0)
        if (notBefore > now) throw UserError("请求受限，约 ${(notBefore - now + 999) / 1000} 秒后重试")
        val connection = openConnection(URL("https://chatgpt.com/backend-api/wham/$endpoint"))
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.setRequestProperty("Authorization", "Bearer ${credentials.accessToken}")
            connection.setRequestProperty("ChatGPT-Account-Id", credentials.accountId)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "codex-usage-android/0.1")
            connection.setRequestProperty("originator", "codex_cli_rs")
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
