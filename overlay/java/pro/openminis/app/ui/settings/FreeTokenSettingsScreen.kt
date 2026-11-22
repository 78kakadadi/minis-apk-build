package pro.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import pro.openminis.app.ext.opencode.runtime.FreeTokenManager
import pro.openminis.app.ext.opencode.runtime.FreeTokenStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

private val OK_GREEN = Color(0xFF32D74B)
private val WARN_ORANGE = Color(0xFFFF9F0A)
private val ERR_RED = Color(0xFFFF453A)

/**
 * 免费 Token 通道设置页。
 *
 * 这个页面的职责只有一个：**让通道真的跑起来，并且在跑不起来时说清楚是哪一步**。
 * 早先版本只展示「文件是否存在」，用户点不到任何能产生请求的东西，于是看起来
 * 一切正常却完全没法用。现在页面顶部就是状态与开关，下面是可验证的实测对话。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeTokenSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { FreeTokenManager.get(context) }
    val status by manager.status.collectAsState()
    val models by manager.models.collectAsState()

    val scope = rememberCoroutineScope()
    var testInput by remember { mutableStateOf("用一句话介绍你自己") }
    var testOutput by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf(manager.defaultModel) }
    var confirmReinstall by remember { mutableStateOf(false) }

    // 用户上次启用过就自动拉起，否则停在「未启用」等一次明确点击
    LaunchedEffect(Unit) { manager.autoStartIfOptedIn() }

    // 重装/换模型后服务端会给回新的默认模型，选中态跟着状态走，
    // 否则列表里的高亮会停在重装前那个已经不存在的 id 上。
    LaunchedEffect(status) {
        val current = (status as? FreeTokenStatus.Running)?.model
        if (current != null) selectedModel = current
    }

    val running = status is FreeTokenStatus.Running
    val working = status is FreeTokenStatus.Working

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("免费 Token 通道") },
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------- 状态与开关 ----------
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "状态",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        StatusChip(status)
                    }

                    when (val s = status) {
                        is FreeTokenStatus.Off -> Text(
                            text = "未启用。启用后会在本机拉起 OpenCode 运行时，" +
                                "并把免费模型注册进 Minis 的模型列表。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        is FreeTokenStatus.Working -> {
                            Text(
                                text = "${s.step}（${s.progress}%）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        is FreeTokenStatus.Running -> {
                            Line("接入地址", s.baseUrl, mono = true, ok = true)
                            Line("当前模型", s.model ?: "未选择", mono = true, ok = s.model != null)
                        }
                        is FreeTokenStatus.Error -> {
                            Line("失败原因", s.message, ok = false)
                            if (!s.retryable) {
                                Text(
                                    text = "这条错误无法靠重试恢复，需要换一个包含运行时的安装包。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ERR_RED,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!running) {
                            Button(
                                onClick = { manager.enable() },
                                enabled = !working,
                            ) { Text(if (working) "启动中…" else "启用通道") }
                        } else {
                            Button(onClick = { manager.disable() }) { Text("停用") }
                        }
                        OutlinedButton(
                            onClick = { manager.restart() },
                            enabled = !working,
                        ) { Text("重启服务") }
                        OutlinedButton(
                            onClick = { manager.refreshModels() },
                            enabled = running,
                        ) { Text("刷新模型") }
                    }
                    if (running) {
                        TextButton(onClick = { manager.startNewConversation() }) {
                            Text("开始新对话（清空服务端上下文）")
                        }
                    }
                }
            }

            // ---------- 账号 ----------
            // 免费池额度按设备与网络计，登录自己的账号是应用内换额度线的唯一入口
            OpencodeAccountCard(manager)

            // ---------- 模型 ----------
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "免费模型（${models.size}）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "清单来自运行时实时的 /config/providers，每次刷新都可能变化。" +
                            "选中后作为默认模型使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (models.isEmpty()) {
                        Text(
                            text = if (running) "尚未发现免费模型，点上面的「刷新模型」。" else "启用通道后加载。",
                            style = MaterialTheme.typography.bodySmall,
                            color = WARN_ORANGE,
                        )
                    } else {
                        models.forEach { m ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FilterChip(
                                    selected = selectedModel == m.id,
                                    onClick = {
                                        selectedModel = m.id
                                        manager.selectModel(m.id)
                                    },
                                    label = { Text(m.label, fontSize = 12.sp) },
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = m.id,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // ---------- 实测 ----------
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "发一条真实请求",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "直接打到本机桥接的 /v1/chat/completions，绕过 UI 验证整条链路。" +
                            "这里成功，聊天里就一定成功。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = testInput,
                        onValueChange = { testInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("测试消息") },
                        minLines = 2,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            testing = true
                            testOutput = ""
                            val base = manager.bridgeBaseUrl
                            if (base == null) {
                                testOutput = "通道未运行，先点「启用通道」。"
                                testing = false
                            } else {
                                scope.launch {
                                    testOutput = withContext(Dispatchers.IO) {
                                        runTest(base, selectedModel ?: manager.defaultModel, testInput)
                                    }
                                    testing = false
                                }
                            }
                        },
                        enabled = !testing && running,
                    ) { Text(if (testing) "请求中…" else "发送") }

                    if (testOutput.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                text = testOutput,
                                modifier = Modifier.padding(10.dp),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = if (testOutput.startsWith("失败")) ERR_RED else OK_GREEN,
                            )
                        }
                    }
                }
            }

            // ---------- 运行时诊断 ----------
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val elf = remember { File(context.applicationInfo.nativeLibraryDir, "libopencode.so") }
                    Text(
                        text = "运行时诊断",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Line("二进制", if (elf.exists()) elf.absolutePath else "缺失（安装包未包含）", mono = true, ok = elf.exists())
                    Line("可执行", if (elf.canExecute()) "是" else "否", ok = elf.canExecute())
                    Line("上次端口", manager.lastPort().toString(), mono = true, ok = manager.lastPort() > 0)
                    Line("上游注册", "设置 › 模型 中查看「${FreeTokenManager.LABEL}」", ok = true)

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            showLogs = !showLogs
                            if (showLogs) logText = manager.logs()
                        }) { Text(if (showLogs) "收起日志" else "查看日志") }
                        OutlinedButton(onClick = {
                            scope.launch(Dispatchers.IO) { manager.probeNow() }
                        }) { Text("探活") }
                    }

                    // ---------- 重装 ----------
                    // 「重启服务」只在进程层面兜底；配置文件被写坏、会话库卡死、
                    // 端口/密码与 service.json 对不上这一类，重启多少次都没用，
                    // 必须把运行时目录整个换掉。
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { confirmReinstall = true },
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("重装 OpenCode 运行时")
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "会停止服务并删除本机的运行时配置、会话数据与日志，再重新初始化。" +
                            "可执行文件随安装包提供，重装不会重新下载它。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (showLogs) {
                        Spacer(Modifier.height(8.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                text = logText.ifBlank { "（暂无日志）" },
                                modifier = Modifier.padding(10.dp),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = Color(0xFFB0B0B0),
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmReinstall) {
        AlertDialog(
            onDismissRequest = { confirmReinstall = false },
            title = { Text("重装 OpenCode 运行时？") },
            text = {
                Text(
                    "会停止本地服务，删除本机的 OpenCode 配置、会话数据与日志，" +
                        "然后重新初始化并启动。\n\n" +
                        "可执行文件由安装包提供，重装不会重新下载它；" +
                        "如果它本身就缺失，只能重新安装这个 APK。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReinstall = false
                    manager.reinstall()
                }) { Text("重装", color = ERR_RED) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReinstall = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun StatusChip(status: FreeTokenStatus) {
    val (text, color) = when (status) {
        is FreeTokenStatus.Off -> "未启用" to Color(0xFF8E8E93)
        is FreeTokenStatus.Working -> "启动中" to WARN_ORANGE
        is FreeTokenStatus.Running -> "运行中" to OK_GREEN
        is FreeTokenStatus.Error -> "失败" to ERR_RED
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f)),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun Line(label: String, value: String, mono: Boolean = false, ok: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            modifier = Modifier.width(72.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            color = if (ok) OK_GREEN else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 真实请求：这是「通道是否可用」的唯一判据。 */
private fun runTest(baseUrl: String, model: String?, text: String): String {
    return try {
        val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
        val body = JSONObject()
            .put("model", model ?: "")
            .put("stream", false)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", text)),
            )
        val req = Request.Builder()
            .url("$baseUrl/chat/completions")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return "失败 HTTP ${resp.code}\n${raw.take(600)}"
            val content = runCatching {
                JSONObject(raw).optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
            }.getOrNull()
            if (content.isNullOrBlank()) "失败：响应里没有内容\n${raw.take(600)}" else content
        }
    } catch (e: Exception) {
        "失败 ${e.javaClass.simpleName}: ${e.message}"
    }
}
