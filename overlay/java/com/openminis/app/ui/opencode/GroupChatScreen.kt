package com.openminis.app.ui.opencode

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import com.openminis.app.ext.group.GroupChatEngine
import com.openminis.app.ext.group.GroupEvent
import com.openminis.app.ext.group.GroupMessage
import com.openminis.app.ext.group.GroupStore
import com.openminis.app.ext.group.TurnStrategy
import com.openminis.app.ext.opencode.runtime.FreeTokenManager
import com.openminis.app.ext.persona.PersonaFileStore
import com.openminis.app.ext.persona.PersonaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private val GC_GREEN = Color(0xFF32D74B)
private val GC_ORANGE = Color(0xFFFF9F0A)
private val GC_RED = Color(0xFFFF453A)

/**
 * 群聊。
 *
 * 三个层次从外到内：群列表 → 成员管理 → 对话。之前的版本把成员写死在代码里，
 * 用户既不能建群也不能加人，看起来"有页面但没有功能"。现在每一步都落盘。
 *
 * 模型来源只有一处：本机免费通道的桥接。这样群聊与单聊行为一致，
 * 也不会出现"选了模型但请求发不出去"的静默失败——未启用时直接在消息区提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { GroupStore(context) }
    val personas = remember { PersonaRepository(PersonaFileStore(context)) }
    val manager = remember { FreeTokenManager.get(context) }
    val freeModels by manager.models.collectAsState()

    val http = remember {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS)
            .build()
    }
    val engine = remember { GroupChatEngine(store, personas, { manager.bridgeBaseUrl }, http) }

    var groups by remember { mutableStateOf(store.groups()) }
    var currentId by remember { mutableStateOf<String?>(null) }
    var newGroupName by remember { mutableStateOf("") }
    var members by remember { mutableStateOf(emptyList<com.openminis.app.ext.group.GroupMemberDef>()) }
    var messages by remember { mutableStateOf(emptyList<GroupMessage>()) }

    var showAddMember by remember { mutableStateOf(false) }
    var pickedPersonaId by remember { mutableStateOf<String?>(null) }
    var pickedModel by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var quote by remember { mutableStateOf<GroupMessage?>(null) }

    val scope = rememberCoroutineScope()

    fun reload(groupId: String?) {
        members = groupId?.let { store.members(it) } ?: emptyList()
        messages = groupId?.let { store.messages(it) } ?: emptyList()
    }

    val group = groups.firstOrNull { it.id == currentId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(group?.name ?: "群聊") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentId != null) {
                            currentId = null
                            reload(null)
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (group != null) {
                        IconButton(onClick = {
                            store.deleteGroup(group.id)
                            groups = store.groups()
                            currentId = null
                            reload(null)
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除群")
                        }
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
            if (group == null) {
                // ---------- 群列表 ----------
                ExtCard(title = "新建群聊", subtitle = "建好后从人设库添加成员，每个成员绑定一个模型") {
                    OutlinedTextField(
                        value = newGroupName,
                        onValueChange = { newGroupName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("群名称") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        val g = store.createGroup(newGroupName)
                        newGroupName = ""
                        groups = store.groups()
                        currentId = g.id
                        reload(g.id)
                    }) { Text("创建") }
                }

                ExtCard(title = "我的群（${groups.size}）") {
                    if (groups.isEmpty()) {
                        Text(
                            text = "还没有群。上面输入名称创建第一个。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    groups.forEach { g ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(g.name, fontWeight = FontWeight.Medium)
                                Text(
                                    text = "${store.members(g.id).size} 位成员 · ${
                                        when (g.strategy) {
                                            TurnStrategy.ROUND_ROBIN -> "轮询"
                                            TurnStrategy.ASSIGN -> "指派"
                                            TurnStrategy.RACE -> "竞速"
                                        }
                                    }",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = {
                                currentId = g.id
                                reload(g.id)
                            }) { Text("进入") }
                        }
                    }
                }
            } else {
                // ---------- 成员 ----------
                ExtCard(
                    title = "成员（${members.size}）",
                    subtitle = if (members.isEmpty()) "先添加成员，否则无法发言" else "成员 = 人设 + 模型",
                ) {
                    members.forEach { m ->
                        val persona = personas.get(m.personaId)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = m.displayName.ifBlank { persona?.name ?: "成员" },
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "${persona?.name ?: "人设已删除"} · ${m.modelId}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = {
                                store.setMemberEnabled(m.id, !m.enabled)
                                reload(group.id)
                            }) { Text(if (m.enabled) "禁用" else "启用") }
                            TextButton(onClick = {
                                store.removeMember(m.id)
                                reload(group.id)
                            }) { Text("移除") }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showAddMember = !showAddMember }) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("添加成员")
                        }
                    }

                    if (showAddMember) {
                        Spacer(Modifier.height(8.dp))
                        Text("选择人设", style = MaterialTheme.typography.labelMedium)
                        personas.listForUi().take(10).forEach { p ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(
                                    selected = pickedPersonaId == p.id,
                                    onClick = { pickedPersonaId = p.id },
                                    label = { Text("${p.name}${if (p.isBuiltin) " · 内置" else ""}", fontSize = 12.sp) },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("绑定模型", style = MaterialTheme.typography.labelMedium)
                        if (freeModels.isEmpty()) {
                            Text(
                                text = "免费通道没有可用模型。先到「设置 › 免费 Token 通道」启用。",
                                style = MaterialTheme.typography.bodySmall,
                                color = GC_ORANGE,
                            )
                        } else {
                            freeModels.forEach { m ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    FilterChip(
                                        selected = pickedModel == m.id,
                                        onClick = { pickedModel = m.id },
                                        label = { Text(m.label, fontSize = 12.sp) },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val pid = pickedPersonaId
                                val model = pickedModel.ifBlank { freeModels.firstOrNull()?.id.orEmpty() }
                                if (pid == null || model.isBlank()) {
                                    status = "请先选人设和模型"
                                } else {
                                    val p = personas.get(pid)
                                    store.addMember(group.id, pid, model, p?.name ?: "成员")
                                    reload(group.id)
                                    showAddMember = false
                                    pickedPersonaId = null
                                    pickedModel = ""
                                    status = ""
                                }
                            },
                        ) { Text("确认添加") }
                    }
                }

                // ---------- 调度策略 ----------
                ExtCard(title = "调度策略", subtitle = "定向引用优先级最高，策略只决定其余成员的顺序") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            TurnStrategy.ROUND_ROBIN to "轮询",
                            TurnStrategy.ASSIGN to "指派（按成员顺序）",
                            TurnStrategy.RACE to "竞速",
                        ).forEach { (s, label) ->
                            FilterChip(
                                selected = group.strategy == s,
                                onClick = {
                                    store.setStrategy(group.id, s)
                                    groups = store.groups()
                                },
                                label = { Text(label, fontSize = 12.sp) },
                            )
                        }
                    }
                }

                // ---------- 对话 ----------
                ExtCard(title = "对话（${messages.size} 条）") {
                    if (messages.isEmpty()) {
                        Text(
                            text = "还没有消息。在下面说点什么，成员会按策略依次回应。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    messages.takeLast(40).forEach { m ->
                        MessageRow(m, onQuote = { quote = m })
                    }
                    pending.forEach { (memberId, text) ->
                        val name = members.firstOrNull { it.id == memberId }?.displayName ?: "成员"
                        MessageBubble(
                            name = name,
                            text = text,
                            color = GC_GREEN,
                            isUser = false,
                        )
                    }
                    if (status.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(status, style = MaterialTheme.typography.bodySmall, color = GC_ORANGE)
                    }
                }

                // ---------- 输入 ----------
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        quote?.let { q ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "引用 ${q.memberName ?: "消息"}：${q.text.take(40)}",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = { quote = null }) { Text("取消") }
                            }
                        }
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("说点什么") },
                            minLines = 2,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !busy,
                                onClick = {
                                    val text = input.trim()
                                    if (text.isEmpty()) return@Button
                                    input = ""
                                    status = ""
                                    busy = true
                                    pending = emptyMap()
                                    val q = quote
                                    quote = null
                                    val gid = group.id
                                    scope.launch {
                                        engine.send(gid, text, q) { ev ->
                                            withContext(Dispatchers.Main) {
                                                when (ev) {
                                                    is GroupEvent.MemberStarted ->
                                                        pending = pending + (ev.memberId to "")
                                                    is GroupEvent.MemberDelta ->
                                                        pending = pending + (ev.memberId to ((pending[ev.memberId] ?: "") + ev.text))
                                                    is GroupEvent.MemberFinished ->
                                                        pending = pending - ev.memberId
                                                    is GroupEvent.MemberFailed -> {
                                                        pending = pending - ev.memberId
                                                        status = "${ev.name} 调用失败：${ev.reason}"
                                                    }
                                                    is GroupEvent.Notice -> status = ev.text
                                                }
                                            }
                                        }
                                        withContext(Dispatchers.Main) {
                                            busy = false
                                            pending = emptyMap()
                                            reload(gid)
                                        }
                                    }
                                },
                            ) { Text(if (busy) "成员发言中…" else "发送") }

                            if (busy) {
                                OutlinedButton(onClick = {
                                    busy = false
                                    pending = emptyMap()
                                    reload(group.id)
                                }) { Text("停止显示") }
                            }
                        }
                        if (manager.bridgeBaseUrl == null) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "免费通道未启用：成员回复会失败。去「设置 › 免费 Token 通道」启用。",
                                style = MaterialTheme.typography.bodySmall,
                                color = GC_RED,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageRow(message: GroupMessage, onQuote: () -> Unit) {
    MessageBubble(
        name = message.memberName ?: "我",
        text = message.text,
        color = if (message.failed) GC_RED else if (message.isUser) Color(0xFF0A84FF) else Color(0xFF8E8E93),
        isUser = message.isUser,
        quotePreview = message.quotePreview,
        onQuote = onQuote,
    )
}

@Composable
private fun MessageBubble(
    name: String,
    text: String,
    color: Color,
    isUser: Boolean,
    quotePreview: String? = null,
    onQuote: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = " " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onQuote != null && !isUser) {
                TextButton(onClick = onQuote) { Text("引用", fontSize = 11.sp) }
            }
        }
        if (!quotePreview.isNullOrBlank()) {
            Text(
                text = "↩ $quotePreview",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(
                text = text.ifBlank { "…" },
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
