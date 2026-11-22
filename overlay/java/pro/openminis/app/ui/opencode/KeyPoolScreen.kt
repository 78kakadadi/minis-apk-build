package pro.openminis.app.ui.opencode

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pro.openminis.app.ext.keypool.KeyPoolConfig
import pro.openminis.app.ext.keypool.KeyPoolRegistry
import pro.openminis.app.ext.keypool.KeyPoolStatus
import pro.openminis.app.ext.keypool.KeyPoolStore
import pro.openminis.app.ext.keypool.PooledKey

private val POOL_OK = Color(0xFF32D74B)
private val POOL_WARN = Color(0xFFFF9F0A)
private val POOL_ERR = Color(0xFFFF453A)

/**
 * API Key 号池：**一个上游地址 + 多把 Key**。
 *
 * 页面上所有操作都先落在内存里的 [KeyPoolConfig]，点「保存并启用」才写入磁盘
 * 并重建网关。这样用户改地址、加 Key、调权重时不会每敲一个字就重启一次服务，
 * 也避免了「改了一半就生效」的中间状态。
 *
 * 为什么需要「默认模型」：号池转发时会把请求里的 model 换成这个值。免费通道
 * 降级过来时带的是 `deepseek-v4.1-flash-free` 这种名字，你的中转站大概率不认识；
 * 留空则原样透传，适合「号池本身就是多 key 的同一家」的用法。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeyPoolScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { KeyPoolStore(context) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { KeyPoolRegistry.init(context) }
    val status by KeyPoolRegistry.status.collectAsState()

    var config by remember { mutableStateOf(store.load()) }
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var defaultModel by remember { mutableStateOf(config.defaultModel) }
    var enabled by remember { mutableStateOf(store.enabled) }

    // 新增但尚未保存的明文 Key：保存时一起写进加密区
    val pendingSecrets = remember { mutableStateMapOf<String, String>() }
    var removedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var dirty by remember { mutableStateOf(false) }

    var showAdd by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }

    // 冷却倒计时需要页面自己刷新，KeyPool 不会发通知
    LaunchedEffect(status) {
        if (status is KeyPoolStatus.Running) {
            while (true) {
                delay(1000)
                tick += 1
            }
        }
    }

    fun apply(updated: KeyPoolConfig) {
        config = updated
        dirty = true
    }

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
            StatusCard(status, enabled) { on ->
                enabled = on
                KeyPoolRegistry.setEnabled(on)
            }

            ExtCard(
                title = "上游",
                subtitle = "一个地址 + 多把 Key。地址填到 /v1 或根路径都可以。",
            ) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = {
                        baseUrl = it
                        dirty = true
                    },
                    label = { Text("上游地址") },
                    placeholder = { Text("https://api.example.com/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = defaultModel,
                    onValueChange = {
                        defaultModel = it
                        dirty = true
                    },
                    label = { Text("默认模型（可留空）") },
                    placeholder = { Text("例如 gpt-4o-mini / glm-4-flash") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "填了默认模型，所有请求都会改用它；留空则原样透传客户端传来的模型名。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ExtCard(
                title = "Key（${config.keys.size}）",
                subtitle = "按权重轮询；某把额度用尽会自动冷却并换下一把",
            ) {
                if (config.keys.isEmpty()) {
                    Text(
                        text = "还没有 Key。点下面的「添加 Key」，可以一次粘贴多行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                config.keys.forEach { key ->
                    KeyRow(
                        key = key,
                        masked = pendingSecrets[key.id]?.let { mask(it) } ?: store.maskedTail(key.id),
                        statusText = keyStatusText(key.id, tick),
                        onToggle = { on ->
                            apply(config.copy(keys = config.keys.map { if (it.id == key.id) it.copy(enabled = on) else it }))
                        },
                        onWeight = { w ->
                            apply(config.copy(keys = config.keys.map { if (it.id == key.id) it.copy(weight = w) else it }))
                        },
                        onDelete = {
                            apply(config.copy(keys = config.keys.filterNot { it.id == key.id }))
                            removedIds = removedIds + key.id
                        },
                        onTest = {
                            val secret = pendingSecrets[key.id] ?: store.secret(key.id).orEmpty()
                            scope.launch {
                                testResult = "测试中…"
                                val probe = config.copy(
                                    baseUrl = baseUrl,
                                    defaultModel = defaultModel,
                                    keys = listOf(key),
                                )
                                testResult = "「${key.label}」：" +
                                    KeyPoolRegistry.testConnection(probe, key.id, secret)
                            }
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                }

                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showAdd = true }) { Text("添加 Key") }
                    OutlinedButton(
                        onClick = {
                            val keys = config.keys
                            if (keys.isEmpty()) {
                                testResult = "先添加至少一把 Key"
                                return@OutlinedButton
                            }
                            val first = keys.first()
                            val secret = pendingSecrets[first.id] ?: store.secret(first.id).orEmpty()
                            scope.launch {
                                testResult = "测试中…"
                                testResult = KeyPoolRegistry.testConnection(
                                    config.copy(baseUrl = baseUrl, defaultModel = defaultModel),
                                    first.id,
                                    secret,
                                )
                            }
                        },
                    ) { Text("测试第一把") }
                }
                testResult?.let {
                    Spacer(Modifier.height(8.dp))
                    ExtCode(it, tint = if (it.contains("正常")) POOL_OK else POOL_WARN)
                }
            }

            Button(
                onClick = {
                    removedIds.forEach { store.removeKey(it) }
                    val merged = config.copy(
                        baseUrl = baseUrl.trim(),
                        defaultModel = defaultModel.trim(),
                    )
                    KeyPoolRegistry.save(merged, pendingSecrets.toMap())
                    config = merged
                    pendingSecrets.clear()
                    removedIds = emptySet()
                    dirty = false
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (dirty) "保存并启用" else "已保存")
            }

            ExtCard(
                title = "它在什么位置生效",
                subtitle = null,
            ) {
                ExtRow("普通对话", "设置 › 模型 →「API Key 号池」", valueColor = POOL_OK)
                ExtRow("群聊成员", "群设置 › 来源选「已存 API」", valueColor = POOL_OK)
                ExtRow("免费通道兜底", "免费额度用尽时自动接手", valueColor = POOL_OK)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Key 保存在系统 Keystore 加密的偏好里，日志与界面只显示尾号。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showAdd) {
        AddKeysDialog(
            onDismiss = { showAdd = false },
            onAdd = { lines ->
                val existing = config.keys.toMutableList()
                var added = 0
                lines.forEach { raw ->
                    val text = raw.trim()
                    if (text.isEmpty()) return@forEach
                    val eq = text.indexOf('=')
                    val label: String
                    val secret: String
                    if (eq in 1 until text.length - 1) {
                        label = text.substring(0, eq).trim()
                        secret = text.substring(eq + 1).trim()
                    } else {
                        label = ""
                        secret = text
                    }
                    if (secret.length < 8) return@forEach
                    val key = store.newKey(existing, label)
                    pendingSecrets[key.id] = secret
                    existing.add(key)
                    added++
                }
                apply(config.copy(keys = existing))
                showAdd = false
                if (added > 0) testResult = "已添加 $added 把 Key，记得点「保存并启用」"
            },
        )
    }
}

@Composable
private fun StatusCard(status: KeyPoolStatus, enabled: Boolean, onEnabled: (Boolean) -> Unit) {
    ExtCard(title = "状态", subtitle = null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when (status) {
                    is KeyPoolStatus.Running -> "运行中"
                    is KeyPoolStatus.Starting -> "启动中"
                    is KeyPoolStatus.Error -> "异常"
                    KeyPoolStatus.Off -> "未启用"
                },
                color = when (status) {
                    is KeyPoolStatus.Running -> POOL_OK
                    is KeyPoolStatus.Error -> POOL_ERR
                    else -> POOL_WARN
                },
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = when (val s = status) {
                    is KeyPoolStatus.Running -> "${s.keyCount} 把 Key，可用 ${s.available} 把"
                    is KeyPoolStatus.Starting -> s.step
                    is KeyPoolStatus.Error -> s.message
                    KeyPoolStatus.Off -> "填好地址和 Key 后保存即可"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = onEnabled)
        }
        if (status is KeyPoolStatus.Running) {
            Spacer(Modifier.height(6.dp))
            ExtRow("本地地址", status.baseUrl)
            ExtRow("当前模型", status.model.ifBlank { "（透传）" })
        }
    }
}

@Composable
private fun KeyRow(
    key: PooledKey,
    masked: String,
    statusText: String,
    onToggle: (Boolean) -> Unit,
    onWeight: (Int) -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = key.label.ifBlank { key.id },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "$masked · 权重 ${key.weight} · $statusText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = key.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = POOL_ERR)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(1, 2, 3).forEach { w ->
                TextButton(onClick = { onWeight(w) }) {
                    Text(
                        text = "权重 $w",
                        color = if (key.weight == w) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onTest) { Text("测试") }
        }
    }
}

@Composable
private fun AddKeysDialog(onDismiss: () -> Unit, onAdd: (List<String>) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 Key") },
        text = {
            Column {
                Text(
                    text = "一行一把，可写「备注=Key」。例如：\n主账号=sk-abc...\n备用=sk-def...",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Key 列表") },
                    minLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(text.lines()) }) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 冷却/健康状态的一行摘要。 */
private fun keyStatusText(keyId: String, tick: Int): String {
    val s = KeyPoolRegistry.keyState(keyId)
    val now = System.currentTimeMillis()
    if (s.cooldownUntil > now) {
        return "冷却 ${(s.cooldownUntil - now) / 1000}s"
    }
    return when {
        s.consecutiveFailures > 0 -> "连续失败 ${s.consecutiveFailures} 次"
        s.successCount > 0 -> "成功 ${s.successCount} 次"
        else -> "待使用"
    }
}

private fun mask(secret: String): String =
    if (secret.length <= 8) "••••" else "••••" + secret.takeLast(4)
