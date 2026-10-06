package app.haltija.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.activity.compose.BackHandler
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.haltija.core.prompt.model.PromptRole

/**
 * 聊天界面。
 *
 * 这一版是能真的聊的：填好 API Key 之后消息会发出去、回复会**边收边显示**。
 * 角色与会话来自数据目录；没有角色卡时提供演示角色。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel(),
    onOpenDiagnostics: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var input by rememberSaveable(state.selectedCharacter?.fileName, state.selectedChatFile) { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf("chat") }
    BackHandler(page != "chat") { page = "chat" }

    if (page == "characters") {
        CharacterScreen(state.characters, state.selectedCharacter, state.canNavigate,
            onSelect = { viewModel.selectCharacter(it); page = "chat" }, onBack = { page = "chat" })
        return
    }
    if (page == "sessions") {
        SessionScreen(state.selectedCharacter?.displayName.orEmpty(), state.sessions, state.selectedChatFile,
            state.canNavigate, onSelect = { viewModel.selectSession(it); page = "chat" },
            onNew = { viewModel.newSession(); page = "chat" }, onBack = { page = "chat" })
        return
    }

    val listState = rememberLazyListState()
    val itemCount = state.messages.size + if (state.isGenerating) 1 else 0

    // 有新内容就滚到底部
    LaunchedEffect(itemCount, state.streamingText) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("haltija", style = MaterialTheme.typography.titleMedium)
                        val name = state.selectedCharacter?.displayName ?: DemoContent.CHARACTER_NAME
                        Text(
                            "$name · ${state.settings.model}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { page = "characters" }, enabled = state.canNavigate) {
                        Icon(Icons.Filled.People, contentDescription = "选择角色")
                    }
                    IconButton(onClick = { page = "sessions" }, enabled = state.canNavigate) {
                        Icon(Icons.Filled.History, contentDescription = "管理会话")
                    }
                    IconButton(onClick = onOpenDiagnostics) {
                        Icon(Icons.Filled.BugReport, contentDescription = "内核自检")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.messages) { message -> MessageBubble(message) }

                if (state.isGenerating) {
                    item {
                        StreamingBubble(state.streamingText, state.streamingReasoning)
                    }
                }
            }

            state.error?.let { message ->
                ErrorBar(message, onDismiss = viewModel::clearError)
            }

            if (state.hasUnsavedChanges) {
                TextButton(onClick = viewModel::retrySave, enabled = !state.isLoading) { Text("重试保存") }
            } else if (state.selectedChatFile == null && !state.isLoading) {
                TextButton(onClick = viewModel::reloadDataRoot) { Text("重试加载目录") }
            }

            InputBar(
                value = input,
                onValueChange = { input = it },
                isGenerating = state.isGenerating,
                canSend = state.canSend && input.isNotBlank(),
                onSend = {
                    if (viewModel.send(input)) input = ""
                },
                onStop = viewModel::stop,
            )
        }
    }

    // SAF 目录选择：必须用 OpenDocumentTree，普通文件选择器拿不到目录写权限
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) viewModel.useSafDataRoot(uri) }

    if (showSettings) {
        SettingsSheet(
            current = state.settings,
            dataRootLabel = state.dataRootLabel.ifBlank { "应用私有目录" },
            characterCount = state.characters.size,
            onDismiss = { showSettings = false },
            onSave = {
                viewModel.updateSettings(it)
                showSettings = false
            },
            onPickDataFolder = { folderPicker.launch(null) },
            onUsePrivateFolder = viewModel::usePrivateDataRoot,
            canChangeDataRoot = state.canNavigate,
        )
    }
}

// ------------------------------------------------------------ 组件

@Composable
private fun MessageBubble(message: UiMessage) {
    val isUser = message.role == PromptRole.USER
    val color = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart

    Box(Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Card(
            modifier = Modifier.fillMaxWidth(0.92f),
            colors = CardDefaults.cardColors(containerColor = color),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                if (message.reasoning.isNotBlank()) {
                    ReasoningBlock(message.reasoning)
                    Spacer(Modifier.height(8.dp))
                }
                Text(message.content, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun StreamingBubble(text: String, reasoning: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Card(
            modifier = Modifier.fillMaxWidth(0.92f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                if (reasoning.isNotBlank()) {
                    ReasoningBlock(reasoning)
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    text.ifEmpty { "…" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** 思维链用弱化的样式展示，和正文区分开。 */
@Composable
private fun ReasoningBlock(reasoning: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp),
            )
            .padding(8.dp),
    ) {
        Text(
            "思考",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            reasoning,
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorBar(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onDismiss) {
                Text("×", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    isGenerating: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("说点什么…") },
            maxLines = 5,
        )
        Spacer(Modifier.width(8.dp))
        if (isGenerating) {
            IconButton(onClick = onStop) {
                Icon(Icons.Filled.Stop, contentDescription = "停止")
            }
        } else {
            IconButton(onClick = onSend, enabled = canSend) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
            }
        }
    }
}
