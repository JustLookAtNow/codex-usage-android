package com.codexusage.android

import android.annotation.SuppressLint
import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.util.Base64

@SuppressLint("SdCardPath") // Root intentionally reads ChatGPT's files, not this app's private directory.
const val CHATGPT_AUTH_PATH = "/data/user/0/com.openai.chatgpt/shared_prefs/com.auth0.authentication.storage.xml"
private const val CHATGPT_AUTH_SUFFIX = "/shared_prefs/com.auth0.authentication.storage.xml"

object ChatGptAuthParser {
    fun sessionPath(authPath: String): String? = authPath.takeIf { it.endsWith(CHATGPT_AUTH_SUFFIX) }
        ?.removeSuffix(CHATGPT_AUTH_SUFFIX)?.plus("/files/datastore/global_session.json")

    fun parse(authBytes: ByteArray, sessionBytes: ByteArray, nowMillis: Long = System.currentTimeMillis()): Credentials {
        if (authBytes.size > AUTH_MAX_BYTES || sessionBytes.size > AUTH_MAX_BYTES) throw UserError("凭据文件过大")
        try {
            val session = JSONObject(sessionBytes.toString(Charsets.UTF_8)).getJSONObject("d")
            if (session.optBoolean("is_anonymous", true)) throw UserError("请先在 ChatGPT 应用中登录")
            val sessionId = session.getString("auth_session_id")
            val userId = session.getString("user_id")
            if (sessionId.isBlank() || userId.isBlank()) throw UserError("ChatGPT 当前会话无效，请重新登录")
            val prefix = "sessions.${sessionId.length}:$sessionId."
            val entries = readEntries(authBytes)
            if (entries[prefix + "user_id"] != userId) throw UserError("ChatGPT 会话已变化，请重新刷新")
            val expiresAt = entries[prefix + "com.auth0.expires_at"]?.toLongOrNull()
                ?: throw UserError("ChatGPT 凭据缺少到期时间，请打开 ChatGPT 更新登录")
            if (expiresAt <= nowMillis) throw UserError("ChatGPT 凭据已过期，请打开 ChatGPT 更新登录后刷新")
            val accessToken = entries[prefix + "com.auth0.access_token"]
                ?: throw UserError("ChatGPT 当前会话没有 access token，请打开 ChatGPT 更新登录")
            val parts = accessToken.split('.')
            if (parts.size != 3) throw UserError("ChatGPT access token 格式无法识别")
            // Decode only to identify the token's account; the server validates the JWT.
            val payload = JSONObject(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8))
            val accountId = payload.getJSONObject("https://api.openai.com/auth").opt("chatgpt_account_id") as? String
                ?: throw UserError("ChatGPT access token 缺少有效账号")
            val tokenExpiry = payload.getLong("exp")
            if (tokenExpiry <= nowMillis / 1000) throw UserError("ChatGPT 凭据已过期，请打开 ChatGPT 更新登录后刷新")
            // Reuse header validation; never return refresh tokens, ID tokens or key aliases.
            val normalized = JSONObject().put("tokens", JSONObject()
                .put("access_token", accessToken).put("account_id", accountId))
            return AuthParser.parse(normalized.toString().toByteArray(Charsets.UTF_8))
        } catch (error: UserError) { throw error }
        catch (_: Exception) { throw UserError("无法解析 ChatGPT 当前登录，请打开 ChatGPT 更新登录或改用 auth.json") }
    }

    private fun readEntries(bytes: ByteArray): Map<String, String> {
        val parser = Xml.newPullParser().apply { setInput(bytes.inputStream(), "UTF-8") }
        val entries = mutableMapOf<String, String>()
        var mapFound = false
        while (true) {
            when (parser.nextToken()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.DOCDECL, XmlPullParser.ENTITY_REF -> throw UserError("认证 XML 含有不支持的声明或实体")
                XmlPullParser.START_TAG -> when {
                    parser.depth == 1 -> {
                        if (parser.name != "map") throw UserError("请选择 ChatGPT 的认证存储 XML")
                        mapFound = true
                    }
                    parser.depth == 2 && parser.name in setOf("string", "long") -> {
                        val name = parser.getAttributeValue(null, "name") ?: continue
                        val value = if (parser.name == "string") parser.nextText()
                            else parser.getAttributeValue(null, "value") ?: continue
                        if (entries.put(name, value) != null) throw UserError("认证 XML 存在重复字段")
                    }
                }
            }
        }
        if (!mapFound) throw UserError("请选择 ChatGPT 的认证存储 XML")
        return entries
    }
}
