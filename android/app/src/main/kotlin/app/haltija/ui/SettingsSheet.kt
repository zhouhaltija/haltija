package app.haltija.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.haltija.data.AppSettings
import kotlinx.coroutines.launch

/**
 * 设置面板。
 *
 * 切换 provider 时会**自动填上对应的默认 Base URL 与模型名** ——
 * 这三家的 URL 形态差别很大，让用户手填很容易出错。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    current: AppSettings,
    dataRootLabel: String,
    characterCount: Int,
    onDismiss: () -> Unit,
    onSave: (AppSettings) -> Unit,
    onPickDataFolder: () -> Unit,
    onUsePrivateFolder: () -> Unit,
    canChangeDataRoot: Boolean = true,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember { mutableStateOf(current) }
    var maxTokensText by remember { mutableStateOf(current.maxTokens.toString()) }
    var temperatureText by remember { mutableStateOf(current.temperature.toString()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("连接设置", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            Text("服务", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppSettings.PROVIDERS.forEach { (id, label) ->
                    FilterChip(
                        selected = draft.providerId == id,
                        onClick = { draft = AppSettings.defaultsFor(id) },
                        label = { Text(label) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = draft.baseUrl,
                onValueChange = { draft = draft.copy(baseUrl = it) },
                label = { Text("Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = draft.apiKey,
                onValueChange = { draft = draft.copy(apiKey = it) },
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "密钥用 EncryptedSharedPreferences 保存，不明文落盘。",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = draft.model,
                onValueChange = { draft = draft.copy(model = it) },
                label = { Text("模型名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = maxTokensText,
                    onValueChange = { maxTokensText = it },
                    label = { Text("max tokens") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = temperatureText,
                    onValueChange = { temperatureText = it },
                    label = { Text("temperature") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(24.dp))
            Text("数据目录", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "当前：$dataRootLabel（找到 $characterCount 个角色）",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "可以指向桌面 SillyTavern 的 data/default-user/ 目录，" +
                    "手机与电脑就能共用同一份角色卡与聊天记录。",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPickDataFolder, enabled = canChangeDataRoot) { Text("选择目录") }
                TextButton(onClick = onUsePrivateFolder, enabled = canChangeDataRoot) { Text("用应用私有目录") }
            }

            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(
                    onClick = {
                        onSave(
                            draft.copy(
                                maxTokens = maxTokensText.toIntOrNull()?.coerceIn(1, 1_000_000) ?: 1024,
                                temperature = temperatureText.toDoubleOrNull()?.coerceIn(0.0, 2.0) ?: 0.8,
                            ),
                        )
                    },
                ) { Text("保存") }
            }
        }
    }
}
