package app.silly.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.silly.core.prompt.model.PromptMessage
import app.silly.core.prompt.model.PromptRole
import app.silly.core.provider.AnthropicProvider
import app.silly.core.provider.ChatProvider
import app.silly.core.provider.GeminiProvider
import app.silly.core.provider.GenerationOptions
import app.silly.core.provider.OpenAiCompatibleProvider
import app.silly.core.provider.transport.ChatResult
import app.silly.core.provider.transport.ChatService
import app.silly.core.provider.transport.ProviderHttpException
import app.silly.core.data.store.CharacterEntry
import app.silly.data.AppSettings
import app.silly.data.DataRootStore
import app.silly.data.OkHttpProviderHttpClient
import app.silly.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 界面上的一条消息。 */
data class UiMessage(
    val role: PromptRole,
    val content: String,
    /** 思维链（DeepSeek / Anthropic / Gemini 都可能给）。 */
    val reasoning: String = "",
)

data class ChatUiState(
    val settings: AppSettings = AppSettings(),
    /** 数据目录里找到的角色卡；为空时用内置的演示角色。 */
    val characters: List<CharacterEntry> = emptyList(),
    val selectedCharacter: CharacterEntry? = null,
    /** 当前数据根的展示名，让用户知道数据是从哪来的。 */
    val dataRootLabel: String = "",
    val messages: List<UiMessage> = emptyList(),
    /** 正在流式接收的正文（还没落成消息）。 */
    val streamingText: String = "",
    val streamingReasoning: String = "",
    val isGenerating: Boolean = false,
    val error: String? = null,
) {
    val canSend: Boolean get() = !isGenerating && settings.isUsable
}

/**
 * 聊天状态机。
 *
 * 一次生成的完整路径：**历史 → 世界书激活 → 宏与故事串 → 组装 → 构造请求 → 收流 → 增量更新 UI**。
 * 前面四步全在 `core-prompt` 里，这里只负责把它们串起来并把增量推给界面。
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)
    private val dataRootStore = DataRootStore(application)
    private val chatService = ChatService(OkHttpProviderHttpClient())

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        val settings = settingsStore.load()
        val characters = loadCharacters()

        _state.value = ChatUiState(
            settings = settings,
            characters = characters,
            selectedCharacter = characters.firstOrNull(),
            dataRootLabel = runCatching { dataRootStore.current().label }.getOrDefault(""),
            messages = listOf(
                UiMessage(
                    PromptRole.ASSISTANT,
                    characters.firstOrNull()?.card?.firstMes?.ifBlank { null }
                        ?: DemoContent.character().firstMes,
                ),
            ),
        )
    }

    // ------------------------------------------------------------ 角色与数据目录

    /**
     * 从当前数据根加载角色卡。
     *
     * 目录里没有卡（还没配数据目录，或者选错了）时返回空列表，
     * 界面会退回到内置的演示角色 —— 让用户至少能先试通收发。
     */
    private fun loadCharacters(): List<CharacterEntry> = runCatching {
        dataRootStore.open().characters.list()
    }.getOrDefault(emptyList()).ifEmpty { emptyList() }

    /** 重新扫描数据目录（用户改完目录设置后调用）。 */
    fun reloadDataRoot() {
        val characters = loadCharacters()
        val selected = characters.firstOrNull() ?: _state.value.selectedCharacter
        _state.update {
            it.copy(
                characters = characters,
                selectedCharacter = selected,
                dataRootLabel = runCatching { dataRootStore.current().label }.getOrDefault(""),
                messages = if (selected == null) it.messages else {
                    listOf(UiMessage(PromptRole.ASSISTANT, selected.card.firstMes))
                },
            )
        }
    }

    /** 用户选中了 SAF 目录。 */
    fun useSafDataRoot(uri: android.net.Uri) {
        dataRootStore.useSafTree(uri)
        reloadDataRoot()
    }

    fun usePrivateDataRoot() {
        dataRootStore.usePrivate()
        reloadDataRoot()
    }

    fun selectCharacter(entry: CharacterEntry) {
        _state.update {
            it.copy(
                selectedCharacter = entry,
                messages = listOf(UiMessage(PromptRole.ASSISTANT, entry.card.firstMes)),
            )
        }
    }

    // ------------------------------------------------------------ 设置

    fun updateSettings(settings: AppSettings) {
        settingsStore.save(settings)
        _state.update { it.copy(settings = settings, error = null) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    // ------------------------------------------------------------ 对话

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.isGenerating) return

        val settings = _state.value.settings
        if (!settings.isUsable) {
            _state.update { it.copy(error = "先在设置里填好 API Key 与模型名") }
            return
        }

        val history = _state.value.messages + UiMessage(PromptRole.USER, trimmed)
        _state.update {
            it.copy(
                messages = history,
                isGenerating = true,
                error = null,
                streamingText = "",
                streamingReasoning = "",
            )
        }

        job = viewModelScope.launch {
            var accumulated = ChatResult.EMPTY
            try {
                // 把界面上的历史还原成内核的消息模型，走完整的组装链路。
                // 有真实角色卡就用它，否则退回内置演示角色。
                val card = _state.value.selectedCharacter?.card ?: DemoContent.character()
                val book = loadFirstWorldBook()
                val promptMessages = PromptPipeline.buildMessages(
                    card,
                    book,
                    history.map { PromptMessage(it.role, it.content) },
                )

                val options = GenerationOptions(
                    model = settings.model,
                    maxTokens = settings.maxTokens,
                    temperature = settings.temperature,
                    stream = true,
                )

                chatService.stream(providerFor(settings), promptMessages, options).collect { delta ->
                    accumulated += delta
                    _state.update {
                        it.copy(
                            streamingText = accumulated.text,
                            streamingReasoning = accumulated.reasoning,
                        )
                    }
                }

                val reply = accumulated.text
                _state.update {
                    it.copy(
                        messages = if (reply.isEmpty()) {
                            it.messages
                        } else {
                            it.messages + UiMessage(PromptRole.ASSISTANT, reply, accumulated.reasoning)
                        },
                        streamingText = "",
                        streamingReasoning = "",
                        isGenerating = false,
                        error = if (reply.isEmpty()) "模型没有返回内容" else null,
                    )
                }
            } catch (e: CancellationException) {
                // 用户主动停止：保留已经收到的部分
                _state.update {
                    it.copy(
                        messages = if (accumulated.text.isEmpty()) {
                            it.messages
                        } else {
                            it.messages + UiMessage(PromptRole.ASSISTANT, accumulated.text)
                        },
                        streamingText = "",
                        streamingReasoning = "",
                        isGenerating = false,
                    )
                }
                throw e
            } catch (e: ProviderHttpException) {
                _state.update {
                    it.copy(
                        isGenerating = false,
                        streamingText = "",
                        error = formatHttpError(e),
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isGenerating = false,
                        streamingText = "",
                        error = e.message ?: "未知错误",
                    )
                }
            }
        }
    }

    /** 停止生成。已经收到的部分会被保留。 */
    fun stop() {
        job?.cancel()
        job = null
    }

    fun clearError() = dismissError()

    // ------------------------------------------------------------ 内部

    /** 取数据目录里的第一本世界书；没有就返回 null（不注入世界书）。 */
    private fun loadFirstWorldBook(): app.silly.core.data.world.WorldBook? = runCatching {
        val worlds = dataRootStore.open().worlds
        worlds.listNames().firstOrNull()?.let { worlds.load(it) }
    }.getOrNull()

    private fun providerFor(settings: AppSettings): ChatProvider = when (settings.providerId) {
        "anthropic" -> AnthropicProvider(settings.baseUrl, settings.apiKey)
        "gemini" -> GeminiProvider(settings.baseUrl, settings.apiKey)
        else -> OpenAiCompatibleProvider(settings.baseUrl, settings.apiKey)
    }

    /**
     * HTTP 错误尽量把服务端原话带出来。
     *
     * 401/403 单独说清楚 —— 最常见的原因就是 key 错了或没余额，
     * 把原始 JSON 抛给用户看反而更难懂。
     */
    private fun formatHttpError(e: ProviderHttpException): String = when (e.statusCode) {
        0 -> e.body
        401, 403 -> "鉴权失败（${e.statusCode}）：检查 API Key。服务端说：${e.body.take(200)}"
        404 -> "接口不存在（404）：检查 Base URL 与模型名。服务端说：${e.body.take(200)}"
        429 -> "被限流或超额（429）：${e.body.take(200)}"
        else -> "HTTP ${e.statusCode}：${e.body.take(300)}"
    }
}
