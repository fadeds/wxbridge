package io.wxbridge.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 兼容的 chat/completions 客户端。
 *
 * DeepSeek / 通义 / 智谱 / Moonshot / OpenAI / 本地 vLLM 都走这个协议，换 baseUrl + model 即可。
 * 视觉能力走同一套多模态 content 数组，只要模型支持 image_url 就能用。
 *
 * 不引 OkHttp：模块代码和 App 代码在同一个 dex 里，依赖越少越安全。
 */
class LlmClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String
) {
    class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** 纯文本 */
    suspend fun chat(
        system: String,
        user: String,
        maxTokens: Int = 1500,
        temperature: Double = 0.3
    ): String = request(buildMessages(system, user, null, null), maxTokens, temperature)

    /** 多模态：一张图 + 一段指令 */
    suspend fun chatWithImage(
        system: String,
        user: String,
        image: ByteArray,
        mime: String = "image/jpeg",
        maxTokens: Int = 700
    ): String = request(buildMessages(system, user, image, mime), maxTokens, 0.2)

    private fun buildMessages(
        system: String,
        user: String,
        image: ByteArray?,
        mime: String?
    ): JSONArray = JSONArray().apply {
        put(JSONObject().put("role", "system").put("content", system))
        val content: Any = if (image == null) {
            user
        } else {
            JSONArray().apply {
                put(JSONObject().put("type", "text").put("text", user))
                put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put("url", "data:${mime ?: "image/jpeg"};base64,${image.toBase64()}")
                    )
                )
            }
        }
        put(JSONObject().put("role", "user").put("content", content))
    }

    private suspend fun request(
        messages: JSONArray,
        maxTokens: Int,
        temperature: Double
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw LlmException("API Key 为空")
        if (model.isBlank()) throw LlmException("模型名为空")

        val endpoint = baseUrl.trimEnd('/').let {
            if (it.endsWith("/chat/completions")) it else "$it/chat/completions"
        }

        val body = JSONObject().apply {
            put("model", model)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            put("messages", messages)
        }.toString()

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()

            if (code !in 200..299) {
                throw LlmException("HTTP $code: ${text.take(400)}")
            }

            val json = JSONObject(text)
            json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.takeIf { it.isNotBlank() }
                ?: throw LlmException("返回体里没有 content: ${text.take(300)}")
        } catch (e: LlmException) {
            throw e
        } catch (t: Throwable) {
            throw LlmException("请求失败: ${t.javaClass.simpleName} ${t.message}", t)
        } finally {
            conn.disconnect()
        }
    }
}

private fun ByteArray.toBase64(): String =
    android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)
