package pro.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import pro.openminis.app.ext.opencode.runtime.FreeTokenManager

/**
 * 账号登录的独立入口。
 *
 * 之前登录按钮只长在「免费 Token 通道」页面里——那个标题看起来跟账号无关，
 * 用户根本不会点进去找。设置首页直接给一行「OpenCode 账号登录」，点进来就是这个页面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpencodeAccountScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { FreeTokenManager.get(context) }

    // 上次启用过就自动拉起；没启用也没关系，卡片里的登录按钮会自己启动
    LaunchedEffect(Unit) { manager.autoStartIfOptedIn() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OpenCode 账号") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            OpencodeAccountCard(manager)
        }
    }
}
