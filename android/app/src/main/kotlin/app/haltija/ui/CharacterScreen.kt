package app.haltija.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.haltija.core.data.store.CharacterEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterScreen(
    characters: List<CharacterEntry>,
    selected: CharacterEntry?,
    enabled: Boolean,
    onSelect: (CharacterEntry) -> Unit,
    onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val entries = characters.ifEmpty { listOfNotNull(selected) }
        .filter { it.displayName.contains(query, ignoreCase = true) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("角色") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回聊天") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text("搜索角色") }, modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            if (characters.isEmpty()) {
                Text("目录里还没有角色卡。可先使用演示角色，或在设置中选择数据目录。",
                    modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("没有找到角色") }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(148.dp), contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(entries, key = { it.fileName }) { entry ->
                        val isSelected = entry.fileName == selected?.fileName
                        Card(
                            onClick = { onSelect(entry) }, enabled = enabled,
                            colors = CardDefaults.cardColors(containerColor = if (isSelected)
                                MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            CharacterAvatar(entry)
                            Text(entry.displayName, Modifier.padding(12.dp), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            if (isSelected) {
                                Icon(Icons.Filled.CheckCircle, "当前角色", Modifier.padding(start = 12.dp, bottom = 12.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CharacterAvatar(entry: CharacterEntry) {
    // 只在后台解码展示尺寸的缩略图，避免把每张 PNG 的完整分辨率送到 GPU。
    val bitmap by produceState<ImageBitmap?>(null, entry.fileName, entry.avatar) {
        value = withContext(Dispatchers.Default) {
            val bytes = entry.avatar ?: return@withContext null
            runCatching {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                var sample = 1
                while (options.outWidth / sample > 512 || options.outHeight / sample > 512) sample *= 2
                options.inJustDecodeBounds = false
                options.inSampleSize = sample
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
        if (bitmap == null) {
            Text(entry.displayName.take(1), style = MaterialTheme.typography.displayLarge)
        } else {
            Image(bitmap!!, contentDescription = "${entry.displayName} 的头像", modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop)
        }
    }
}
