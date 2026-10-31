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

enum class ResetResult(val message: String, val succeeded: Boolean) {
    RESET("重置成功，已使用 1 个重置点", true),
    ALREADY_REDEEMED("本次重置已完成，已确认结果", true),
    NOTHING_TO_RESET("当前没有符合条件的额度需要重置，未使用重置点", false),
    NO_CREDIT("当前没有可用重置点，未执行重置", false)
}

internal data class ResetCelebration(val requestId: String, val before: Usage?)

class UsageRepository(
    private val context: Context,
    private val loadCredentials: () -> Credentials = { CredentialStore(context).load() },
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val notifyWeeklyReset: () -> Unit = { WeeklyResetNotifications(context).notifyReset() },
    private val notifyNewResetCredits: (Int, Int) -> Unit = { added, available ->
        ResetCreditNotifications(context).notifyAdded(added, available)
    },
    private val notifyExpiringResetCredits: (List<ResetCredit>, Long) -> Unit = { credits, now ->
        ResetCreditExpiryNotifications(context).notifyExpiring(credits, now)
    },
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val prefs = context.getSharedPreferences("usage", Context.MODE_PRIVATE)
    val body: String? get() = prefs.getString("body", null)
    val updatedAt: Long get() = prefs.getLong("updated_at", 0)
    val error: String? get() = prefs.getString("error", null)
    val credits: Int? get() = prefs.getInt("credits", -1).takeIf { it >= 0 }
    val creditsError: String? get() = prefs.getString("credits_error", null)
    val creditsUpdatedAt: Long get() = prefs.getLong("credits_updated_at", 0)
    val resetPending: Boolean get() = prefs.contains("reset_request_id")
    internal val pendingResetRequestId: String? get() = prefs.getString("reset_request_id", null)
    internal val pendingResetCreditMissing: Boolean get() {
        val selected = prefs.getString("reset_credit_id", null) ?: return false
        val rows = resetCredits()?.credits ?: return false
        return resetPending && creditsUpdatedAt > 0 && creditsError == null && rows.none { it.id == selected }
    }
    val resetMessage: String? get() = prefs.getString("reset_message", null)
    fun resetCredits(): ResetCredits? = try {
        prefs.getString("credits_body", null)?.let(ResetCreditsParser::parse)
    } catch (_: Exception) { null }
    fun usage(): Usage? = try { body?.let { UsageParser.parse(it) } } catch (_: Exception) { null }

    // Presentation override only: notifications and comparisons continue to use usage().
    fun displayUsage(): Usage? = usage()?.let { value ->
        if (prefs.getBoolean("reset_display_full", false))
            value.copy(windows = value.windows.map { it.copy(remaining = 100.0) }) else value
    }

    internal fun claimResetCelebration(): ResetCelebration? = synchronized(ConfigurationLock) {
        val id = prefs.getString("reset_success_id", null) ?: return@synchronized null
        val effects = context.getSharedPreferences("reset_effects", Context.MODE_PRIVATE)
        if (effects.getString("played_id", null) == id) return@synchronized null
        val before = try {
            prefs.getString("reset_success_before", null)?.let(UsageParser::parse)
        } catch (_: Exception) { null }
        effects.edit().putString("played_id", id).save()
        ResetCelebration(id, before)
    }

    fun clear() {
        if (!prefs.edit().clear().commit()) throw UserError("无法清除额度缓存，请重试")
        context.getSharedPreferences("reset_effects", Context.MODE_PRIVATE).edit().clear().save()
    }

    // Explicit user acknowledgement of a historical success, never a new redemption.
    // Do not replay the old success animation or overwrite today's quota with 100%.
    internal fun acknowledgeCompletedReset(requestId: String): String = synchronized(RepositoryLock) {
        if (pendingResetRequestId != requestId) throw UserError("待确认记录已更新，请刷新后查看")
        val message = "已确认上次重置完成"
        prefs.edit().putString("reset_acknowledged_request_id", requestId)
            .putString("reset_acknowledged_credit_id", prefs.getString("reset_credit_id", null))
            .remove("reset_request_id").remove("reset_account").remove("reset_credit_id")
            .putString("reset_message", message).save()
        message
    }

    fun refresh() = synchronized(RepositoryLock) { refreshInternal() }

    private fun refreshInternal(preserveResetDisplay: Boolean = false) {
        if (CredentialStore(context).mode == null) return
        val credentials = try { loadCredentials() } catch (error: Exception) {
            prefs.edit().putString("error", (error as? UserError)?.message
                ?: "查询失败，请检查网络或重新导入凭据").save()
            return
        }
        val before = displayUsage()
        try {
            val current = UsageParser.parse(request("usage", credentials))
            val previous = usage()
            val editor = prefs.edit().putString("body", UsageParser.displayJson(current))
                .putLong("updated_at", nowMillis()).remove("error")
            if (!preserveResetDisplay) editor.remove("reset_display_full")
            editor.save()
            if (shouldNotifyWeeklyReset(previous, current)) {
                try { notifyWeeklyReset() } catch (_: Exception) { }
            }
        } catch (error: Exception) {
            prefs.edit().putString("error", (error as? UserError)?.message
                ?: "查询失败，请检查网络或重新导入凭据").save()
        }
        // Credit reconciliation must still work when the usage endpoint fails.
        try {
            val current = fetchCredits(credentials)
            val requestId = prefs.getString("reset_request_id", null)
            val creditId = prefs.getString("reset_credit_id", null)
            val rows = current.credits
            val selected = rows?.firstOrNull { it.id == creditId }
            // This endpoint can omit spent credits entirely, rather than returning a consumed row.
            // Only a fresh, explicit list with usable IDs can establish that the old ID disappeared.
            val noLongerListed = rows != null && rows.all { !it.id.isNullOrBlank() } && selected == null
            if (requestId != null && creditId != null &&
                prefs.getString("reset_account", null) == accountHash(credentials) &&
                (selected?.status in setOf("redeemed", "consumed") || noLongerListed)) {
                completeReset(ResetResult.ALREADY_REDEEMED, requestId, before)
            }
        } catch (error: Exception) {
            prefs.edit().putString("credits_error", (error as? UserError)?.message
                ?: "重置点响应无法解析，保留上次结果").save()
        }
    }

    private fun accountHash(credentials: Credentials): String =
        MessageDigest.getInstance("SHA-256").digest(credentials.accountId.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun completeReset(result: ResetResult, requestId: String, before: Usage?, clearCredits: Boolean = false) {
        val editor = prefs.edit().remove("reset_request_id").remove("reset_account")
            .remove("reset_credit_id").putString("reset_message", result.message)
        if (clearCredits) editor.remove("credits").remove("credits_body").remove("credits_updated_at")
        if (result.succeeded && prefs.getString("reset_success_id", null) != requestId) {
            editor.putString("reset_success_id", requestId)
                .putString("reset_success_before", before?.let(UsageParser::displayJson))
                .putBoolean("reset_display_full", true)
        }
        editor.save()
    }

    private fun fetchCredits(credentials: Credentials): ResetCredits {
        val value = ResetCreditsParser.parse(request("rate-limit-reset-credits", credentials))
        val previous = if (prefs.contains("credits_notification_baseline"))
            prefs.getInt("credits_notification_baseline", 0) else credits
        val now = nowMillis()
        val stages = JSONObject(prefs.getString("credits_expiry_stages", "{}")!!)
        val expiring = if (value.availableCount <= 0) emptyList() else value.credits.orEmpty().filter { credit ->
            val level = credit.expiryLevel(now / 1000)
            val key = credit.expiryNotificationKey()
            if (level.ordinal > stages.optInt(key, 0)) {
                // CRITICAL also covers WARNING, including first discovery inside 24 hours.
                stages.put(key, level.ordinal)
                true
            } else false
        }
        prefs.edit().putInt("credits", value.availableCount)
            .putInt("credits_notification_baseline", value.availableCount)
            .putString("credits_body", ResetCreditsParser.displayJson(value))
            .putString("credits_expiry_stages", stages.toString())
            .putLong("credits_updated_at", now).remove("credits_error").save()
        if (previous != null && value.availableCount > previous) {
            // Advance the baseline even if delivery is disabled or fails; never replay alerts.
            try { notifyNewResetCredits(value.availableCount - previous, value.availableCount) }
            catch (_: Exception) { }
        }
        if (expiring.isNotEmpty()) {
            try { notifyExpiringResetCredits(expiring, now / 1000) }
            catch (_: Exception) { }
        }
        return value
    }

    // Invoked only by a confirmed foreground tap. Never run or retry this from a worker.
    fun consumeReset(creditId: String? = null): ResetResult = synchronized(RepositoryLock) {
        if (CredentialStore(context).mode == null) throw UserError("请先配置凭据")
        val credentials = loadCredentials()
        val account = accountHash(credentials)
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
                "reset" -> ResetResult.RESET
                "already_redeemed" -> ResetResult.ALREADY_REDEEMED
                "nothing_to_reset" -> ResetResult.NOTHING_TO_RESET
                "no_credit" -> ResetResult.NO_CREDIT
                else -> throw UserError("服务返回了无法识别的重置结果")
            }
        } catch (error: Exception) {
            val detail = (error as? UserError)?.message ?: "重置响应无法解析"
            val message = "$detail\n重置结果未确认，请展开重置点详情并重试本次重置；重试会复用同一次请求"
            prefs.edit().putString("reset_message", message).save()
            throw UserError(message)
        }
        completeReset(result, requestId, displayUsage(), clearCredits = true)
        // Refresh authoritative data without replacing this reset's 100% presentation.
        refreshInternal(preserveResetDisplay = result.succeeded)
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
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
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
