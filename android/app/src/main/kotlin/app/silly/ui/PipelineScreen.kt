package app.silly.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.silly.core.prompt.model.PromptMessage

/**
 * 首屏：证明内核在设备上真的能跑。
 *
 * 这一版刻意不做成聊天界面 —— 先把「数据层与引擎在 Android 上可用」这件事
 * 摆出来，再往上搭交互。管线只在 [remember] 里跑一次。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PipelineScreen(onBack: () -> Unit = {}) {
    val result = remember { PipelineDemo.run() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("内核自检") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "内核自检",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "下面每一步都真的执行了 core-data / core-prompt / core-provider 里的代码，" +
                        "没有 WebView，也没有内嵌 Node。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items(result.steps) { step ->
                SectionCard(step.title, step.detail)
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "组装后的消息列表",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            items(result.messages) { message -> MessageCard(message) }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "三家请求体",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            items(result.requests) { request ->
                SectionCard(request.provider, "${request.url}\n\n${request.body}", monospace = true)
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, detail: String, monospace: Boolean = false) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(
                detail,
                style = if (monospace) {
                    MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                } else {
                    MaterialTheme.typography.bodySmall
                },
            )
        }
    }
}

@Composable
private fun MessageCard(message: PromptMessage) {
    val tint = when (message.role) {
        app.silly.core.prompt.model.PromptRole.SYSTEM -> MaterialTheme.colorScheme.secondaryContainer
        app.silly.core.prompt.model.PromptRole.USER -> MaterialTheme.colorScheme.primaryContainer
        app.silly.core.prompt.model.PromptRole.ASSISTANT -> MaterialTheme.colorScheme.tertiaryContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = tint),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "${message.role.name} · ${message.source.name}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(message.content, style = MaterialTheme.typography.bodySmall)
        }
    }
}
