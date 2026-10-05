package app.silly

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.silly.ui.ChatScreen
import app.silly.ui.PipelineScreen

/**
 * 唯一的 Activity。
 *
 * UI 全部是 Compose，没有任何 WebView —— 这正是本项目存在的理由：
 * SillyTavern 的交互逻辑由 Kotlin 重写，只对齐它的**数据格式**。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SillyApp() }
    }
}

@Composable
fun SillyApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            // 两个界面：聊天是主界面，自检页把内核每一步的产物摊开给人看
            var showDiagnostics by remember { mutableStateOf(false) }

            if (showDiagnostics) {
                PipelineScreen(onBack = { showDiagnostics = false })
            } else {
                ChatScreen(onOpenDiagnostics = { showDiagnostics = true })
            }
        }
    }
}
