package app.silly.core.provider

import app.silly.core.prompt.model.PromptMessage
import app.silly.core.prompt.model.PromptRole
import app.silly.core.provider.stream.AnthropicStreamInterpreter
import app.silly.core.provider.stream.GeminiStreamInterpreter
import app.silly.core.provider.stream.OpenAiStreamInterpreter
import app.silly.core.provider.stream.StreamInterpreter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 采样参数。三家能接受的东西不完全一样，[ChatProvider] 各自取舍。 */
data class GenerationOptions(
    val model: String,
    val maxTokens: Int = 1024,
    val temperature: Double? = null,
    val topP: Double? = null,
    val stop: List<String> = emptyList(),
    val stream: Boolean = true,
    /** 部分端点（如 OpenAI 的 o 系列、GPT-5）不接受 `max_tokens`，改用 `max_completion_tokens`。 */
    val useMaxCompletionTokens: Boolean = false,
)

/**
 * 一次 HTTP 请求的描述。
 *
 * 刻意**不含 HTTP 客户端** —— 这一层只负责「把消息翻译成请求体」，
 * 发不发、怎么发交给 App 层（Android 上用 OkHttp）。好处是整个翻译过程
 * 可以纯数据测试，也能在单元测试里断言请求体。
 */
data class ProviderRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
)

/**
 * 一家模型服务。
 *
 * 三家的共同点是「都收消息、都吐文本」，差别全在细节里：
 *
 * | | system 怎么放 | 角色名 | 鉴权头 |
 * |---|---|---|---|
 * | OpenAI 兼容 | 当作一条 `system` 消息 | `system` / `user` / `assistant` | `Authorization: Bearer` |
 * | Anthropic | **顶层 `system` 字段**，消息数组里不能有 | `user` / `assistant` | `x-api-key` |
 * | Gemini | **顶层 `systemInstruction`** | `user` / **`model`** | URL 查询参数 `key` |
 */
interface ChatProvider {
    val id: String

    fun buildRequest(messages: List<PromptMessage>, options: GenerationOptions): ProviderRequest

    fun createInterpreter(): StreamInterpreter

    /** 非流式响应里取正文。 */
    fun extractContent(response: JsonObject): String
}

internal val providerJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

/** 把 `PromptMessage` 的 role 翻译成目标 API 的字符串。 */
internal fun PromptRole.wireName(system: String = "system"): String = when (this) {
    PromptRole.SYSTEM -> system
    PromptRole.USER -> "user"
    PromptRole.ASSISTANT -> "assistant"
}

/**
 * OpenAI 兼容接口。
 *
 * 覆盖官方 OpenAI、DeepSeek、xAI、OpenRouter、以及各种本地/中转端点。
 * 只依赖 `/chat/completions` 这个事实标准。
 *
 * @param baseUrl 例：`https://api.openai.com/v1`
 */
class OpenAiCompatibleProvider(
    private val baseUrl: String,
    private val apiKey: String,
    override val id: String = "openai",
    private val extraHeaders: Map<String, String> = emptyMap(),
    /** OpenRouter 需要这两个头才愿意把应用信息透传。 */
    private val referer: String? = null,
    private val title: String? = null,
) : ChatProvider {

    override fun buildRequest(messages: List<PromptMessage>, options: GenerationOptions): ProviderRequest {
        val body = buildJsonObject {
            put("model", options.model)
            put("stream", options.stream)
            put(
                "messages",
                buildJsonArray {
                    for (message in messages) {
                        add(
                            buildJsonObject {
                                put("role", message.role.wireName())
                                put("content", message.content)
                                // OpenAI 允许给非 system 消息附 name，某些中转也认
                                message.name?.takeIf { it.isNotEmpty() }?.let { put("name", it) }
                            },
                        )
                    }
                },
            )

            if (options.useMaxCompletionTokens) {
                put("max_completion_tokens", options.maxTokens)
            } else {
                put("max_tokens", options.maxTokens)
            }
            options.temperature?.let { put("temperature", it) }
            options.topP?.let { put("top_p", it) }
            if (options.stop.isNotEmpty()) {
                put("stop", buildJsonArray { options.stop.forEach { add(JsonPrimitive(it)) } })
            }
        }

        val headers = buildMap {
            put("Content-Type", "application/json")
            if (apiKey.isNotEmpty()) put("Authorization", "Bearer $apiKey")
            referer?.let { put("HTTP-Referer", it) }
            title?.let { put("X-Title", it) }
            putAll(extraHeaders)
        }

        return ProviderRequest(
            url = "${baseUrl.trimEnd('/')}/chat/completions",
            headers = headers,
            body = providerJson.encodeToString(JsonObject.serializer(), body),
        )
    }

    override fun createInterpreter(): StreamInterpreter = OpenAiStreamInterpreter()

    override fun extractContent(response: JsonObject): String =
        response["choices"]?.let { it as? JsonArray }?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject?.get("content")
            ?.jsonPrimitive?.content
            ?: ""
}

/**
 * Anthropic Messages API。
 *
 * 两处与 OpenAI 家的硬性差别，写错会直接 400：
 *  1. **system 不是消息**，必须放到顶层 `system` 字段；
 *  2. `messages` 里**只能是 user / assistant 交替**，连续的同一角色要合并。
 *
 * @param baseUrl 例：`https://api.anthropic.com`
 */
class AnthropicProvider(
    private val baseUrl: String,
    private val apiKey: String,
    override val id: String = "anthropic",
    private val apiVersion: String = "2023-06-01",
    private val extraHeaders: Map<String, String> = emptyMap(),
) : ChatProvider {

    override fun buildRequest(messages: List<PromptMessage>, options: GenerationOptions): ProviderRequest {
        // system 抽到顶层；多家 system 用 \n\n 连接
        val systemText = messages
            .filter { it.role == PromptRole.SYSTEM }
            .joinToString("\n\n") { it.content }
            .trim()

        val conversational = messages.filter { it.role != PromptRole.SYSTEM }.map {
            it.role.wireName() to it.content
        }

        val body = buildJsonObject {
            put("model", options.model)
            put("max_tokens", options.maxTokens)
            put("stream", options.stream)
            if (systemText.isNotEmpty()) put("system", systemText)

            put("messages", buildJsonArray {
                for ((role, content) in mergeConsecutive(conversational)) {
                    add(
                        buildJsonObject {
                            put("role", role)
                            put("content", content)
                        },
                    )
                }
            })

            options.temperature?.let { put("temperature", it) }
            options.topP?.let { put("top_p", it) }
            if (options.stop.isNotEmpty()) {
                put("stop_sequences", buildJsonArray { options.stop.forEach { add(JsonPrimitive(it)) } })
            }
        }

        return ProviderRequest(
            url = "${baseUrl.trimEnd('/')}/v1/messages",
            headers = buildMap {
                put("Content-Type", "application/json")
                put("x-api-key", apiKey)
                put("anthropic-version", apiVersion)
                putAll(extraHeaders)
            },
            body = providerJson.encodeToString(JsonObject.serializer(), body),
        )
    }

    override fun createInterpreter(): StreamInterpreter = AnthropicStreamInterpreter()

    override fun extractContent(response: JsonObject): String =
        (response["content"] as? JsonArray)
            ?.mapNotNull { element ->
                val block = element as? JsonObject ?: return@mapNotNull null
                if (block["type"]?.jsonPrimitive?.content == "text") {
                    block["text"]?.jsonPrimitive?.content
                } else {
                    null
                }
            }
            ?.joinToString("")
            .orEmpty()

    private companion object {
        /** Anthropic 要求相邻消息角色不同，否则报错。 */
        fun mergeConsecutive(messages: List<Pair<String, String>>): List<Pair<String, String>> {
            val merged = ArrayList<Pair<String, String>>()
            for ((role, content) in messages) {
                val last = merged.lastOrNull()
                if (last != null && last.first == role) {
                    merged[merged.size - 1] = role to (last.second + "\n" + content)
                } else {
                    merged += role to content
                }
            }
            return merged
        }
    }
}

/**
 * Google Gemini（generativelanguage）。
 *
 * 三处差别：
 *  1. **模型名在 URL 路径里**，不在请求体；
 *  2. 角色叫 **`model`** 而不是 `assistant`；
 *  3. 鉴权走查询参数 `key=`；流式要显式加 `alt=sse`，否则返回的是 JSON 数组。
 *
 * @param baseUrl 例：`https://generativelanguage.googleapis.com/v1beta`
 */
class GeminiProvider(
    private val baseUrl: String,
    private val apiKey: String,
    override val id: String = "gemini",
    private val extraHeaders: Map<String, String> = emptyMap(),
) : ChatProvider {

    override fun buildRequest(messages: List<PromptMessage>, options: GenerationOptions): ProviderRequest {
        val systemText = messages
            .filter { it.role == PromptRole.SYSTEM }
            .joinToString("\n\n") { it.content }
            .trim()

        val conversational = messages.filter { it.role != PromptRole.SYSTEM }

        val body = buildJsonObject {
            if (systemText.isNotEmpty()) {
                put(
                    "systemInstruction",
                    buildJsonObject {
                        put("parts", buildJsonArray { add(buildJsonObject { put("text", systemText) }) })
                    },
                )
            }

            put("contents", buildJsonArray {
                for (message in conversational) {
                    add(
                        buildJsonObject {
                            // Gemini 用 "model" 表示助手
                            put("role", if (message.role == PromptRole.ASSISTANT) "model" else "user")
                            put(
                                "parts",
                                buildJsonArray { add(buildJsonObject { put("text", message.content) }) },
                            )
                        },
                    )
                }
            })

            put(
                "generationConfig",
                buildJsonObject {
                    put("maxOutputTokens", options.maxTokens)
                    options.temperature?.let { put("temperature", it) }
                    options.topP?.let { put("topP", it) }
                    if (options.stop.isNotEmpty()) {
                        put("stopSequences", buildJsonArray { options.stop.forEach { add(JsonPrimitive(it)) } })
                    }
                },
            )
        }

        val method = if (options.stream) "streamGenerateContent" else "generateContent"
        val suffix = if (options.stream) "?alt=sse&key=$apiKey" else "?key=$apiKey"

        return ProviderRequest(
            url = "${baseUrl.trimEnd('/')}/models/${options.model}:$method$suffix",
            headers = buildMap {
                put("Content-Type", "application/json")
                putAll(extraHeaders)
            },
            body = providerJson.encodeToString(JsonObject.serializer(), body),
        )
    }

    override fun createInterpreter(): StreamInterpreter = GeminiStreamInterpreter()

    override fun extractContent(response: JsonObject): String =
        (response["candidates"] as? JsonArray)?.firstOrNull()
            ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.let { it as? JsonArray }
            ?.mapNotNull { part ->
                val obj = part as? JsonObject ?: return@mapNotNull null
                val isThought = obj["thought"]?.let {
                    runCatching { it.jsonPrimitive.content.toBoolean() }.getOrDefault(false)
                } ?: false
                if (isThought) null else obj["text"]?.jsonPrimitive?.content
            }
            ?.joinToString("")
            .orEmpty()
}
