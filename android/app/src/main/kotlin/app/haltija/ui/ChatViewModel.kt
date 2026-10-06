package app.haltija.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.haltija.core.data.store.CharacterEntry
import app.haltija.core.data.store.ChatEntry
import app.haltija.core.data.world.WorldBook
import app.haltija.core.prompt.model.PromptMessage
import app.haltija.core.prompt.model.PromptRole
import app.haltija.core.provider.AnthropicProvider
import app.haltija.core.provider.ChatProvider
import app.haltija.core.provider.GenerationOptions
import app.haltija.core.provider.GeminiProvider
import app.haltija.core.provider.OpenAiCompatibleProvider
import app.haltija.core.provider.transport.ChatService
import app.haltija.core.provider.transport.ProviderHttpException
import app.haltija.data.AppSettings
import app.haltija.data.ChatSession
import app.haltija.data.ChatGenerationRunner
import app.haltija.data.SessionSaveException
import app.haltija.data.ChatSessionStore
import app.haltija.data.DataRootStore
import app.haltija.data.OkHttpProviderHttpClient
import app.haltija.data.SessionSelectionStore
import app.haltija.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive

/** UI 是原始 Chat 的投影，写回始终使用原始数据模型。 */
data class UiMessage(val role: PromptRole, val content: String, val reasoning: String = "")

data class ChatUiState(
    val settings: AppSettings = AppSettings(),
    val characters: List<CharacterEntry> = emptyList(),
    val selectedCharacter: CharacterEntry? = null,
    val sessions: List<ChatEntry> = emptyList(),
    val selectedChatFile: String? = null,
    val dataRootLabel: String = "",
    val messages: List<UiMessage> = emptyList(),
    val streamingText: String = "",
    val streamingReasoning: String = "",
    val isGenerating: Boolean = false,
    val isLoading: Boolean = true,
    val hasUnsavedChanges: Boolean = false,
    val error: String? = null,
) {
    val canNavigate: Boolean get() = !isGenerating && !isLoading && !hasUnsavedChanges
    val canSend: Boolean get() = canNavigate && settings.isUsable && selectedChatFile != null
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsStore = SettingsStore(application)
    private val dataRootStore = DataRootStore(application)
    private val selectionStore = SessionSelectionStore(application)
    private val chatService = ChatService(OkHttpProviderHttpClient())
    private val demo = CharacterEntry("haltija-demo.json", DemoContent.character(), null)
    private val _state = MutableStateFlow(ChatUiState(settings = settingsStore.load()))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var sessionStore: ChatSessionStore? = null
    private var session: ChatSession? = null
    private var rootKey = ""
    private var worldBook: WorldBook? = null
    private var generationJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                loadDataRoot()
            } catch (e: Exception) {
                _state.update { it.copy(error = "读取数据目录失败：${e.message}") }
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    private suspend fun loadDataRoot() {
        val loaded = withContext(Dispatchers.IO) {
            val data = dataRootStore.open()
            val key = dataRootStore.selectionKey()
            val characters = data.characters.list()
            val character = characters.find { it.fileName == selectionStore.character(key) }
                ?: characters.firstOrNull() ?: demo
            val store = ChatSessionStore(data.chats)
            val opened = restore(store, character, key)
            val book = data.worlds.listNames().firstOrNull()?.let(data.worlds::load)
            LoadedData(key, data.root.label, characters, store, opened, book, store.list(character))
        }
        sessionStore = loaded.store
        rootKey = loaded.key
        worldBook = loaded.book
        session = loaded.session
        rememberSelection(loaded.session)
        _state.update {
            it.copy(
                characters = loaded.characters,
                selectedCharacter = loaded.session.character,
                dataRootLabel = loaded.label,
                sessions = loaded.sessions,
                selectedChatFile = loaded.session.fileName,
                messages = loaded.session.uiMessages(),
                hasUnsavedChanges = false,
                error = null,
            )
        }
    }

    private fun restore(store: ChatSessionStore, character: CharacterEntry, key: String): ChatSession {
        val chats = store.list(character)
        val remembered = selectionStore.chat(key, character.fileName)
        val file = chats.find { it.fileName == remembered }?.fileName ?: chats.firstOrNull()?.fileName
        return if (file == null) store.create(character, DemoContent.USER_NAME) else store.load(character, file)
    }

    fun reloadDataRoot() = storageAction { loadDataRoot() }

    fun useSafDataRoot(uri: Uri) = storageAction {
        withContext(Dispatchers.IO) { dataRootStore.useSafTree(uri) }
        loadDataRoot()
    }

    fun usePrivateDataRoot() = storageAction {
        withContext(Dispatchers.IO) { dataRootStore.usePrivate() }
        loadDataRoot()
    }

    fun selectCharacter(entry: CharacterEntry) = storageAction {
        val store = sessionStore ?: return@storageAction
        val opened = withContext(Dispatchers.IO) { restore(store, entry, rootKey) }
        adopt(opened, store)
    }

    fun selectSession(fileName: String) = storageAction {
        val current = session ?: return@storageAction
        val store = sessionStore ?: return@storageAction
        val opened = withContext(Dispatchers.IO) { store.load(current.character, fileName) }
        adopt(opened, store)
    }

    fun newSession() = storageAction {
        val current = session ?: return@storageAction
        val store = sessionStore ?: return@storageAction
        val opened = withContext(Dispatchers.IO) { store.create(current.character, DemoContent.USER_NAME) }
        adopt(opened, store)
    }

    /** 所有目录与会话 IO 在后台执行；忙碌或保存失败时不切换会话。 */
    private fun storageAction(action: suspend () -> Unit) {
        if (!_state.value.canNavigate) return
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "操作失败：${e.message}") }
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    private suspend fun adopt(opened: ChatSession, store: ChatSessionStore) {
        val sessions = withContext(Dispatchers.IO) { store.list(opened.character) }
        session = opened
        rememberSelection(opened)
        _state.update {
            it.copy(
                selectedCharacter = opened.character,
                selectedChatFile = opened.fileName,
                sessions = sessions,
                messages = opened.uiMessages(),
                streamingText = "",
                streamingReasoning = "",
                error = null,
            )
        }
    }

    private fun rememberSelection(opened: ChatSession) {
        selectionStore.save(rootKey, opened.character.fileName, opened.fileName)
    }

    fun updateSettings(settings: AppSettings) {
        settingsStore.save(settings)
        _state.update { it.copy(settings = settings, error = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /** 用户消息先落盘，再发送请求；捕获本次会话与连接设置，流式期间不允许切换。 */
    fun send(text: String): Boolean {
        val trimmed = text.trim()
        val store = sessionStore ?: return false
        val current = session ?: return false
        if (trimmed.isEmpty() || !_state.value.canSend) return false
        val settings = _state.value.settings
        val book = worldBook
        val pending = store.append(current, trimmed, isUser = true, userName = DemoContent.USER_NAME)
        session = pending
        _state.update {
            it.copy(messages = pending.uiMessages(), isGenerating = true, error = null,
                streamingText = "", streamingReasoning = "")
        }
        generationJob = viewModelScope.launch {
            ChatGenerationRunner(store).run(
                pending, DemoContent.USER_NAME,
                source = {
                    val promptMessages = withContext(Dispatchers.Default) {
                        PromptPipeline.buildMessages(
                            pending.character.card, book,
                            pending.uiMessages().filter { it.role != PromptRole.SYSTEM }
                                .map { PromptMessage(it.role, it.content) },
                        )
                    }
                    chatService.stream(providerFor(settings), promptMessages, GenerationOptions(
                        model = settings.model, maxTokens = settings.maxTokens,
                        temperature = settings.temperature, stream = true,
                    ))
                },
                onProgress = { result ->
                    _state.update { it.copy(streamingText = result.text, streamingReasoning = result.reasoning) }
                },
                onComplete = { result ->
                    session = result.session
                    val sessions = runCatching { withContext(Dispatchers.IO) { store.list(pending.character) } }
                        .getOrElse { _state.value.sessions }
                    val error = when (val cause = result.error) {
                        is SessionSaveException -> "保存失败：${cause.cause?.message ?: "写入被中断"}。请重试保存。"
                        is ProviderHttpException -> formatHttpError(cause)
                        null -> if (result.emptyReply) "模型没有返回内容" else null
                        else -> cause.message ?: "未知错误"
                    }
                    _state.update {
                        it.copy(messages = result.session.uiMessages(), isGenerating = false,
                            streamingText = "", streamingReasoning = "", sessions = sessions,
                            hasUnsavedChanges = !result.saved, error = error)
                    }
                    generationJob = null
                },
            )
        }
        return true
    }

    fun retrySave() {
        if (_state.value.isGenerating || _state.value.isLoading || !_state.value.hasUnsavedChanges) return
        val current = session ?: return
        val store = sessionStore ?: return
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.save(current) }
                val sessions = withContext(Dispatchers.IO) { store.list(current.character) }
                _state.update { it.copy(hasUnsavedChanges = false, error = null, sessions = sessions) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "保存失败：${e.message}。请重试保存。") }
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    fun stop() { generationJob?.cancel() }

    private fun ChatSession.uiMessages(): List<UiMessage> = chat.messages.map {
        UiMessage(
            when { it.isSystem -> PromptRole.SYSTEM; it.isUser -> PromptRole.USER; else -> PromptRole.ASSISTANT },
            it.activeText,
            it.extra["reasoning"]?.let { value -> runCatching { value.jsonPrimitive.content }.getOrDefault("") }.orEmpty(),
        )
    }

    private fun providerFor(settings: AppSettings): ChatProvider = when (settings.providerId) {
        "anthropic" -> AnthropicProvider(settings.baseUrl, settings.apiKey)
        "gemini" -> GeminiProvider(settings.baseUrl, settings.apiKey)
        else -> OpenAiCompatibleProvider(settings.baseUrl, settings.apiKey)
    }

    private fun formatHttpError(e: ProviderHttpException): String = when (e.statusCode) {
        0 -> e.body
        401, 403 -> "鉴权失败（${e.statusCode}）：检查 API Key。服务端说：${e.body.take(200)}"
        404 -> "接口不存在（404）：检查 Base URL 与模型名。服务端说：${e.body.take(200)}"
        429 -> "被限流或超额（429）：${e.body.take(200)}"
        else -> "HTTP ${e.statusCode}：${e.body.take(300)}"
    }

    private data class LoadedData(
        val key: String, val label: String, val characters: List<CharacterEntry>,
        val store: ChatSessionStore, val session: ChatSession, val book: WorldBook?, val sessions: List<ChatEntry>,
    )
}
