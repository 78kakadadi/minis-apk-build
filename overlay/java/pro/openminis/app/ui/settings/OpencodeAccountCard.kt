package pro.openminis.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.openminis.app.ext.opencode.account.DeviceLogin
import pro.openminis.app.ext.opencode.account.OpencodeAccount
import pro.openminis.app.ext.opencode.runtime.FreeTokenManager
import pro.openminis.app.ext.opencode.runtime.FreeTokenStatus

private val ACC_OK = Color(0xFF32D74B)
private val ACC_WARN = Color(0xFFFF9F0A)
private val ACC_ERR = Color(0xFFFF453A)

/**
 * OpenCode 账号卡片。
 *
 * 为什么要做这个：免费池的额度按**设备与网络**计，用尽后清缓存、重装运行时都
 * 不会恢复；唯一能在应用内换一条额度线的办法，是登录自己的账号（另一条是
 * 在「API Key 池」里填自己的上游 Key）。所以这里给两条登录路径：
 *
 *  - 浏览器设备码：不用抄 Key，授权完自动落库；
 *  - API Key：给「已经在 opencode.ai/auth 生成过 Key」或浏览器打不开的场景。
 *
 * 登录状态**不缓存本地**：凭据只存在运行时自己的库里，页面每次进来重新问一次，
 * 免得出现「界面说已登录、实际请求还在匿名」的分裂。
 */
@Composable
fun OpencodeAccountCard(manager: FreeTokenManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by manager.status.collectAsState()
    val running = status is FreeTokenStatus.Running
    val working = status is FreeTokenStatus.Working
    val off = status is FreeTokenStatus.Off || status is FreeTokenStatus.Error

    var accounts by remember { mutableStateOf<List<OpencodeAccount>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var device by remember { mutableStateOf<DeviceLogin?>(null) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var logoutTarget by remember { mutableStateOf<OpencodeAccount?>(null) }

    suspend fun reload() {
        val result = withContext(Dispatchers.IO) {
            runCatching { manager.account.list() }
        }
        result.onSuccess {
            accounts = it
            loadError = null
        }.onFailure {
            accounts = emptyList()
            loadError = it.message ?: "读取账号失败"
        }
    }

    /**
     * 登录要借用本地运行时的凭据接口，所以必须先有服务在跑。
     *
     * 之前按钮只在 Running 时可点，通道没启用时干脆整排隐藏——用户进页面只看到
     * 一段说明文字，看起来就是「没有登录按钮」。现在点按钮自己把通道拉起来。
     */
    suspend fun ensureRunning(): Boolean {
        if (manager.status.value is FreeTokenStatus.Running) return true
        if (manager.status.value !is FreeTokenStatus.Working) {
            withContext(Dispatchers.IO) { runCatching { manager.enable() } }
        }
        // 首次启动要准备运行时目录、建服务、等端口就绪，给足 90 秒
        repeat(180) {
            delay(500)
            when (manager.status.value) {
                is FreeTokenStatus.Running -> return true
                is FreeTokenStatus.Error -> return false
                else -> Unit
            }
        }
        return manager.status.value is FreeTokenStatus.Running
    }

    // 只在「真正可请求」时拉账号；刷新模型期间状态会短暂变成 Working，
    // 这时保留上一轮结果，否则界面会闪一下「匿名使用」。
    LaunchedEffect(running, off) {
        when {
            running -> reload()
            off -> {
                accounts = emptyList()
                loadError = null
            }
        }
    }

    // 设备码登录：轮询到终态就关弹窗。页面被切走时协程随之取消，不会留下后台轮询。
    LaunchedEffect(device?.attemptId) {
        val attempt = device ?: return@LaunchedEffect
        while (true) {
            delay(2_500)
            if (attempt.expiresAt > 0 && System.currentTimeMillis() > attempt.expiresAt) {
                notice = "验证码已过期，重新发起一次登录。"
                device = null
                break
            }
            val result = withContext(Dispatchers.IO) {
                runCatching { manager.account.poll(attempt.attemptId) }
            }
            val state = result.getOrElse { "pending" }
            if (state != "pending") {
                var saved = withContext(Dispatchers.IO) {
                    runCatching { manager.account.list() }
                }.getOrDefault(emptyList())
                // 授权刚完成时凭据可能还没写完，再问一次，别把成功显示成失败
                if (saved.none { it.active }) {
                    delay(800)
                    saved = withContext(Dispatchers.IO) {
                        runCatching { manager.account.list() }
                    }.getOrDefault(saved)
                }
                accounts = saved
                device = null
                val active = saved.firstOrNull { it.active }
                if (active != null) {
                    notice = "已登录：${active.label}。正在按账号重启运行时并重新注册模型…"
                    // 凭据在运行时启动时才组装进 provider，光刷新清单不够；
                    // 重启后还会重新走模型发现与 provider 注册，选择器里才会真的变。
                    manager.restart()
                } else {
                    notice = "授权没有完成（状态：$state），可以再试一次或改用 API Key 登录。"
                }
                break
            }
        }
    }

    // 拿到链接就先打开浏览器；失败也不拦着，弹窗里有地址和复制按钮。
    LaunchedEffect(device?.attemptId) {
        device?.let { openUrl(context, it.url) }
    }

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
                    text = "OpenCode 账号",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                val active = accounts?.firstOrNull { it.active }
                Text(
                    text = when {
                        working -> "启动中…"
                        !running -> "未启用"
                        accounts == null -> "读取中…"
                        active != null -> "已登录"
                        else -> "匿名使用"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        !running -> ACC_WARN
                        active != null -> ACC_OK
                        else -> ACC_WARN
                    },
                )
            }

            Text(
                text = "匿名状态下免费池的额度按设备和网络计，用尽后清缓存、重装运行时都不恢复。" +
                    "登录后改用你自己账号的额度与订阅，模型清单也会按账号重新刷新。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val error = loadError
            if (error != null) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = ACC_ERR)
            }

            if (!running && !working) {
                Text(
                    text = "通道还没启用：点下面的登录按钮会自动把它启动起来，不用先去上面点「启用通道」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val list = accounts.orEmpty()
            if (list.isNotEmpty()) {
                list.forEach { acc ->
                    AccountRow(
                        account = acc,
                        busy = busy != null,
                        onSwitch = {
                            scope.launch {
                                busy = "switch"
                                val r = withContext(Dispatchers.IO) {
                                    runCatching { manager.account.activate(acc.id) }
                                }
                                busy = null
                                r.onSuccess {
                                    notice = "已切换到：${acc.label}，正在按账号重启运行时…"
                                    manager.restart()
                                }.onFailure {
                                    notice = "切换失败：${it.message}"
                                }
                                reload()
                            }
                        },
                        onLogout = { logoutTarget = acc },
                    )
                }
                Spacer(Modifier.height(2.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        busy = "device"
                        notice = null
                        scope.launch {
                            if (!ensureRunning()) {
                                busy = null
                                notice = "本地运行时没启动起来，看页面上方状态卡里的失败原因。"
                                return@launch
                            }
                            val r = withContext(Dispatchers.IO) {
                                runCatching { manager.account.startDeviceLogin() }
                            }
                            busy = null
                            r.onSuccess { device = it }
                                .onFailure { notice = "发起登录失败：${it.message}" }
                        }
                    },
                    enabled = busy == null,
                ) { Text(if (busy == "device") "启动中…" else "登录 OpenCode 账号") }

                OutlinedButton(
                    onClick = {
                        notice = null
                        scope.launch {
                            busy = "key"
                            val ready = ensureRunning()
                            busy = null
                            if (ready) {
                                showKeyDialog = true
                            } else {
                                notice = "本地运行时没启动起来，看页面上方状态卡里的失败原因。"
                            }
                        }
                    },
                    enabled = busy == null,
                ) { Text("用 API Key 登录") }
            }

            if (list.isEmpty() && (running || working)) {
                Text(
                    text = "提示：登录本身不会凭空多出免费额度。账号没有订阅时，免费池仍按账号额度计；" +
                        "要立刻不受限，用「API Key 池」挂自己的 Key 更稳。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = ACC_OK)
            }
        }
    }

    device?.let { attempt ->
        DeviceLoginDialog(
            attempt = attempt,
            onOpen = { openUrl(context, attempt.url) },
            onCopy = {
                copyText(context, attempt.userCode.ifBlank { attempt.url }, "OpenCode 验证码")
            },
            onCancel = {
                val id = attempt.attemptId
                device = null
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { manager.account.cancel(id) } }
                }
            },
        )
    }

    if (showKeyDialog) {
        KeyLoginDialog(
            onDismiss = { showKeyDialog = false },
            onSubmit = { key, label ->
                showKeyDialog = false
                busy = "key"
                notice = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching { manager.account.loginWithKey(key, label) }
                    }
                    busy = null
                    r.onSuccess { warning ->
                        notice = warning ?: "已登录。正在按账号重启运行时并重新注册模型…"
                        // 先把账号列表读出来（此时服务还在），再重启
                        reload()
                        manager.restart()
                    }.onFailure {
                        notice = "登录失败：${it.message}"
                    }
                }
            },
        )
    }

    logoutTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { logoutTarget = null },
            title = { Text("退出这个账号？") },
            text = {
                Text(
                    "退出后回到匿名状态，免费池额度重新按设备和网络计。" +
                        "已保存的 API Key 号池不受影响。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    logoutTarget = null
                    busy = "logout"
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching { manager.account.logout(target.id) }
                        }
                        busy = null
                        r.onSuccess { notice = "已退出：${target.label}，正在重启运行时…" }
                            .onFailure { notice = "退出失败：${it.message}" }
                        reload()
                        manager.restart()
                    }
                }) { Text("退出登录", color = ACC_ERR) }
            },
            dismissButton = { TextButton(onClick = { logoutTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun AccountRow(
    account: OpencodeAccount,
    busy: Boolean,
    onSwitch: () -> Unit,
    onLogout: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = account.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (account.active) {
                    Text(
                        text = "  · 当前",
                        style = MaterialTheme.typography.labelSmall,
                        color = ACC_OK,
                    )
                }
            }
            Text(
                text = buildString {
                    if (account.keyTail != null) append("Key ••••").append(account.keyTail).append(" · ")
                    append(if (account.method == "oauth") "浏览器授权" else "API Key")
                },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!account.active) {
            TextButton(onClick = onSwitch, enabled = !busy) { Text("切换") }
        }
        TextButton(onClick = onLogout, enabled = !busy) {
            Text("退出", color = ACC_ERR)
        }
    }
}

@Composable
private fun DeviceLoginDialog(
    attempt: DeviceLogin,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onCancel: () -> Unit,
) {
    var now by remember(attempt.attemptId) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(attempt.attemptId) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val remain = if (attempt.expiresAt <= 0) "" else {
        val left = ((attempt.expiresAt - now) / 1000).coerceAtLeast(0)
        "· ${left / 60}:${(left % 60).toString().padStart(2, '0')} 后失效"
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("登录 OpenCode 账号") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "已在浏览器打开授权页。页面里填入下面的验证码，授权完成后这里会自动关闭。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = attempt.userCode.ifBlank { "打开链接后按页面提示操作" },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF32D74B),
                        )
                        Text(
                            text = attempt.url,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = Color(0xFF8E8E93),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        text = "   等待授权… $remain",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onCopy) { Text("复制验证码 / 链接") }
            }
        },
        confirmButton = { TextButton(onClick = onOpen) { Text("打开浏览器") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}

@Composable
private fun KeyLoginDialog(
    onDismiss: () -> Unit,
    onSubmit: (String, String?) -> Unit,
) {
    var key by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("用 API Key 登录") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "在 opencode.ai/auth 生成 API Key 后粘贴到这里。Key 只写入本地运行时的凭据库，" +
                        "不会上传到别处；界面只显示尾号。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("备注（可选）") },
                    placeholder = { Text("例如：主账号") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(key, label.ifBlank { null }) },
                enabled = key.trim().length >= 8,
            ) { Text("登录") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun copyText(context: Context, text: String, label: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
