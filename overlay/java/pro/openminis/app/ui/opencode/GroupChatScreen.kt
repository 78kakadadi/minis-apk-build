package pro.openminis.app.ui.opencode

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pro.openminis.app.ext.group.AiAvatars
import pro.openminis.app.ext.group.AvatarSpec
import pro.openminis.app.ext.group.GroupChatEngine
import pro.openminis.app.ext.group.GroupDef
import pro.openminis.app.ext.group.GroupEvent
import pro.openminis.app.ext.group.GroupMemberDef
import pro.openminis.app.ext.group.GroupMessage
import pro.openminis.app.ext.group.GroupStore
import pro.openminis.app.ext.group.TurnStrategy
import pro.openminis.app.ext.opencode.runtime.FreeTokenManager
import pro.openminis.app.ext.opencode.runtime.GroupEndpointResolver
import pro.openminis.app.ext.opencode.runtime.ProviderChoice
import pro.openminis.app.ext.persona.PersonaFileStore
import pro.openminis.app.ext.persona.PersonaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private val GC_BLUE = Color(0xFF0A84FF)
private val GC_ORANGE = Color(0xFFFF9F0A)
private val GC_RED = Color(0xFFFF453A)

/**
 * 群聊。
 *
 * 界面按「对话页」的形态做：顶部是群名 + 成员头像，中间是消息流（成员带头像与名字），
 * 底部是输入框；群设置收进右上角的弹层里，不再把成员管理、策略、对话堆在一个滚动页里。
 *
 * 上下文规则见 [GroupChatEngine] 的注释：每个成员只看到「上一轮及更早」的最终答复，
 * 看不到同轮其他成员的回复，更看不到任何人的思考过程。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(
    onBack: () -> Unit,
    initialGroupId: String? = null,
) {
    val context = LocalContext.current
    val store = remember(context) { GroupStore(context) }
    val personas = remember(context) { PersonaRepository(PersonaFileStore(context)) }
    val manager = remember(context) { FreeTokenManager.get(context) }
    val freeModels by manager.models.collectAsState()

    val resolver = remember(context) {
        GroupEndpointResolver(
            context = context,
            freeBaseUrl = { manager.bridgeBaseUrl },
            freeModels = { manager.models.value },
        )
    }

    val http = remember {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()
    }
    val engine = remember { GroupChatEngine(store, personas, resolver, http) }

    var version by remember { mutableStateOf(0) }
    var currentId by remember { mutableStateOf(initialGroupId) }

    val groups = remember(version) { store.groups() }
    val group = remember(version, currentId) { currentId?.let { store.group(it) } }
    val members = remember(version, currentId) {
        currentId?.let { store.members(it) } ?: emptyList()
    }
    val messages = remember(version, currentId) {
        currentId?.let { store.messages(it) } ?: emptyList()
    }

    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var thinking by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var status by remember { mutableStateOf("") }
    var quote by remember { mutableStateOf<GroupMessage?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, pending.size) {
        val total = messages.size + pending.size
        if (total > 0) runCatching { listState.animateScrollToItem(total - 1) }
    }

    if (group == null) {
        GroupListPane(
            groups = groups,
            newGroupName = newGroupName,
            onNameChange = { newGroupName = it },
            onCreate = {
                val g = store.createGroup(newGroupName)
                newGroupName = ""
                version++
                currentId = g.id
            },
            onOpen = { currentId = it },
            onBack = onBack,
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { currentId = null; version++ }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回群列表")
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AvatarStack(members)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(group.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "${members.size} 位成员 · ${strategyLabel(group.strategy)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "群设置")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            if (members.isEmpty()) {
                EmptyMembersHint { showSettings = true }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp,
                ),
            ) {
                items(messages, key = { it.id }) { m ->
                    if (m.isUser) {
                        UserBubble(m)
                    } else {
                        MemberBubble(
                            message = m,
                            member = members.firstOrNull { it.id == m.memberId },
                        )
                    }
                }
                items(pending.keys.toList(), key = { "pending_$it" }) { memberId ->
                    val member = members.firstOrNull { it.id == memberId }
                    StreamingBubble(
                        name = member?.displayName?.ifBlank { "成员" } ?: "成员",
                        spec = member?.let { AiAvatars.resolve(it) }
                            ?: AvatarSpec("AI", 0xFF8E8E93.toInt()),
                        text = pending[memberId].orEmpty(),
                        thinking = thinking[memberId].orEmpty(),
                    )
                }
            }

            if (status.isNotBlank()) {
                Text(
                    text = status,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = GC_ORANGE,
                )
            }

            val sendGroupId = group.id
            Composer(
                value = input,
                onValueChange = { input = it },
                quote = quote,
                onClearQuote = { quote = null },
                busy = busy,
                bridgeReady = manager.bridgeBaseUrl != null || members.any {
                    it.source == GroupMemberDef.SOURCE_PROVIDER
                },
                onSend = {
                    val text = input.trim()
                    if (text.isNotEmpty() && !busy) {
                        input = ""
                        status = ""
                        busy = true
                        pending = emptyMap()
                        thinking = emptyMap()
                        val q = quote
                        quote = null
                        val gid = sendGroupId
                        scope.launch {
                            try {
                                engine.send(gid, text, q) { ev ->
                                    withContext(Dispatchers.Main) {
                                        when (ev) {
                                            is GroupEvent.MemberStarted ->
                                                pending = pending + (ev.memberId to "")
                                            is GroupEvent.MemberDelta ->
                                                pending = pending + (
                                                    ev.memberId to ((pending[ev.memberId] ?: "") + ev.text)
                                                    )
                                            is GroupEvent.MemberThinking ->
                                                thinking = thinking + (
                                                    ev.memberId to ((thinking[ev.memberId] ?: "") + ev.text)
                                                    )
                                            is GroupEvent.MemberFinished ->
                                                pending = pending - ev.memberId
                                            is GroupEvent.MemberSkipped -> {
                                                pending = pending - ev.memberId
                                                status = "${ev.name} 跳过了这轮：${ev.reason}"
                                            }
                                            is GroupEvent.MemberFailed -> {
                                                pending = pending - ev.memberId
                                                status = "${ev.name} 调用失败：${ev.reason}"
                                            }
                                            is GroupEvent.Notice -> status = ev.text
                                        }
                                    }
                                }
                            } finally {
                                withContext(Dispatchers.Main) {
                                    busy = false
                                    pending = emptyMap()
                                    thinking = emptyMap()
                                    version++
                                }
                            }
                        }
                    }
                },
            )
        }
    }

    if (showSettings) {
        GroupSettingsSheet(
            group = group,
            members = members,
            personas = personas,
            freeModels = freeModels.map { it.id to it.label },
            providerChoices = remember(showSettings, version) { resolver.providerChoices() },
            onDismiss = { showSettings = false },
            onChange = { version++ },
            onDeleteGroup = {
                store.deleteGroup(group.id)
                showSettings = false
                currentId = null
                version++
            },
        )
    }
}

// ─── 群列表 ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupListPane(
    groups: List<GroupDef>,
    newGroupName: String,
    onNameChange: (String) -> Unit,
    onCreate: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("群聊") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExtCard(title = "新建群聊", subtitle = "建好后从人设库添加成员，每个成员绑定一个模型") {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = onNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("群名称") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onCreate) { Text("创建") }
            }

            ExtCard(title = "我的群（${groups.size}）") {
                if (groups.isEmpty()) {
                    Text(
                        text = "还没有群。上面输入名称创建第一个，或者在会话列表长按新对话按钮。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                groups.forEach { g ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(g.id) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(g.name, fontWeight = FontWeight.Medium)
                            Text(
                                text = strategyLabel(g.strategy),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("进入", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

// ─── 消息 ───────────────────────────────────────────────────────────────────

@Composable
private fun UserBubble(message: GroupMessage) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.End,
    ) {
        message.quotePreview?.let {
            Text(
                text = "↩ ${it.take(60)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(color = GC_BLUE, shape = RoundedCornerShape(14.dp)) {
            Text(
                text = message.text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun MemberBubble(message: GroupMessage, member: GroupMemberDef?) {
    val spec = member?.let { AiAvatars.resolve(it) }
        ?: AvatarSpec(
            message.memberName?.let { AiAvatars.initialOf(it) } ?: "AI",
            0xFF8E8E93.toInt(),
        )
    Row(modifier = Modifier.padding(vertical = 6.dp)) {
        MemberAvatar(spec, 34.dp)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message.memberName ?: "成员",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color(spec.color),
                )
                Text(
                    text = "  " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (message.skipped) {
                    Text(
                        text = " · 已跳过",
                        style = MaterialTheme.typography.labelSmall,
                        color = GC_ORANGE,
                    )
                }
            }
            if (!message.thinking.isNullOrBlank()) {
                ThinkingBlock(message.thinking)
            }
            Surface(
                color = if (message.failed) GC_RED.copy(alpha = 0.10f)
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = message.text.ifBlank { "…" },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.failed) GC_RED else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun StreamingBubble(name: String, spec: AvatarSpec, text: String, thinking: String) {
    Row(modifier = Modifier.padding(vertical = 6.dp)) {
        MemberAvatar(spec, 34.dp)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color(spec.color),
                )
                Spacer(Modifier.width(6.dp))
                CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 1.5.dp)
            }
            if (thinking.isNotBlank()) ThinkingBlock(thinking)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = text.ifBlank { "…" },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** 思考过程默认折叠，只有本机用户能展开；它从不进入其他成员的上下文。 */
@Composable
private fun ThinkingBlock(text: String) {
    var expanded by remember(text.length) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = if (expanded) "▾ 思考过程（仅你可见）" else "▸ 思考过程（仅你可见）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (expanded) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, top = 2.dp, bottom = 4.dp),
            )
        }
    }
}

// ─── 输入框 ─────────────────────────────────────────────────────────────────

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    quote: GroupMessage?,
    onClearQuote: () -> Unit,
    busy: Boolean,
    bridgeReady: Boolean,
    onSend: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (!bridgeReady) {
                Text(
                    text = "免费通道未启用，成员会发不出消息：设置 › 免费 Token 通道",
                    style = MaterialTheme.typography.labelSmall,
                    color = GC_RED,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            quote?.let { q ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "引用 ${q.memberName ?: "消息"}：${q.text.take(40)}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onClearQuote) { Text("取消") }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("发消息给群成员…") },
                    maxLines = 5,
                    shape = RoundedCornerShape(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = onSend,
                    enabled = !busy && value.isNotBlank(),
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (busy || value.isBlank()) Color(0xFFB0B0B5) else GC_BLUE),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送", tint = Color.White)
                }
            }
            if (busy) {
                Text(
                    text = "成员发言中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

// ─── 头像 ───────────────────────────────────────────────────────────────────

@Composable
private fun MemberAvatar(spec: AvatarSpec, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(spec.color)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = spec.label,
            color = Color.White,
            fontSize = if (spec.label.length >= 3) (size.value / 4.4f).sp else (size.value / 3.2f).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun AvatarStack(members: List<GroupMemberDef>) {
    Row {
        members.take(3).forEachIndexed { index, m ->
            Box(
                modifier = Modifier
                    .padding(start = if (index == 0) 0.dp else (-8).dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color(AiAvatars.resolve(m).color)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = AiAvatars.resolve(m).label,
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun EmptyMembersHint(onAdd: () -> Unit) {
    Surface(
        color = GC_ORANGE.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "这个群还没有成员，先添加成员才能发言",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = GC_ORANGE,
            )
            TextButton(onClick = onAdd) { Text("添加") }
        }
    }
}

// ─── 群设置 ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupSettingsSheet(
    group: GroupDef,
    members: List<GroupMemberDef>,
    personas: PersonaRepository,
    freeModels: List<Pair<String, String>>,
    providerChoices: List<ProviderChoice>,
    onDismiss: () -> Unit,
    onChange: () -> Unit,
    onDeleteGroup: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { GroupStore(context) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var name by remember(group.id) { mutableStateOf(group.name) }
    var strategy by remember(group.id) { mutableStateOf(group.strategy) }
    var autoFit by remember(group.id) { mutableStateOf(group.autoTaskFit) }
    var editingAvatar by remember { mutableStateOf<GroupMemberDef?>(null) }
    var reassign by remember { mutableStateOf<GroupMemberDef?>(null) }

    var showAdd by remember { mutableStateOf(false) }
    var personaId by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf(GroupMemberDef.SOURCE_FREE) }
    var freeModel by remember { mutableStateOf("") }
    var providerInstance by remember { mutableStateOf("") }
    var providerModel by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf("") }

    val personaList = remember(group.id) { personas.listForUi() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(220.dp, 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("群设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("群名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    store.renameGroup(group.id, name)
                    onChange()
                }) { Text("保存名称") }
            }

            Text("发言策略", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                    TurnStrategy.ROUND_ROBIN to "轮询",
                    TurnStrategy.ASSIGN to "按顺序",
                    TurnStrategy.RACE to "竞速",
                ).forEach { (s, label) ->
                    FilterChip(
                        selected = strategy == s,
                        onClick = {
                            strategy = s
                            store.setStrategy(group.id, s)
                            onChange()
                        },
                        label = { Text(label, fontSize = 12.sp) },
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("自动判断任务是否适合自己", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "不适合的成员会跳过本轮，不占用发言名额",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoFit,
                    onCheckedChange = {
                        autoFit = it
                        store.setAutoTaskFit(group.id, it)
                        onChange()
                    },
                )
            }

            Text("成员（${members.size}）", style = MaterialTheme.typography.labelLarge)
            members.forEach { m ->
                val persona = personas.get(m.personaId)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.clickable { editingAvatar = m }) {
                                MemberAvatar(AiAvatars.resolve(m), 34.dp)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = m.displayName.ifBlank { persona?.name ?: "成员" },
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = modelLabel(m, providerChoices),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { reassign = m }) { Text("换模型") }
                            IconButton(onClick = {
                                store.removeMember(m.id)
                                onChange()
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "移除", tint = GC_RED)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("思考强度", style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.width(6.dp))
                            listOf("low" to "低", "medium" to "中", "high" to "高").forEach { (key, label) ->
                                FilterChip(
                                    selected = m.reasoning == key,
                                    onClick = {
                                        store.setMemberReasoning(m.id, key)
                                        onChange()
                                    },
                                    label = { Text(label, fontSize = 11.sp) },
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (showAdd) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("选择人设", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            personaList.take(12).forEach { p ->
                                FilterChip(
                                    selected = personaId == p.id,
                                    onClick = { personaId = p.id },
                                    label = { Text(p.name, fontSize = 11.sp) },
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("模型来源", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = source == GroupMemberDef.SOURCE_FREE,
                                onClick = { source = GroupMemberDef.SOURCE_FREE },
                                label = { Text("免费通道", fontSize = 11.sp) },
                            )
                            FilterChip(
                                selected = source == GroupMemberDef.SOURCE_PROVIDER,
                                onClick = { source = GroupMemberDef.SOURCE_PROVIDER },
                                label = { Text("我的 API", fontSize = 11.sp) },
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        if (source == GroupMemberDef.SOURCE_FREE) {
                            if (freeModels.isEmpty()) {
                                Text(
                                    text = "没有可用免费模型：设置 › 免费 Token 通道 启用后再来",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = GC_ORANGE,
                                )
                            }
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                freeModels.forEach { (id, label) ->
                                    FilterChip(
                                        selected = freeModel == id,
                                        onClick = { freeModel = id },
                                        label = { Text(label, fontSize = 11.sp) },
                                    )
                                }
                            }
                        } else {
                            if (providerChoices.isEmpty()) {
                                Text(
                                    text = "没有可用的已存 API（仅支持 OpenAI 兼容类型）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = GC_ORANGE,
                                )
                            }
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                providerChoices.forEach { pc ->
                                    FilterChip(
                                        selected = providerInstance == pc.instanceId,
                                        onClick = {
                                            providerInstance = pc.instanceId
                                            providerModel = pc.models.firstOrNull().orEmpty()
                                        },
                                        label = { Text(pc.label, fontSize = 11.sp) },
                                    )
                                }
                            }
                            val models = providerChoices
                                .firstOrNull { it.instanceId == providerInstance }
                                ?.models.orEmpty()
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                models.take(12).forEach { mid ->
                                    FilterChip(
                                        selected = providerModel == mid,
                                        onClick = { providerModel = mid },
                                        label = { Text(mid, fontSize = 11.sp) },
                                    )
                                }
                            }
                        }
                        if (addError.isNotBlank()) {
                            Text(addError, style = MaterialTheme.typography.labelSmall, color = GC_RED)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Button(onClick = {
                                val pid = personaId
                                val model = if (source == GroupMemberDef.SOURCE_FREE) {
                                    freeModel.ifBlank { freeModels.firstOrNull()?.first.orEmpty() }
                                } else providerModel
                                if (pid == null || model.isBlank()) {
                                    addError = "先选人设和模型"
                                } else {
                                    store.addMember(
                                        groupId = group.id,
                                        personaId = pid,
                                        modelId = model,
                                        displayName = personas.get(pid)?.name ?: "成员",
                                        source = source,
                                        providerInstanceId = if (source == GroupMemberDef.SOURCE_PROVIDER)
                                            providerInstance else "",
                                    )
                                    addError = ""
                                    showAdd = false
                                    personaId = null
                                    onChange()
                                }
                            }) { Text("确认添加") }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { showAdd = false }) { Text("取消") }
                        }
                    }
                }
            } else {
                OutlinedButton(onClick = { showAdd = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("添加成员")
                }
            }

            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onDeleteGroup) {
                Text("删除这个群", color = GC_RED)
            }
        }
    }

    editingAvatar?.let { m ->
        AvatarPickerDialog(
            member = m,
            onDismiss = { editingAvatar = null },
            onPick = { spec ->
                store.setMemberAvatar(m.id, spec.label, spec.color)
                editingAvatar = null
                onChange()
            },
        )
    }

    reassign?.let { m ->
        ReassignModelDialog(
            member = m,
            freeModels = freeModels,
            providerChoices = providerChoices,
            onDismiss = { reassign = null },
            onPick = { modelId, src, instanceId ->
                store.setMemberModel(m.id, modelId, src, instanceId)
                reassign = null
                onChange()
            },
        )
    }
}

@Composable
private fun AvatarPickerDialog(
    member: GroupMemberDef,
    onDismiss: () -> Unit,
    onPick: (AvatarSpec) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("换头像") },
        text = {
            Column {
                Text(
                    text = "默认按模型所属厂商取标识与配色；也可以在这里换一个。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AiAvatars.presets.forEach { spec ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(spec.color))
                                .clickable { onPick(spec) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = spec.label,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "当前：${member.displayName} · ${AiAvatars.resolve(member).label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun ReassignModelDialog(
    member: GroupMemberDef,
    freeModels: List<Pair<String, String>>,
    providerChoices: List<ProviderChoice>,
    onDismiss: () -> Unit,
    onPick: (modelId: String, source: String, instanceId: String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("换模型") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("免费通道", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    freeModels.forEach { (id, label) ->
                        FilterChip(
                            selected = member.source == GroupMemberDef.SOURCE_FREE && member.modelId == id,
                            onClick = { onPick(id, GroupMemberDef.SOURCE_FREE, "") },
                            label = { Text(label, fontSize = 11.sp) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("我的 API", style = MaterialTheme.typography.labelMedium)
                providerChoices.forEach { pc ->
                    Text(pc.label, style = MaterialTheme.typography.labelSmall, fontSize = 11.sp)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        pc.models.take(8).forEach { mid ->
                            FilterChip(
                                selected = member.source == GroupMemberDef.SOURCE_PROVIDER &&
                                    member.providerInstanceId == pc.instanceId && member.modelId == mid,
                                onClick = { onPick(mid, GroupMemberDef.SOURCE_PROVIDER, pc.instanceId) },
                                label = { Text(mid, fontSize = 11.sp) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

// ─── 工具 ───────────────────────────────────────────────────────────────────

internal fun strategyLabel(strategy: TurnStrategy): String = when (strategy) {
    TurnStrategy.ROUND_ROBIN -> "轮询"
    TurnStrategy.ASSIGN -> "按顺序"
    TurnStrategy.RACE -> "竞速"
}

private fun modelLabel(member: GroupMemberDef, choices: List<ProviderChoice>): String {
    if (member.source == GroupMemberDef.SOURCE_PROVIDER) {
        val label = choices.firstOrNull { it.instanceId == member.providerInstanceId }?.label ?: "已存的 API"
        return "$label · ${member.modelId}"
    }
    return "免费 · ${member.modelId}"
}
