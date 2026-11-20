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
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 免费 Token 通道（内嵌 OpenCode）设置页。
 *
 * 本页三件事：
 * 1. 展示运行时目录的真实落点（不创建，只查）；
 * 2. 展示上游硬性要求的四个工具名与 Minis 实际工具名的映射——这是 403 的根因；
 * 3. 「运行自检」直接 execve nativeLibraryDir/libopencode.so，验证它真的可执行。
 *
 * 第 3 点是整条通道的地基：Android 10+ 只允许从 nativeLibraryDir 执行二进制，
 * 这一步若报 Permission denied，后面的 serve / SSE / 模型调用全都不成立。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeTokenSettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val layout = remember { OpencodeLayout(context.filesDir) }
    val configExists = remember { layout.configFile.exists() }
    val serviceExists = remember { layout.serviceFile.exists() }

    // 规格要求的唯一合法可执行目录
    val elf = remember {
        File(context.applicationInfo.nativeLibraryDir, "libopencode.so")
    }
    val elfPresent = remember { elf.exists() }
    val elfExecutable = remember { elf.canExecute() }

    val toolRows = remember { FreeTierTool.ALL }
    val sampleGrep = remember {
        ShellCommandBuilder.grep("TODO", path = "src", include = "kt", limit = 20)
    }
    val sampleGlob = remember {
        ShellCommandBuilder.glob("**/*.kt", path = "app")
    }

    var probing by remember { mutableStateOf(false) }
    var probeResult by remember { mutableStateOf<String?>(null) }
    var probeOk by remember { mutableStateOf(false) }

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
            // ---------- 原生二进制 ----------
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
                        text = "原生二进制",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    StatusLine(
                        label = "文件存在",
                        value = if (elfPresent) "是" else "否（未打进安装包）",
                        ok = elfPresent,
                    )
                    StatusLine(
                        label = "可执行位",
                        value = if (elfExecutable) "已就绪" else "无执行权限",
                        ok = elfExecutable,
                    )
                    StatusLine(
                        label = "路径",
                        value = elf.absolutePath,
                        ok = false,
                        mono = true,
                    )

                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = {
                            probing = true
                            probeResult = null
                            scope.launch {
                                val (ok, text) = withContext(Dispatchers.IO) {
                                    probeElf(elf)
                                }
                                probeOk = ok
                                probeResult = text
                                probing = false
                            }
                        },
                        enabled = !probing && elfPresent,
                    ) {
                        Text(if (probing) "检测中…" else "运行自检")
                    }

                    probeResult?.let { text ->
                        Spacer(Modifier.height(6.dp))
                        CommandBox(text, ok = probeOk)
                    }
                }
            }

            // ---------- 运行时目录 ----------
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
                        text = "运行时目录",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    StatusLine(label = "根目录", value = layout.root.absolutePath, ok = false, mono = true)
                    StatusLine(
                        label = "配置文件",
                        value = if (configExists) "已生成" else "未生成",
                        ok = configExists,
                    )
                    StatusLine(
                        label = "服务身份",
                        value = if (serviceExists) "已注册" else "未注册",
                        ok = serviceExists,
                    )
                }
            }

            // ---------- 工具名映射 ----------
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
                    CommandBox(sampleGlob, ok = true)
                    Spacer(Modifier.height(6.dp))
                    CommandBox(sampleGrep, ok = true)
                }
            }
        }
    }
}

/**
 * 执行 `libopencode.so --version`。
 *
 * 用绝对路径直接 execve，不经 shell——只有这样才能验证「二进制本身可执行」，
 * 而不是「bash 能跑」。这正是规格 §308 的判定条件。
 */
private fun probeElf(elf: File): Pair<Boolean, String> {
    if (!elf.exists()) return false to "文件不存在：${elf.absolutePath}"
    if (!elf.canExecute()) return false to "缺少执行权限：${elf.absolutePath}"
    return try {
        val process = ProcessBuilder(elf.absolutePath, "--version")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(25, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return false to "执行超时（25 秒未返回）"
        }
        val code = process.exitValue()
        val trimmed = output.trim().take(500)
        if (code == 0 && trimmed.isNotEmpty()) {
            true to "exit=0\n$trimmed"
        } else {
            false to "exit=$code\n$trimmed"
        }
    } catch (e: Exception) {
        false to "启动失败：${e.javaClass.simpleName}: ${e.message}"
    }
}

@Composable
private fun StatusLine(
    label: String,
    value: String,
    ok: Boolean,
    mono: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(76.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            color = if (ok) Color(0xFF32D74B) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CommandBox(text: String, ok: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = if (ok) Color(0xFF32D74B) else Color(0xFFFF9F0A),
        )
    }
}
