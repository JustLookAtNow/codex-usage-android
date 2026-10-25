package com.codexusage.android

import org.json.JSONObject
import java.io.InputStream
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

const val AUTH_MAX_BYTES = 256 * 1024
const val RESPONSE_MAX_BYTES = 1024 * 1024

class UserError(message: String) : Exception(message)

// Deliberately no data-class toString(): credentials must never appear in logs.
class Credentials(val accessToken: String, val accountId: String) {
    // Display metadata only; the server still validates the token.
    val expiresAt: Long? get() = try {
        val parts = accessToken.split('.')
        if (parts.size != 3) null else {
            val payload = JSONObject(String(java.util.Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8))
            (payload.opt("exp") as? Number)?.toDouble()
                ?.takeIf { it.isFinite() && it in 0.0..253402300799.0 }?.toLong()
        }
    } catch (_: Exception) { null }

    override fun toString() = "Credentials([redacted])"
}

fun readBounded(input: InputStream, limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count == -1) break
        if (output.size() + count > limit) throw UserError("文件或响应过大")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

object AuthParser {
    fun parse(bytes: ByteArray): Credentials {
        if (bytes.size > AUTH_MAX_BYTES) throw UserError("凭据文件过大")
        val tokens = try {
            JSONObject(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")).optJSONObject("tokens")
        } catch (_: Exception) { null } ?: throw UserError("请选择 Codex 的 auth.json（ChatGPT 账号登录）")
        fun token(name: String): String {
            val value = tokens.opt(name) as? String
            if (value.isNullOrBlank() || value.any { it.code < 32 || it.code > 126 })
                throw UserError("凭据缺少有效的 access_token 或 account_id")
            return value
        }
        return Credentials(token("access_token"), token("account_id"))
    }
}

data class UsageWindow(val label: String, val remaining: Double?, val resetAt: Long?, val seconds: Long?, val additional: Boolean)
data class Usage(val plan: String?, val windows: List<UsageWindow>)

object UsageParser {
    fun displayJson(usage: Usage): String {
        val root = JSONObject().put("plan_type", usage.plan)
        val main = JSONObject()
        val extra = org.json.JSONArray()
        for (window in usage.windows) {
            val item = JSONObject().put("limit_window_seconds", window.seconds).put("reset_at", window.resetAt)
                .put("used_percent", window.remaining?.let { 100.0 - it })
            if (window.additional) {
                extra.put(JSONObject().put("limit_name", window.label.substringBeforeLast(" · "))
                    .put("rate_limit", JSONObject().put(if (window.label.endsWith(" · 次周期")) "secondary_window" else "primary_window", item)))
            } else {
                main.put(if (window.label == "次周期" || main.has("primary_window")) "secondary_window" else "primary_window", item)
            }
        }
        return root.put("rate_limit", main).put("additional_rate_limits", extra).toString()
    }

    fun parse(body: String): Usage {
        val root = JSONObject(body)
        val windows = mutableListOf<UsageWindow>()
        fun append(limits: JSONObject?, prefix: String = "") {
            for ((key, fallback) in listOf("primary_window" to "主周期", "secondary_window" to "次周期")) {
                val item = limits?.optJSONObject(key) ?: continue
                val seconds = finite(item.opt("limit_window_seconds"))?.takeIf { it > 0 && it < Long.MAX_VALUE.toDouble() }?.toLong()
                val label = when (seconds) { 18000L -> "5h"; 604800L -> "Weekly"; else -> seconds?.let { "${it / 3600.0}h" } ?: fallback }
                val used = finite(item.opt("used_percent"))
                windows += UsageWindow(prefix + label, used?.let { (100.0 - it).coerceIn(0.0, 100.0) }, epoch(item.opt("reset_at")), seconds, prefix.isNotEmpty())
            }
        }
        append(root.optJSONObject("rate_limit"))
        val additional = root.optJSONArray("additional_rate_limits")
        if (additional != null) for (i in 0 until additional.length()) {
            val item = additional.optJSONObject(i) ?: continue
            val name = (item.opt("limit_name") as? String) ?: (item.opt("normal_model_slug") as? String) ?: "附加额度"
            append(item.optJSONObject("rate_limit"), name.take(48) + " · ")
        }
        if (windows.isEmpty()) throw UserError("接口未提供可识别的用量周期")
        return Usage(root.opt("plan_type") as? String, windows)
    }

    private fun finite(value: Any?): Double? = (value as? Number)?.toDouble()?.takeIf { it.isFinite() }
    internal fun epoch(value: Any?): Long? = try {
        when (value) {
            is Number -> finite(value)?.takeIf { it >= 0 && it <= 253402300799.0 }?.toLong()
            is String -> Instant.parse(value).epochSecond.takeIf { it >= 0 && it <= 253402300799L }
            else -> null
        }
    } catch (_: Exception) { null }
}

data class ResetCredit(
    val id: String?, val resetType: String?, val status: String?,
    val grantedAt: Long?, val expiresAt: Long?, val title: String?, val description: String?
)

data class ResetCredits(val availableCount: Int, val totalEarnedCount: Int?, val credits: List<ResetCredit>?)

object ResetCreditsParser {
    fun parse(body: String): ResetCredits {
        val root = JSONObject(body)
        val count = count(root.opt("available_count")) ?: throw UserError("接口未提供重置点数量，保留上次结果")
        val rows = root.optJSONArray("credits")
        val credits = rows?.let {
            (0 until it.length()).mapNotNull { index ->
                val row = it.optJSONObject(index) ?: return@mapNotNull null
                fun string(key: String) = (row.opt(key) as? String)?.takeIf(String::isNotBlank)
                ResetCredit(string("id"), string("reset_type"), string("status"),
                    UsageParser.epoch(row.opt("granted_at")), UsageParser.epoch(row.opt("expires_at")),
                    string("title")?.take(200), string("description")?.take(2000))
            }
        }
        return ResetCredits(count, count(root.opt("total_earned_count")), credits)
    }

    // Cache allowlisted display fields only, never the original server response.
    fun displayJson(value: ResetCredits): String {
        val root = JSONObject().put("available_count", value.availableCount).put("total_earned_count", value.totalEarnedCount)
        value.credits?.let { rows ->
            root.put("credits", org.json.JSONArray().apply {
                rows.forEach { row -> put(JSONObject().put("id", row.id).put("reset_type", row.resetType)
                    .put("status", row.status).put("granted_at", row.grantedAt).put("expires_at", row.expiresAt)
                    .put("title", row.title).put("description", row.description)) }
            })
        }
        return root.toString()
    }

    private fun count(value: Any?): Int? = (value as? Number)?.toDouble()
        ?.takeIf { it.isFinite() && it >= 0 && it <= Int.MAX_VALUE && it % 1.0 == 0.0 }?.toInt()
}

fun retrySeconds(header: String?, now: Instant = Instant.now()): Long {
    header?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let {
        return kotlin.math.ceil(it).coerceIn(1.0, 315360000.0).toLong()
    }
    return try {
        (ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() - now.epochSecond).coerceIn(1, 315360000)
    } catch (_: Exception) { 60 }
}

object RootCommands {
    fun quotePath(path: String): String {
        if (!path.startsWith('/') || path.any { it.code < 32 } || path.length > 1024)
            throw UserError("请填写绝对文件路径，不能包含换行或控制字符")
        return "'" + path.replace("'", "'\"'\"'") + "'"
    }

    // Prefer ChatGPT's known auth store, then conventional Codex homes. No recursive scan.
    val discover = """
        [ "${'$'}(id -u)" = 0 ] || exit 13
        printf 'ROOT_OK\n'
        for p in $CHATGPT_AUTH_PATH /data/data/*/files/home/.codex/auth.json /data/user/0/*/files/home/.codex/auth.json /data/data/*/files/.codex/auth.json /data/local/tmp/.codex/auth.json; do
          [ -f "${'$'}p" ] && printf '%s\n' "${'$'}p"
        done
        exit 0
    """.trimIndent()

    fun read(path: String): String {
        val quoted = quotePath(path)
        return "[ \"${'$'}(id -u)\" = 0 ] || exit 13; [ -f $quoted ] || exit 14; head -c ${AUTH_MAX_BYTES + 1} $quoted"
    }
}
