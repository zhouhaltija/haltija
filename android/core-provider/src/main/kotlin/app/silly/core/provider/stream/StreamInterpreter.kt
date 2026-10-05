package app.silly.core.provider.stream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 一次流式增量。
 *
 * 三家 API 的原始事件格式完全不同，但上层只关心这几样东西，
 * 所以 [StreamInterpreter] 把它们统一成这个形状。
 */
data class StreamDelta(
    /** 正文增量。 */
    val text: String = "",

    /** 思维链增量（DeepSeek 的 `reasoning_content`、Anthropic 的 `thinking`、Gemini 的 `thought` 部分）。 */
    val reasoning: String = "",

    /** 图片（data URL）。Gemini 会流式返回 inline 图片。 */
    val images: List<String> = emptyList(),

    /** 流结束。 */
    val done: Boolean = false,
) {
    val isEmpty: Boolean get() = text.isEmpty() && reasoning.isEmpty() && images.isEmpty() && !done

    operator fun plus(other: StreamDelta) = StreamDelta(
        text = text + other.text,
        reasoning = reasoning + other.reasoning,
        images = images + other.images,
        done = done || other.done,
    )

    companion object {
        val EMPTY = StreamDelta()
        val DONE = StreamDelta(done = true)
    }
}

/**
 * 把一个 SSE 事件翻译成 [StreamDelta]。
 *
 * 逐条对应 SillyTavern 的 `getStreamingReply`（`public/scripts/openai.js:3224+`）——
 * 那个函数按 `chat_completion_source` 分派，这里拆成三个实现。
 */
interface StreamInterpreter {
    /** 返回 null 表示这个事件不产出任何内容（例如心跳、usage 事件）。 */
    fun interpret(event: SseEvent): StreamDelta?
}

internal val streamJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** 从事件里取 JSON；解析失败返回 null。 */
internal fun SseEvent.parseJson(): JsonObject? =
    runCatching { streamJson.parseToJsonElement(data).jsonObject }.getOrNull()

/**
 * OpenAI 兼容接口的流式解释器。
 *
 * 适用于官方 OpenAI、DeepSeek、xAI、OpenRouter、以及所有兼容端点
 * （LM Studio / Ollama / vLLM / 各种中转）。
 *
 * 数据形如：
 *
 * ```
 * data: {"choices":[{"delta":{"content":"Hel"}}]}
 * data: {"choices":[{"delta":{"reasoning_content":"想…"}}]}
 * data: [DONE]
 * ```
 *
 * 反直觉之处：**思维链字段名各家不一样**。DeepSeek 用 `reasoning_content`，
 * OpenRouter 用 `reasoning`，有的还把内容放在 `message` 而不是 `delta` 里。
 * 这里全部兼容，取值顺序与 ST 一致。
 */
class OpenAiStreamInterpreter : StreamInterpreter {

    override fun interpret(event: SseEvent): StreamDelta? {
        if (event.data.trim() == "[DONE]") return StreamDelta.DONE

        val json = event.parseJson() ?: return null

        // 有些端点在出错时返回 {"error": {...}}
        if (json["error"] != null) {
            val message = json["error"]?.let {
                (it as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: it.toString()
            }
            throw ProviderStreamException(message ?: "未知错误")
        }

        val choice = json["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val delta = choice["delta"] as? JsonObject
        val message = choice["message"] as? JsonObject

        val text = delta?.get("content")?.jsonPrimitive?.contentOrNull
            ?: message?.get("content")?.jsonPrimitive?.contentOrNull
            ?: choice["text"]?.jsonPrimitive?.contentOrNull
            ?: ""

        val reasoning = delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
            ?: delta?.get("reasoning")?.jsonPrimitive?.contentOrNull
            ?: message?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
            ?: message?.get("reasoning")?.jsonPrimitive?.contentOrNull
            ?: ""

        val images = (delta?.get("images") as? JsonArray ?: message?.get("images") as? JsonArray)
            ?.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                obj["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            }
            .orEmpty()

        val finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNull

        val result = StreamDelta(text = text, reasoning = reasoning, images = images)
        return if (finish != null && result.isEmpty) StreamDelta(done = true) else result
    }
}

/**
 * Anthropic Messages API 的流式解释器。
 *
 * 与 OpenAI 家最大的不同：**事件类型在 `event:` 行里**，`data:` 里还有一层 `type`。
 *
 * ```
 * event: content_block_delta
 * data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Hel"}}
 *
 * event: content_block_delta
 * data: {"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"想…"}}
 *
 * event: message_stop
 * data: {"type":"message_stop"}
 * ```
 */
class AnthropicStreamInterpreter : StreamInterpreter {

    override fun interpret(event: SseEvent): StreamDelta? {
        val json = event.parseJson() ?: return null
        val type = json["type"]?.jsonPrimitive?.contentOrNull ?: event.event

        return when (type) {
            "content_block_delta" -> {
                val delta = json["delta"] as? JsonObject
                StreamDelta(
                    text = delta?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    reasoning = delta?.get("thinking")?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }

            "message_delta" -> {
                // 只有 stop_reason / usage，没有正文
                val stopReason = (json["delta"] as? JsonObject)
                    ?.get("stop_reason")?.jsonPrimitive?.contentOrNull
                if (stopReason != null) StreamDelta.DONE else null
            }

            "message_stop" -> StreamDelta.DONE

            // 这些都不产出正文
            "message_start", "content_block_start", "content_block_stop", "ping" -> null

            "error" -> {
                val message = (json["error"] as? JsonObject)
                    ?.get("message")?.jsonPrimitive?.contentOrNull
                throw ProviderStreamException(message ?: "Anthropic 返回错误事件")
            }

            else -> null
        }
    }
}

/**
 * Google Gemini（generativelanguage / Vertex）的流式解释器。
 *
 * 用 `?alt=sse` 拿到 SSE，每个事件的 `data` 是一个完整的候选块：
 *
 * ```
 * data: {"candidates":[{"content":{"parts":[{"text":"Hel"}]}}]}
 * ```
 *
 * **思维链藏在 parts 里**：带 `thought: true` 的 part 是思考内容，
 * 不带的是正文。这一点与另外两家都不同（它们用独立字段）。
 * 图片则放在 `inlineData` 里。
 */
class GeminiStreamInterpreter : StreamInterpreter {

    override fun interpret(event: SseEvent): StreamDelta? {
        if (event.data.trim() == "[DONE]") return StreamDelta.DONE

        val json = event.parseJson() ?: return null
        val candidate = (json["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val parts = (candidate["content"] as? JsonObject)?.get("parts") as? JsonArray ?: return null

        val reasoning = StringBuilder()
        val text = StringBuilder()
        val images = ArrayList<String>()

        for (element in parts) {
            val part = element as? JsonObject ?: continue
            val partText = part["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val isThought = part["thought"]?.let {
                runCatching { it.jsonPrimitive.boolean }.getOrDefault(false)
            } ?: false

            if (isThought) {
                reasoning.append(partText)
            } else {
                text.append(partText)
                val inline = part["inlineData"] as? JsonObject
                val mime = inline?.get("mimeType")?.jsonPrimitive?.contentOrNull
                val data = inline?.get("data")?.jsonPrimitive?.contentOrNull
                if (mime != null && data != null) images += "data:$mime;base64,$data"
            }
        }

        val finishReason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
        val result = StreamDelta(text.toString(), reasoning.toString(), images)
        return if (finishReason != null && result.isEmpty) StreamDelta.DONE else result
    }
}

/** 流式过程中服务端返回的错误。 */
class ProviderStreamException(message: String) : RuntimeException(message)
