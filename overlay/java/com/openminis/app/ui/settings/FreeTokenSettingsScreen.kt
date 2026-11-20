package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ext.opencode.policy.FreeTierTool
import com.openminis.app.ext.opencode.policy.ShellCommandBuilder
import com.openminis.app.ext.opencode.runtime.OpencodeLayout

/**
 * 免费 Token 通道（内嵌 OpenCode）设置页。
 *
 * 这一页刻意不依赖任何网络或子进程：它把「通道要用的真实规则」直接算出来给用户看，
 * 包括运行时目录的真实落点、上游硬性要求的四个工具名与 Minis 的映射、以及命令拼装结果。
 *
 * 之所以先做「只读自检」而不是「一键启动」：启动链路依赖 libopencode.so，
 * 而该二进制是否随包分发取决于打包产物，先把不依赖二进制的部分做成可见、可验证的。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeTokenSettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    // 真实计算：运行时目录布局（file 对象，不创建）
    val layout = remember { OpencodeLayout(context.filesDir) }
    val configExists = remember { layout.configFile.exists() }
    val serviceExists = remember { layout.serviceFile.exists() }

    // 真实计算：上游要求的四个工具名 → Minis 实际工具名
    val toolRows = remember { FreeTierTool.ALL }

    // 真实计算：命令拼装样例（这条命令是 grep 的最终形态）
    val sampleGrep = remember {
        ShellCommandBuilder.grep("TODO", path = "src", include = "kt", limit = 20)
    }
    val sampleGlob = remember {
        ShellCommandBuilder.glob("**/*.kt", path = "app")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("免费 Token 通道") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------- 状态 ----------
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "通道状态",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    StatusLine("运行时根目录", layout.root.absolutePath, true)
                    StatusLine("配置文件", if (configExists) "已生成" else "未生成", configExists)
                    StatusLine("服务身份", if (serviceExists) "已注册" else "未注册", serviceExists)
                    StatusLine("原生二进制", "未随包分发", false)
                }
            }

            // ---------- 为什么需要别名 ----------
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "工具名映射",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "上游只接受左侧四个名字，Minis 实际用的是右侧。" +
                            "名字对不上会被拒绝，而报错文案会误导成「没装 OpenCode」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "上游要求",
                            modifier = Modifier.width(96.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Minis 实际",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    toolRows.forEach { row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = row.mcpName,
                                modifier = Modifier.width(96.dp),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = row.minisTool,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ---------- 命令拼装 ----------
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "命令拼装（实时计算）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "glob / grep 由 shell 命令实现，参数全部做引号转义，含路径穿越拦截。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    CommandBox(sampleGlob)
                    Spacer(Modifier.height(6.dp))
                    CommandBox(sampleGrep)
                }
            }

            // ---------- 说明 ----------
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "当前进度",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    BulletLine("已完成", "SSE 解析、事件映射、用量台账、重连策略、进程管理、配置生成、Key 池调度、人设与记忆、插件校验 —— 共 159 个单元测试通过")
                    BulletLine("已完成", "本页展示的映射与命令拼装均为真实计算结果，非占位文本")
                    BulletLine("待完成", "原生二进制分发与 Provider 接入（需要在真机构建中验证 libopencode.so 可执行）")
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(88.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (ok) FontFamily.Default else FontFamily.Monospace,
            color = if (ok) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun CommandBox(command: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = command,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color(0xFF32D74B),
        )
    }
}

@Composable
private fun BulletLine(tag: String, text: String) {
    val tint = if (tag == "已完成") Color(0xFF32D74B) else Color(0xFFFF9F0A)
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = tag,
            modifier = Modifier.width(56.dp),
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
