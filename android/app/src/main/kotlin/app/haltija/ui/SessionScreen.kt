package app.haltija.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.haltija.core.data.store.ChatEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    characterName: String,
    sessions: List<ChatEntry>,
    selectedFile: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(topBar = {
        TopAppBar(title = {
            Column {
                Text("会话")
                Text(characterName, style = MaterialTheme.typography.labelSmall)
            }
        }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回聊天") }
        }, actions = {
            IconButton(onClick = onNew, enabled = enabled) { Icon(Icons.Filled.Add, "新建会话") }
        })
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (sessions.isEmpty()) item { Text("还没有会话，点击右上角新建。") }
            items(sessions, key = { it.fileName }) { entry ->
                val selected = entry.fileName == selectedFile
                Card(
                    onClick = { onSelect(entry.fileName) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = if (selected)
                        MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(entry.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        Text("${entry.messageCount} 条消息${if (selected) " · 当前会话" else ""}",
                            style = MaterialTheme.typography.labelSmall)
                        entry.chat.lastMessage?.let {
                            Text(it.activeText.take(200), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
