package pro.openminis.app.ui.opencode

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pro.openminis.app.MinisApp
import pro.openminis.app.ext.group.AiAvatars
import pro.openminis.app.ext.group.AvatarSpec
import pro.openminis.app.ext.group.GroupAttachment
import pro.openminis.app.ext.group.GroupChatEngine
import pro.openminis.app.ext.group.GroupChatJobs
import pro.openminis.app.ext.group.GroupDef
import pro.openminis.app.ext.group.GroupEvent
import pro.openminis.app.ext.group.GroupMemberDef
import pro.openminis.app.ext.group.GroupMessage
import pro.openminis.app.ext.group.GroupSkill
import pro.openminis.app.ext.group.GroupSkillSource
import pro.openminis.app.ext.group.GroupSkills
import pro.openminis.app.ext.group.GroupStore
import pro.openminis.app.ext.group.GroupTools
import pro.openminis.app.ext.group.TurnStrategy
import pro.openminis.app.ext.opencode.runtime.FreeTokenManager
import pro.openminis.app.ext.opencode.runtime.GroupEndpointResolver
import pro.openminis.app.ext.opencode.runtime.ProviderChoice
import pro.openminis.app.ext.persona.PersonaFileStore
import pro.openminis.app.ext.persona.PersonaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
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
    // 群聊任务现在跑在进程级 scope（GroupChatJobs）里，可能比这个界面活得久，
    // 所以这里一律用 applicationContext，避免把 Activity 泄漏出去。
    val appContext = remember(context) { context.applicationContext }
    val store = remember(appContext) { GroupStore(appContext) }
    val personas = remember(appContext) { PersonaRepository(PersonaFileStore(appContext)) }
    val manager = remember(appContext) { FreeTokenManager.get(appContext) }
    val freeModels by manager.models.collectAsState()

    val resolver = remember(appContext) {
        GroupEndpointResolver(
            context = appContext,
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
    val engine = remember {
        GroupChatEngine(
            store = store,
            personas = personas,
            resolver = resolver,
            http = http,
            // 与免费通道共用同一个工作区：两种来源的成员看到的是同一批文件
            workspace = File(appContext.filesDir, "opencode/workspace"),
            // 每次发言现场问一次技能仓库：技能随时可能在设置里被启停，
            // 缓存一份反而会拿到过期的列表。
            skills = GroupSkillSource { availableSkills(appContext) },
            // 图片压成 JPEG 再 base64；不压缩的话一张 4K 照片就能让请求体超限。
            encodeImage = { file -> encodeImageForRequest(file) },
        )
    }

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
    // 任务在进程级 scope 里跑，界面的按钮状态以「任务还活着吗」为准，初始值也照此取：
    // 从别处切回来时如果还在生成，一进来就该是停止键。
    var busy by remember { mutableStateOf(currentId?.let { GroupChatJobs.isRunning(it) } == true) }
    var attachments by remember { mutableStateOf<List<GroupAttachment>>(emptyList()) }
    var pending by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var thinking by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var status by remember { mutableStateOf("") }
    // 每一轮发送一个编号：旧任务的 finally 只能清理自己那一轮的状态。
    // 否则「停止后立刻再发一条」时，上一轮的收尾会把新一轮的流式气泡一起清掉。
    var runToken by remember { mutableStateOf(0) }
    var quote by remember { mutableStateOf<GroupMessage?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }
    // 磁盘上的流式草稿：界面里已经有活跃流式气泡（pending 里有这个成员）时
    // 隐藏草稿，否则同一条回复会出现两次；从别处切回来时 pending 是空的，
    // 草稿就顶上来显示「已经输出的部分」。
    val visibleMessages = messages.filter { !it.streaming || pending[it.memberId] == null }
    // send 与重试共用同一套事件处理：成员消息进流式气泡，失败写状态条
    val handleEvent: suspend (GroupEvent) -> Unit = { ev ->
        kotlinx.coroutines.withContext(Dispatchers.Main) {
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
                is GroupEvent.MemberFinished -> {
                    // 先落盘再发事件，所以这里直接刷新就能看到这条最终答复，
                    // 不用等整轮（多个成员）全说完。
                    pending = pending - ev.memberId
                    version++
                }
                is GroupEvent.MemberSkipped -> {
                    pending = pending - ev.memberId
                    status = "${ev.name} 跳过了这轮：${ev.reason}"
                    version++
                }
                is GroupEvent.MemberFailed -> {
                    pending = pending - ev.memberId
                    status = "${ev.name} 调用失败：${ev.reason}"
                    version++
                }
                is GroupEvent.UserMessageAdded ->
                    // 用户消息已落盘：立刻刷新，人一发送就能看到自己那条
                    version++
                is GroupEvent.Notice -> status = ev.text
            }
        }
    }

    val listState = rememberLazyListState()

    // 一直「照镜子」：按钮状态与刷新都由进程级任务的真实状态驱动。
    //
    // 以前 busy 全靠每次发送自己 finally 去清，有两个坑：
    //  - 上一轮被取消后 finally 还没跑完，新一轮已经开始，finally 会把停止键
    //    错设回发送键，于是「正输出时按不到停止」；
    //  - 停止时任务卡在落盘上，界面就一直转圈，看起来像「暂停没生效」。
    // 现在只看 isRunning：取消后最多 400ms 内一定回到可输入状态。
    LaunchedEffect(currentId) {
        val gid = currentId ?: return@LaunchedEffect
        while (true) {
            val running = GroupChatJobs.isRunning(gid)
            if (running != busy) {
                busy = running
                if (!running) {
                    // 任务刚结束：清掉流式气泡并刷新一次。就算这一轮一个字都没输出
                    // （比如一开始就被打断），也要刷 —— 打断时的半截消息已经落盘了。
                    pending = emptyMap()
                    thinking = emptyMap()
                    version++
                }
            }
            if (running) {
                // 生成中跟着磁盘上的草稿刷新（退出再回来也能看到进度）
                version++
                delay(700)
            } else {
                delay(400)
            }
        }
    }

    // 选图片 / 选文件：复制进应用私有目录，群聊记录不依赖相册里那张原图。
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val gid = currentId
        if (uri != null && gid != null) {
            store.importAttachment(gid, uri)
                .onSuccess { attachments = attachments + it }
                .onFailure { status = "添加图片失败：${it.message}" }
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val gid = currentId
        if (uri != null && gid != null) {
            store.importAttachment(gid, uri)
                .onSuccess { attachments = attachments + it }
                .onFailure { status = "添加文件失败：${it.message}" }
        }
    }

    // 自动跟随只在用户本来就在底部时生效：输出过程中往上翻看历史，不会被强行拽回底部
    LaunchedEffect(visibleMessages.size, pending.size, pending.values.sumOf { it.length }) {
        val total = visibleMessages.size + pending.size
        if (total > 0 && !listState.canScrollForward) {
            runCatching { listState.animateScrollToItem(total - 1) }
        }
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
                    // 返回 = 直接回上一页（主页）。以前先回到群列表，用户要按两次
                    // 才能退出——从主页进来的人只想一键回到主页。群列表仍可从
                    // 长按新对话按钮的入口进入。
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                items(visibleMessages, key = { it.id }) { m ->
                    if (m.isUser) {
                        UserBubble(m)
                    } else {
                        val retryGroupId = group.id
                        MemberBubble(
                            message = m,
                            member = members.firstOrNull { it.id == m.memberId },
                            onRetry = if (m.failed && m.memberId != null && !busy) ({
                                {
                                    status = ""
                                    busy = true
                                    val mid = m.memberId
                                    if (mid != null) {
                                        GroupChatJobs.launch(retryGroupId) {
                                            // 以前这里只有 finally：重试真要抛异常（比如端点 400），
                                            // 界面什么都不会显示，看起来就是「按钮点了没反应」。
                                            try {
                                                engine.retryMember(retryGroupId, mid, handleEvent)
                                            } catch (e: kotlinx.coroutines.CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                withContext(Dispatchers.Main) {
                                                    status = "重试失败：${e.message ?: e.javaClass.simpleName}"
                                                }
                                            }
                                        }
                                    }
                                }
                            }) else null,
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
                attachments = attachments,
                onRemoveAttachment = { a -> attachments = attachments.filterNot { it.id == a.id } },
                onPickImage = { pickImage.launch("image/*") },
                onPickFile = { pickFile.launch("*/*") },
                busy = busy,
                bridgeReady = manager.bridgeBaseUrl != null || members.any {
                    it.source == GroupMemberDef.SOURCE_PROVIDER
                },
                onStop = {
                    // 只管发取消信号：界面状态由上面那个轮询照镜子清，
                    // 这里先给一句反馈，用户能看到「点了真有反应」。
                    GroupChatJobs.cancel(sendGroupId)
                    status = "已停止"
                },
                onSend = {
                    val text = input.trim()
                    val files = attachments
                    if ((text.isNotEmpty() || files.isNotEmpty()) && !busy) {
                        input = ""
                        attachments = emptyList()
                        status = ""
                        busy = true
                        pending = emptyMap()
                        thinking = emptyMap()
                        val q = quote
                        quote = null
                        runToken++
                        val myToken = runToken
                        GroupChatJobs.launch(sendGroupId) {
                            try {
                                engine.send(sendGroupId, text, q, files, handleEvent)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // 兜底：引擎内部已经把单个成员的失败收成消息了，
                                // 能走到这里的只有「整轮都没跑起来」这种意外。
                                withContext(Dispatchers.Main) {
                                    status = "发送失败：${e.message ?: e.javaClass.simpleName}"
                                }
                            } finally {
                                // 被「停止」取消时 withContext 本身会立刻抛，必须 NonCancellable，
                                // 否则清理逻辑不会执行。
                                withContext(NonCancellable + Dispatchers.Main) {
                                    if (myToken == runToken) {
                                        pending = emptyMap()
                                        thinking = emptyMap()
                                        version++
                                    }
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
            skills = remember(showSettings, version) { availableSkills(appContext) },
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
        // 图片按发送顺序排在文字上方：群聊里最常看的是图本身，不是说明文字
        message.attachments.filter { it.isImage }.forEach { a ->
            AttachmentImage(
                attachment = a,
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .widthIn(max = 230.dp)
                    .heightIn(max = 220.dp),
            )
        }
        message.attachments.filterNot { it.isImage }.forEach { a ->
            AttachmentFileChip(a, Modifier.padding(vertical = 2.dp))
        }
        if (message.text.isNotBlank()) {
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
}

/** 聊天里显示一张图片。解码放到 IO 线程，避免大图把主线程卡住。 */
@Composable
private fun AttachmentImage(attachment: GroupAttachment, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(null, attachment.path) {
        value = withContext(Dispatchers.IO) { decodeThumbnail(attachment.path, 720) }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = attachment.name,
            modifier = modifier.clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Fit,
        )
    } else {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(10.dp),
            modifier = modifier,
        ) {
            Text(
                text = "🖼 ${attachment.name}（无法显示）",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 文件附件：只显示名字和大小，点开是系统的事（这里不做预览）。 */
@Composable
private fun AttachmentFileChip(attachment: GroupAttachment, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("📎", fontSize = 12.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.widthIn(max = 180.dp),
            )
            val size = pro.openminis.app.ext.group.GroupAttachments.humanSize(attachment.size)
            if (size.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = size,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MemberBubble(
    message: GroupMessage,
    member: GroupMemberDef?,
    onRetry: (() -> Unit)? = null,
) {
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
                if (message.interrupted) {
                    Text(
                        text = " · 已停止",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            // 失败的消息带重试：不重发用户消息，只重新调用这位成员
            if (message.failed && onRetry != null) {
                TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("重试", style = MaterialTheme.typography.labelMedium)
                }
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
    attachments: List<GroupAttachment>,
    onRemoveAttachment: (GroupAttachment) -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    busy: Boolean,
    bridgeReady: Boolean,
    onStop: () -> Unit,
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
            // 待发送的附件：先在这里排一排，发出去之前还能删
            if (attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    attachments.forEach { a ->
                        Box {
                            if (a.isImage) {
                                AttachmentImage(
                                    attachment = a,
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                                )
                            } else {
                                AttachmentFileChip(a)
                            }
                            IconButton(
                                onClick = { onRemoveAttachment(a) },
                                modifier = Modifier
                                    .size(20.dp)
                                    .align(Alignment.TopEnd)
                                    .clip(CircleShape)
                                    .background(Color(0xCC000000)),
                            ) {
                                Text("✕", color = Color.White, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                // 图片和文件分成两个入口：从相册选图是最常用的一条路，
                // 混在一个「全部文件」里会多走好几步。
                IconButton(
                    onClick = onPickImage,
                    enabled = !busy,
                    modifier = Modifier.size(40.dp),
                ) {
                    Text(
                        text = "🖼",
                        fontSize = 18.sp,
                        color = if (busy) Color(0xFFB0B0B5) else Color.Unspecified,
                    )
                }
                IconButton(
                    onClick = onPickFile,
                    enabled = !busy,
                    modifier = Modifier.size(40.dp),
                ) {
                    Text(
                        text = "📎",
                        fontSize = 18.sp,
                        color = if (busy) Color(0xFFB0B0B5) else Color.Unspecified,
                    )
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("发消息给群成员…") },
                    maxLines = 5,
                    shape = RoundedCornerShape(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                if (busy) {
                    // 输出中：发送键换成停止键。打断后已输出的半截回复会保留在聊天里。
                    IconButton(
                        onClick = onStop,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(GC_RED),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = "停止", tint = Color.White)
                    }
                } else {
                    IconButton(
                        onClick = onSend,
                        enabled = value.isNotBlank() || attachments.isNotEmpty(),
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(
                                if (value.isBlank() && attachments.isEmpty()) Color(0xFFB0B0B5) else GC_BLUE,
                            ),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送", tint = Color.White)
                    }
                }
            }
            if (busy) {
                Text(
                    text = "成员同时发言中…（点 ■ 可打断，已输出的内容会保留）",
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
                    .offset(x = if (index == 0) 0.dp else (-8).dp)
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

/**
 * 成员的工具白名单。
 *
 * 勾选是「允许」，不是「强制」：模型可以不用。全部不勾等于彻底禁用这个成员的工具，
 * 它就只能靠自己的知识回答——在群聊里这往往是更安全的选择。
 */
@Composable
private fun MemberToolsRow(member: GroupMemberDef, onSet: (Set<String>) -> Unit) {
    val allowed = GroupTools.allowed(member)
    Column(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("可调用工具", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(6.dp))
            Text(
                text = GroupTools.summary(member),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onSet(GroupTools.ALL_IDS) }) { Text("全开", fontSize = 11.sp) }
            TextButton(onClick = { onSet(emptySet()) }) { Text("关闭", fontSize = 11.sp) }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            GroupTools.SPECS
                // 联网搜索是 OpenCode 的能力，自己存的 API 没有本地搜索后端
                .filter { !(it.freeOnly && member.source == GroupMemberDef.SOURCE_PROVIDER) }
                .forEach { spec ->
                    val on = spec.id in allowed
                    FilterChip(
                        selected = on,
                        onClick = {
                            val next = allowed.toMutableSet()
                            if (on) next.remove(spec.id) else next.add(spec.id)
                            onSet(next)
                        },
                        label = {
                            Text(
                                text = spec.label,
                                fontSize = 11.sp,
                                color = if (on && spec.danger) GC_ORANGE else Color.Unspecified,
                            )
                        },
                    )
                }
        }
        Text(
            text = if (member.source == GroupMemberDef.SOURCE_PROVIDER) {
                "工具在本机应用私有工作区执行，越界路径会被拒绝；联网搜索仅免费通道可用。"
            } else {
                "工具由免费通道在本机执行；写文件、执行命令会在回复里留下可见记录。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 成员的技能白名单。
 *
 * 语义和「可调用工具」一致：不勾选就一个都不加载；「全开」等于跟随设置里
 * 启用的全部技能。技能正文不会进提示词，只放清单，模型要用时自己读文件。
 */
@Composable
private fun MemberSkillsRow(
    member: GroupMemberDef,
    available: List<GroupSkill>,
    onSet: (Set<String>) -> Unit,
) {
    val picked = GroupSkills.select(available, member)
    Column(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("可调用技能", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(6.dp))
            Text(
                text = GroupSkills.summary(available, member),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { onSet(available.map { it.id }.toSet()) },
                enabled = available.isNotEmpty(),
            ) { Text("全开", fontSize = 11.sp) }
            TextButton(onClick = { onSet(emptySet()) }) { Text("关闭", fontSize = 11.sp) }
        }
        if (available.isEmpty()) {
            Text(
                text = "还没有已启用的技能：设置 › 技能，导入或启用后再回来",
                style = MaterialTheme.typography.labelSmall,
                color = GC_ORANGE,
            )
        } else {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                available.forEach { skill ->
                    val on = picked.any { it.id == skill.id }
                    FilterChip(
                        selected = on,
                        onClick = {
                            val next = picked.map { it.id }.toMutableSet()
                            if (on) next.remove(skill.id) else next.add(skill.id)
                            onSet(next)
                        },
                        label = { Text(skill.name, fontSize = 11.sp) },
                    )
                }
            }
            Text(
                text = "技能说明会写进成员共享的工作区，模型要用时自己读取；选了技能会自动允许读文件。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupSettingsSheet(
    group: GroupDef,
    members: List<GroupMemberDef>,
    personas: PersonaRepository,
    freeModels: List<Pair<String, String>>,
    providerChoices: List<ProviderChoice>,
    skills: List<GroupSkill>,
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
                        MemberToolsRow(
                            member = m,
                            onSet = { next ->
                                // 全选写回 null：将来新增工具会自动出现在这个成员身上
                                store.setMemberTools(
                                    m.id,
                                    if (next.containsAll(GroupTools.ALL_IDS)) null else next.sorted(),
                                )
                                onChange()
                            },
                        )
                        MemberSkillsRow(
                            member = m,
                            available = skills,
                            onSet = { next ->
                                // 同上：全选写回 null，将来装了新技能也会自动可用
                                val all = skills.map { it.id }.toSet()
                                store.setMemberSkills(m.id, if (next.containsAll(all)) null else next.sorted())
                                onChange()
                            },
                        )
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

// ─── 图片与技能的小工具 ─────────────────────────────────────────────────────

/**
 * 把图片压成可以塞进请求体的 JPEG。
 *
 * 手机直出的照片动辄 4000×3000、几 MB，base64 之后还会再涨三分之一——很多
 * 「OpenAI 兼容」端点直接 413。这里先按最长边 1600 降采样，再按质量递减压到
 * 1.8 MB 以内；透明图铺白底，否则转 JPEG 会变成黑底。
 */
private fun encodeImageForRequest(file: File): ByteArray? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
    val bmp = BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: return null

    val flat = if (bmp.hasAlpha()) {
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawBitmap(bmp, 0f, 0f, null)
        out
    } else {
        bmp
    }
    val bos = ByteArrayOutputStream()
    var quality = 82
    var bytes: ByteArray
    do {
        bos.reset()
        flat.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        bytes = bos.toByteArray()
        quality -= 12
    } while (bytes.size > 1_800_000 && quality > 30)
    if (flat !== bmp) flat.recycle()
    bmp.recycle()
    bytes
}.getOrNull()

/** 缩略图解码：按最长边降采样，绝不要把原图整张读进内存。 */
private fun decodeThumbnail(path: String, maxSide: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

/**
 * 上游 Skills 仓库里的「已启用技能」。
 *
 * MinisApp 在安全模式下可能还没初始化仓库（lateinit），这里一律 runCatching：
 * 技能加载失败只该让群成员少一项能力，不该让整个群聊界面崩掉。
 */
private fun availableSkills(context: android.content.Context): List<GroupSkill> = runCatching {
    val app = context.applicationContext as MinisApp
    app.skillRepository.skills.value
        .filter { it.isEnabled }
        .map { GroupSkill(id = it.id, name = it.name, description = it.description, body = it.body) }
}.getOrDefault(emptyList())
