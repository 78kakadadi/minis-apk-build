package pro.openminis.app.ui.opencode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import pro.openminis.app.ext.dispatch.AgentReportStatus
import pro.openminis.app.ext.dispatch.DispatchKind
import pro.openminis.app.ext.dispatch.MandateDecision
import pro.openminis.app.ext.dispatch.MandateGuard
import pro.openminis.app.ext.dispatch.TaskMandate
import pro.openminis.app.ext.dispatch.WaveClaim
import pro.openminis.app.ext.dispatch.WaveDeduplicator
import pro.openminis.app.ext.keypool.FailureAction
import pro.openminis.app.ext.keypool.KeyPool
import pro.openminis.app.ext.keypool.PooledKey
import pro.openminis.app.ext.opencode.memory.MemoryScope
import pro.openminis.app.ext.plugin.DependencyResolver
import pro.openminis.app.ext.plugin.ManifestValidator
import pro.openminis.app.ext.plugin.PluginLevel
import pro.openminis.app.ext.plugin.PluginPermission
import pro.openminis.app.ext.plugin.SlotRegistry
import pro.openminis.app.ext.plugin.SlotType
import pro.openminis.app.ext.plugin.ZipSlip

private val OK_GREEN = Color(0xFF32D74B)
private val WARN_ORANGE = Color(0xFFFF9F0A)
private val ERR_RED = Color(0xFFFF453A)

// ============================================================
// API Key 池
// ============================================================

/**
 * Key 池（规格 §7）。
 *
 * 演示区真的跑了 [KeyPool] 的加权轮询与冷却逻辑，显示的是算法实际输出，
 * 不是写死的示例文本。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeyPoolScreen(onBack: () -> Unit) {
    val pool = remember {
        KeyPool().apply {
            addKey(PooledKey("k1", "anthropic", "keystore:k1", weight = 1, label = "主账号"))
            addKey(PooledKey("k2", "anthropic", "keystore:k2", weight = 2, label = "备用"))
            addKey(PooledKey("k3", "anthropic", "keystore:k3", weight = 1, label = "低价池"))
        }
    }
    var pickSequence by remember { mutableStateOf<List<String>>(emptyList()) }
    var cooldownNote by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("API Key 池") },
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
            ExtCard(
                title = "池中的 Key",
                subtitle = "权重决定轮询比例；同一会话优先复用同一把以命中上游缓存",
            ) {
                remember(version) {
                    pool.keysOf("anthropic").map { k ->
                        k.label.ifBlank { k.id } to "权重 ${k.weight} · 冷却 ${cooldownText(pool, k.id)}"
                    }
                }.forEach { (label, value) ->
                    ExtRow(label = label, value = value)
                }
            }

            ExtCard(
                title = "加权轮询（真实调度）",
                subtitle = "点一次分配 6 次，观察权重 1:2:1 的分布",
            ) {
                Button(onClick = {
                    pickSequence = (1..6).map { pool.acquire("anthropic", "demo-$it-${System.nanoTime()}")?.id ?: "—" }
                    version += 1
                }) {
                    Text("分配 6 次")
                }
                Spacer(Modifier.height(10.dp))
                ExtCode(
                    text = if (pickSequence.isEmpty()) "（尚未分配）" else pickSequence.joinToString(" → "),
                    tint = if (pickSequence.isEmpty()) WARN_ORANGE else OK_GREEN,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        pool.onFailure("k1", 429)
                        cooldownNote = (pool.state("k1")).let { "k1 被限流，冷却 60 秒" }
                        version += 1
                    }) { Text("模拟 429") }
                    Button(onClick = {
                        pool.onFailure("k2", 402)
                        cooldownNote = "k2 额度耗尽，冷却 30 分钟"
                        version += 1
                    }) { Text("模拟 402") }
                    Button(onClick = {
                        val action = pool.onFailure("k3", 400)
                        cooldownNote = if (action is FailureAction.Keep) "400 不换 Key：${action.reason}" else "意外的换 Key"
                        version += 1
                    }) { Text("模拟 400") }
                }
                cooldownNote?.let {
                    Spacer(Modifier.height(8.dp))
                    ExtCode(it, tint = WARN_ORANGE)
                }
            }

            ExtCard(
                title = "差异化冷却规则",
                subtitle = "换 Key 与否取决于错误类型，一刀切会把额度浪费在必然失败的请求上",
            ) {
                ExtRow("429 限流", "冷却 60 秒，换 Key", valueColor = WARN_ORANGE)
                ExtRow("401 凭证失效", "冷却 5 分钟，换 Key", valueColor = WARN_ORANGE)
                ExtRow("402 额度耗尽", "冷却 30 分钟，换 Key", valueColor = ERR_RED)
                ExtRow("400 参数错误", "不换 Key", valueColor = OK_GREEN)
                ExtRow("403 内容被拒", "不换 Key", valueColor = OK_GREEN)
                ExtRow("网络异常", "不换 Key", valueColor = OK_GREEN)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Key 以 Keystore 引用标识存储，本模块不持有明文。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun cooldownText(pool: KeyPool, keyId: String): String {
    val until = pool.state(keyId).cooldownUntil
    if (until <= 0L) return "无"
    val remain = (until - System.currentTimeMillis()) / 1000
    return if (remain > 0) "剩余 ${remain}s" else "已到期，待探活"
}

// ============================================================
// 插件系统
// ============================================================

/**
 * 插件导入校验（规格 §8.4）。
 *
 * 页面上的校验器直接调 [ManifestValidator] 与 [ZipSlip]，
 * 输入什么就得到什么结果——用户可以用它验证自己的包为什么装不上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginScreen(onBack: () -> Unit) {
    var manifest by remember {
        mutableStateOf(
            """
            {
              "id": "com.example.myplugin",
              "name": "示例插件",
              "version": "1.0.0",
              "engines": { "minis": ">=1.0.0" },
              "clientEntry": "main.js",
              "permissions": ["network", "storage"],
              "slots": [
                { "id": "btn", "type": "chat.message.action", "label": "翻译" }
              ]
            }
            """.trimIndent(),
        )
    }
    var resultText by remember { mutableStateOf("") }
    var resultOk by remember { mutableStateOf(true) }

    var pathInput by remember { mutableStateOf("../etc/passwd") }
    var pathResult by remember { mutableStateOf("") }
    var pathOk by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("插件") },
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
            ExtCard(
                title = "清单校验",
                subtitle = "改一改下面的 JSON 再校验，能看到具体哪一项不通过",
            ) {
                OutlinedTextField(
                    value = manifest,
                    onValueChange = { manifest = it },
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    val r = ManifestValidator.validate(manifest, "1.5.0")
                    resultOk = r.ok
                    resultText = buildString {
                        if (r.ok) {
                            val m = r.manifest!!
                            append("通过：${m.name} ${m.version}\n")
                            append("级别：${m.level.riskLabel}\n")
                            append("权限：${m.permissions.joinToString("、") { it.readable }}\n")
                            append("插槽：${m.slots.joinToString("、") { it.type.readable }}")
                        } else {
                            r.issues.forEach { append("${it.field}：${it.reason}\n") }
                        }
                        r.warnings.forEach { append("\n警告：$it") }
                    }
                }) {
                    Text("校验")
                }
                if (resultText.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    ExtCode(resultText, tint = if (resultOk) OK_GREEN else ERR_RED)
                }
            }

            ExtCard(
                title = "包路径防护",
                subtitle = "zip-slip 是导入流程里唯一能把文件写到应用目录之外的漏洞",
            ) {
                OutlinedTextField(
                    value = pathInput,
                    onValueChange = { pathInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("包内条目路径") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val safe = ZipSlip.safeRelativePath(pathInput)
                        pathOk = safe != null
                        pathResult = if (safe != null) "放行，归一化为：$safe" else "拒绝：路径越界或为绝对路径"
                    }) { Text("检查") }
                    Button(onClick = { pathInput = "src/main.js" }) { Text("试个正常路径") }
                    Button(onClick = { pathInput = "a/../../b" }) { Text("试穿越") }
                }
                if (pathResult.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    ExtCode(pathResult, tint = if (pathOk) OK_GREEN else ERR_RED)
                }
            }

            ExtCard(
                title = "可撤销插槽",
                subtitle = "热重载时按插件批量撤销，Compose 树里不残留旧内容",
            ) {
                val registry = remember { SlotRegistry() }
                var count by remember { mutableStateOf(0) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        registry.register(
                            "com.example.myplugin",
                            pro.openminis.app.ext.plugin.DeclaredSlot("s1", SlotType.CHAT_MESSAGE_ACTION),
                            "内容$count",
                        )
                        count += 1
                    }) { Text("注册一个") }
                    Button(onClick = { registry.disposeAllOf("com.example.myplugin") }) { Text("全部撤销") }
                }
                Spacer(Modifier.height(8.dp))
                ExtRow("消息行按钮数", remember(count) { registry.contentsOf(SlotType.CHAT_MESSAGE_ACTION).size }.toString(), mono = true)
                ExtRow("设置区块数", remember(count) { registry.contentsOf(SlotType.SETTINGS_SECTION).size }.toString(), mono = true)
            }

            ExtCard(title = "权限说明示例") {
                PluginPermission.entries.take(5).forEach { p ->
                    ExtRow(p.wire, p.readable, mono = true, labelWidth = 100)
                }
                Spacer(Modifier.height(6.dp))
                ExtRow("级别", PluginLevel.L1.riskLabel, labelWidth = 100)
            }
        }
    }
}

// ============================================================
// 多 Agent 派发
// ============================================================

/**
 * 派发与授权令牌（规格 §5.1）。
 *
 * mandate 校验器是真的：选一个令牌、选一个工具，立刻得到允许或拒绝及原因。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DispatchScreen(onBack: () -> Unit) {
    var mandate by remember { mutableStateOf<TaskMandate>(TaskMandate.Explore) }
    var tool by remember { mutableStateOf("file_read") }
    var targetPath by remember { mutableStateOf("src/a.kt") }
    var decisionText by remember { mutableStateOf("") }
    var decisionOk by remember { mutableStateOf(true) }

    val dedup = remember { WaveDeduplicator() }
    var waveText by remember { mutableStateOf("") }
    var waveOk by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("多 Agent 派发") },
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
            ExtCard(
                title = "agent_report 六态",
                subtitle = "复用 Minis 既有状态，不新增语义；带横幅的三种必须让用户看见",
            ) {
                AgentReportStatus.entries.forEach { s ->
                    ExtRow(
                        label = s.wire,
                        value = if (s.needsBanner) "需要状态横幅" else "静默更新",
                        mono = true,
                        valueColor = if (s.needsBanner) WARN_ORANGE else OK_GREEN,
                    )
                }
            }

            ExtCard(
                title = "task_mandate 范围校验",
                subtitle = "子代理请求超出令牌范围时由宿主直接拒绝，不靠提示词自我约束",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        TaskMandate.Explore to "explore",
                        TaskMandate.Worker to "worker",
                        TaskMandate.Edit(listOf("src")) to "edit:src",
                    ).forEach { (m, label) ->
                        FilterChip(
                            selected = mandate.wire == m.wire,
                            onClick = { mandate = m },
                            label = { Text(label) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("file_read", "shell_execute", "file_write").forEach { t ->
                        FilterChip(
                            selected = tool == t,
                            onClick = { tool = t },
                            label = { Text(t) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = targetPath,
                    onValueChange = { targetPath = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("写操作目标路径") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    when (val d = MandateGuard.check(mandate, tool, targetPath)) {
                        is MandateDecision.Allowed -> {
                            decisionOk = true
                            decisionText = "允许：${mandate.wire} 下执行 $tool"
                        }
                        is MandateDecision.Denied -> {
                            decisionOk = false
                            decisionText = "拒绝：${d.reason}"
                        }
                    }
                }) { Text("校验") }
                if (decisionText.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    ExtCode(decisionText, tint = if (decisionOk) OK_GREEN else ERR_RED)
                }
            }

            ExtCard(
                title = "波次指纹去重",
                subtitle = "10 分钟内同种类同简报的重复派发会被拒绝，并返回已有句柄",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val claim = dedup.claim(
                            DispatchKind.DISPATCH,
                            listOf("梳理登录链路", "补单元测试"),
                        )
                        waveOk = claim is WaveClaim.Granted
                        waveText = when (claim) {
                            is WaveClaim.Granted -> "已授予句柄：${claim.handle}"
                            is WaveClaim.Duplicate ->
                                "重复派发被拒，复用已有句柄：${claim.existingHandle}（${claim.ageSeconds} 秒前）"
                        }
                    }) { Text("派发一次") }
                    Button(onClick = {
                        // 简报顺序颠倒，指纹应当相同
                        val claim = dedup.claim(
                            DispatchKind.DISPATCH,
                            listOf("补单元测试", "梳理登录链路"),
                        )
                        waveOk = claim is WaveClaim.Granted
                        waveText = when (claim) {
                            is WaveClaim.Granted -> "已授予句柄：${claim.handle}"
                            is WaveClaim.Duplicate ->
                                "顺序颠倒仍被识别为同一波次：${claim.existingHandle}"
                        }
                    }) { Text("顺序颠倒再派发") }
                }
                if (waveText.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    ExtCode(waveText, tint = if (waveOk) OK_GREEN else WARN_ORANGE)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "未完成批次会出现在泳道里并标注「不要重新派发」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
