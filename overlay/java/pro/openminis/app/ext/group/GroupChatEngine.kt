package pro.openminis.app.ext.group

import pro.openminis.app.ext.opencode.api.TokenUsage
import pro.openminis.app.ext.persona.Persona
import pro.openminis.app.ext.persona.PersonaRepository
import pro.openminis.app.ext.usage.UsageCenter
import pro.openminis.app.ext.usage.UsageEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.UUID

/** 群聊引擎向外抛的增量事件。UI 按成员分段渲染。 */
sealed interface GroupEvent {
    data class MemberStarted(val memberId: String, val name: String) : GroupEvent
    data class MemberDelta(val memberId: String, val text: String) : GroupEvent

    /** 思考过程：只回给本机 UI，绝不写入任何成员可见的上下文。 */
    data class MemberThinking(val memberId: String, val text: String) : GroupEvent
    data class MemberFinished(val memberId: String, val name: String, val text: String) : GroupEvent
    data class MemberFailed(val memberId: String, val name: String, val reason: String) : GroupEvent

    /** 成员自检后认为任务不适合自己，本轮不发言。 */
    data class MemberSkipped(val memberId: String, val name: String, val reason: String) : GroupEvent
    data class Notice(val text: String) : GroupEvent

    /**
     * 用户消息已落盘。界面收到后立刻刷新，让人一发送就看到自己那条——
     * 以前要等整轮结束才 version++，中途看起来就像「消息没发出去」。
     */
    data class UserMessageAdded(val messageId: String) : GroupEvent
}

/**
 * 一个成员这一次要请求的端点。
 *
 * `baseUrl` 已经包含 `/v1` 这类前缀，请求时直接拼 `/chat/completions`。
 * 之所以做成「解析器」而不是在引擎里直接读上游仓库：引擎要能脱离 Android
 * 依赖单独跑测试，而 ProviderRepository 是 Android 侧的异步单例。
 */
data class MemberEndpoint(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /**
     * 这个成员允许调用的工具（[GroupTools] 的 id）。
     *
     * `null` = 不限制（老配置）；空集合 = 明确禁用全部工具。
     */
    val tools: Set<String>? = null,
    /** 是否走本机免费通道（OpenCode）。免费通道的工具由 OpenCode 执行。 */
    val isFree: Boolean = false,
)

fun interface MemberEndpointResolver {
    fun resolve(member: GroupMemberDef): MemberEndpoint?
}

/**
 * 群聊引擎：把「一条用户消息」变成「若干成员同时发言」。
 *
 * ## 上下文可见性（这是群聊最容易做错的地方）
 *
 * 用户的要求是：**每个 AI 的思考过程别人看不到；本轮其他成员的回复，也要等
 * 群主下次发消息时才会被看到**。所以这里在写入用户消息**之前**先取一份历史快照，
 * 整轮所有成员都只拿这份快照：
 *
 *  - 同一轮里 A 说完，B 看不到 A 这一轮的回复（B 的上下文里只有上一轮及更早）；
 *  - 下一轮用户发言时，快照自然包含了 A、B 上一轮的最终答复；
 *  - 思考过程（thinking）只落盘给用户看，构建上下文时从不读取。
 *
 * ## 为什么可以并行
 *
 * 正因为每个成员只拿「发言前」的快照，成员之间在本轮互不依赖——上下文模型天然
 * 兼容并行。多个成员同时流式输出，UI 按成员分段渲染，谁先出字谁先显示。
 */
class GroupChatEngine(
    private val store: GroupStore,
    private val personas: PersonaRepository,
    private val resolver: MemberEndpointResolver,
    private val http: OkHttpClient,
    /**
     * 「自己存的 API」成员执行工具的工作区。和免费通道共用
     * `filesDir/opencode/workspace`，这样两种成员看到的是同一批文件。
     */
    workspace: File? = null,
    /** 已启用的技能。默认空：老调用方（测试、别的入口）不传就没有技能。 */
    private val skills: GroupSkillSource = GroupSkillSource { emptyList() },
    /**
     * 把图片文件压成适合放进请求体的 JPEG 字节。
     *
     * 放在构造参数里而不是直接调 BitmapFactory，是为了让引擎能在 JVM 上跑单测
     * （那边没有 Android 图形库）。返回 null 表示这张图没法编码，调用方会退化成
     * 「已保存到工作区，用工具自己看」。
     */
    private val encodeImage: (File) -> ByteArray? = { null },
) {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val planner = GroupTurnPlanner()
    private val workDir = workspace
        ?: File(System.getProperty("java.io.tmpdir") ?: ".", "minis-group-workspace")
    private val tools = GroupToolRunner(workspace = workDir, http = http)

    /** 给消息栏用：当前群可发言成员的人设名，避免 UI 再查一次仓库。 */
    fun memberViews(groupId: String): List<Pair<GroupMemberDef, Persona?>> =
        store.members(groupId).map { it to personas.get(it.personaId) }

    suspend fun send(
        groupId: String,
        text: String,
        quote: GroupMessage? = null,
        attachments: List<GroupAttachment> = emptyList(),
        onEvent: suspend (GroupEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (text.isBlank() && attachments.isEmpty()) return@withContext

        val group = store.group(groupId) ?: run {
            onEvent(GroupEvent.Notice("群不存在"))
            return@withContext
        }
        val members = store.members(groupId).filter { it.enabled }
        if (members.isEmpty()) {
            onEvent(GroupEvent.Notice("这个群还没有成员：先添加成员"))
            return@withContext
        }

        // 关键：先取快照，再写入本轮用户消息。见类注释的「上下文可见性」。
        // 流式草稿（还在写的那条）要排掉：它是半截内容，不是这轮的完整答复。
        val history = store.messages(groupId).filter { !it.streaming }.takeLast(HISTORY_LIMIT)

        val userMessage = GroupMessage(
            id = "msg_" + UUID.randomUUID().toString().take(8),
            groupId = groupId,
            role = "user",
            memberId = null,
            memberName = null,
            text = text.trim(),
            createdAt = System.currentTimeMillis(),
            quoteMemberId = quote?.memberId,
            quotePreview = quote?.text?.take(120),
            attachments = attachments,
        )
        store.appendMessage(userMessage)
        onEvent(GroupEvent.UserMessageAdded(userMessage.id))

        val planningMembers = members.map { m ->
            GroupMember(
                id = m.id,
                personaId = m.personaId,
                modelBinding = m.modelId,
                displayName = m.displayName.ifBlank { personas.get(m.personaId)?.name ?: "成员" },
                enabled = m.enabled,
            )
        }

        val quoteRef = quote?.let {
            QuoteRef.of(
                messageId = it.id,
                memberId = it.memberId.orEmpty(),
                role = if (it.isUser) QuoteRole.USER else QuoteRole.ASSISTANT,
                content = it.text,
                createdAt = it.createdAt,
            )
        }

        val plan = planner.plan(
            members = planningMembers,
            strategy = group.strategy,
            quote = quoteRef,
            // 纯图片消息没有正文：用一个能说明「有内容」的占位当话题，
            // 否则任务自检和轮询策略会以为这一轮是空的。
            topic = text.trim().ifBlank { "（用户发来了附件，请查看）" },
            traitsOf = { pid -> personas.get(pid)?.traits.orEmpty() },
            allowTakeover = group.allowTakeover,
        )

        val order = when (plan) {
            is TurnPlan.Speak -> plan.order
            is TurnPlan.WaitFor -> {
                onEvent(GroupEvent.Notice("等待 ${nameOf(members, plan.target)} 回应：${plan.reason}"))
                return@withContext
            }
            is TurnPlan.Takeover -> {
                onEvent(GroupEvent.Notice("定向未达成，已转交 ${nameOf(members, plan.actual)}：${plan.reason}"))
                plan.order
            }
            is TurnPlan.DirectFailed -> {
                onEvent(GroupEvent.Notice(plan.reason.ifBlank { "本轮无人可发言" }))
                return@withContext
            }
        }

        // 并行发言：每个成员拿同一份快照、同时流式输出。见类注释「为什么可以并行」。
        // 异常与取消的处理都在 speakOne 内收敛，这里只负责同时开火、一起等完。
        coroutineScope {
            order.take(MAX_SPEAKERS).map { memberId ->
                launch {
                    val member = members.firstOrNull { it.id == memberId } ?: return@launch
                    speakOne(group, member, history, text, quote, attachments, onEvent = onEvent)
                }
            }
        }
    }

    /**
     * 单个成员的完整一轮：解析端点 → 任务自检 → 流式调用 → 落盘。
     *
     * 被取消（用户点停止）时，已输出的半截文本也要落盘——用户明确要求
     * 「打断时已说的话还在」。所以 catch 里用 [NonCancellable] 写文件再 rethrow。
     */
    private suspend fun speakOne(
        group: GroupDef,
        member: GroupMemberDef,
        history: List<GroupMessage>,
        userText: String,
        quote: GroupMessage?,
        attachments: List<GroupAttachment>,
        /** 用户手动点「重试」：跳过「这个任务适合我吗」的自检。 */
        force: Boolean = false,
        onEvent: suspend (GroupEvent) -> Unit,
    ) {
        val persona = personas.get(member.personaId)
        val name = member.displayName.ifBlank { persona?.name ?: "成员" }

        val endpoint = resolver.resolve(member)
        if (endpoint == null) {
            val reason = if (member.source == GroupMemberDef.SOURCE_PROVIDER)
                "绑定的 API 不可用（凭据缺失或该类型暂不支持），到群设置里改一下"
            else "免费通道未启用（设置 › 免费 Token 通道）"
            onEvent(GroupEvent.MemberFailed(member.id, name, reason))
            // 也要落一条失败消息：否则界面上只有一行状态条，没有任何地方能点重试。
            store.appendMessage(
                GroupMessage(
                    id = "msg_" + UUID.randomUUID().toString().take(8),
                    groupId = group.id,
                    role = "assistant",
                    memberId = member.id,
                    memberName = name,
                    text = "（调用失败：$reason）",
                    createdAt = System.currentTimeMillis(),
                    failed = true,
                ),
            )
            return
        }

        // 技能：先过滤出这个成员能用的，再把正文镜像进工作区（两种来源都靠 read_file 读）
        val available = runCatching { skills.skillsFor(member) }.getOrDefault(emptyList())
        val picked = GroupSkills.select(available, member)
        val mirrored = GroupSkills.mirrorToWorkspace(workDir, picked)
        val promptSkills = picked.filter { it.id in mirrored }
        val skillFragment = GroupSkills.promptFragment(promptSkills)

        // 选了技能却不让读文件，技能等于摆设：自动补上「读文件」，
        // 但用户显式关掉全部工具（空集合）时不补——那是一个明确的安全选择。
        var allowedTools = GroupTools.allowed(member)
        if (promptSkills.isNotEmpty() && allowedTools.isNotEmpty()) {
            allowedTools = allowedTools + GroupTools.READ
        }
        val freeToolRule: Set<String>? =
            if (member.tools == null && promptSkills.isEmpty()) null else allowedTools

        // 任务适配自检：只问一句「适合你吗」，不适合就不占用一轮发言
        val taskForFit = userText.trim().ifBlank {
            attachments.joinToString("、") { it.name }.let { "用户发来了附件：$it" }
        }
        if (!force && group.autoTaskFit && shouldSkip(endpoint, persona, name, taskForFit)) {
            onEvent(GroupEvent.MemberSkipped(member.id, name, "判断这个任务不适合自己"))
            store.appendMessage(
                GroupMessage(
                    id = "msg_" + UUID.randomUUID().toString().take(8),
                    groupId = group.id,
                    role = "assistant",
                    memberId = member.id,
                    memberName = name,
                    text = "（这个任务不适合我，本轮先不发言）",
                    createdAt = System.currentTimeMillis(),
                    skipped = true,
                ),
            )
            return
        }

        onEvent(GroupEvent.MemberStarted(member.id, name))
        // 这条回复的固定 id：草稿、最终版、打断版都覆盖同一条记录，界面上永远
        // 只有一条气泡。以前是「说完才 append」，中途退出界面就什么都留不下。
        val messageId = "msg_" + UUID.randomUUID().toString().take(8)
        val createdAt = System.currentTimeMillis()
        val reply = StringBuilder()
        val thinking = StringBuilder()
        var lastPersistAt = 0L
        var usage: TokenUsage? = null
        var failure: Throwable? = null

        /** 把「到目前为止」的内容写盘。streaming=true 表示还会被后续覆盖。 */
        fun persistDraft(streaming: Boolean) {
            store.upsertMessage(
                GroupMessage(
                    id = messageId,
                    groupId = group.id,
                    role = "assistant",
                    memberId = member.id,
                    memberName = name,
                    text = reply.toString(),
                    createdAt = createdAt,
                    thinking = thinking.toString().trim().ifBlank { null },
                    streaming = streaming,
                ),
            )
        }

        try {
            usage = streamMember(
                endpoint = endpoint,
                persona = persona,
                member = member,
                memberName = name,
                history = history,
                userText = userText,
                quote = quote,
                attachments = attachments,
                skillFragment = skillFragment,
                freeToolRule = freeToolRule,
                onDelta = { chunk ->
                    reply.append(chunk)
                    onEvent(GroupEvent.MemberDelta(member.id, chunk))
                    // 节流落盘：每个字都写文件会把主线程外的 IO 打满，
                    // 0.7 秒一次足够让「切走再回来」看到最新进度。
                    val now = System.currentTimeMillis()
                    if (now - lastPersistAt >= STREAM_PERSIST_MS) {
                        lastPersistAt = now
                        persistDraft(streaming = true)
                    }
                },
                onThinking = { chunk ->
                    thinking.append(chunk)
                    onEvent(GroupEvent.MemberThinking(member.id, chunk))
                    val now = System.currentTimeMillis()
                    if (now - lastPersistAt >= STREAM_PERSIST_MS) {
                        lastPersistAt = now
                        persistDraft(streaming = true)
                    }
                },
                // 工具块和正文一样进这条气泡：用户看得见「调用了什么、结果是什么」，
                // 落盘之后切走再回来也还在。
                onTool = { block ->
                    reply.append(block)
                    onEvent(GroupEvent.MemberDelta(member.id, block))
                    val now = System.currentTimeMillis()
                    if (now - lastPersistAt >= STREAM_PERSIST_MS) {
                        lastPersistAt = now
                        persistDraft(streaming = true)
                    }
                },
                allowedTools = allowedTools,
            )
        } catch (e: CancellationException) {
            // 打断：半截回复落盘（没字就把草稿删掉，别留空壳），再把取消抛回去
            withContext(NonCancellable) {
                if (reply.toString().isBlank()) {
                    store.removeMessage(group.id, messageId)
                } else {
                    store.upsertMessage(
                        GroupMessage(
                            id = messageId,
                            groupId = group.id,
                            role = "assistant",
                            memberId = member.id,
                            memberName = name,
                            text = reply.toString().trim(),
                            createdAt = createdAt,
                            thinking = thinking.toString().trim().ifBlank { null },
                            interrupted = true,
                            streaming = false,
                        ),
                    )
                }
            }
            throw e
        } catch (e: Exception) {
            // 同上：取消引起的断连不算失败
            currentCoroutineContext().ensureActive()
            failure = e
        }

        if (failure != null) {
            onEvent(GroupEvent.MemberFailed(member.id, name, failure.message ?: "调用失败"))
            store.upsertMessage(
                GroupMessage(
                    id = messageId,
                    groupId = group.id,
                    role = "assistant",
                    memberId = member.id,
                    memberName = name,
                    text = "（调用失败：${failure.message}）",
                    createdAt = createdAt,
                    failed = true,
                    streaming = false,
                ),
            )
        } else {
            val finalText = reply.toString().trim()
            // 先落盘再发事件：UI 收到 MemberFinished 会撤掉流式气泡，
            // 这样磁盘上已经有最终版，不会出现「气泡没了、消息也没了」。
            store.upsertMessage(
                GroupMessage(
                    id = messageId,
                    groupId = group.id,
                    role = "assistant",
                    memberId = member.id,
                    memberName = name,
                    text = finalText.ifBlank { "（本轮没有输出）" },
                    createdAt = createdAt,
                    thinking = thinking.toString().trim().ifBlank { null },
                    streaming = false,
                ),
            )
            onEvent(GroupEvent.MemberFinished(member.id, name, finalText))
            // 绑定已存 API 的成员直连外部服务，bridge 看不到这些调用——
            // 用量在这里记账。免费成员走 bridge，由 bridge 侧记录，避免双重入账。
            if (usage != null && !usage.isEmpty && member.source == GroupMemberDef.SOURCE_PROVIDER) {
                runCatching {
                    UsageCenter.record(
                        UsageEvent(
                            provider = "api",
                            model = endpoint.model,
                            usage = usage,
                            timestamp = System.currentTimeMillis(),
                            sessionId = group.id,
                            memberId = member.id,
                            personaId = member.personaId,
                        ),
                    )
                }
            }
        }
    }

    /**
     * 重试单个失败成员：不重发用户消息，只把这位成员按本轮上下文重新调用一遍。
     *
     * 本轮上下文 = 到最后一条用户消息（含）为止的历史快照——和其他成员看到的
     * 完全一致，保证「这一轮大家都看同样的东西」这条规则不被重试打破。
     */
    suspend fun retryMember(
        groupId: String,
        memberId: String,
        onEvent: suspend (GroupEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val group = store.group(groupId) ?: run {
            onEvent(GroupEvent.Notice("群不存在"))
            return@withContext
        }
        val member = store.members(groupId).firstOrNull { it.id == memberId } ?: run {
            onEvent(GroupEvent.Notice("成员不存在，可能已被删除"))
            return@withContext
        }

        val all = store.messages(groupId).filter { !it.streaming }
        // 最后一条用户消息定义了「当前轮」；重试只覆盖本轮，不吃进本轮已落盘的回复
        val lastUserIdx = all.indexOfLast { it.isUser }
        if (lastUserIdx < 0) {
            onEvent(GroupEvent.Notice("没有可以重试的用户消息"))
            return@withContext
        }
        val userMessage = all[lastUserIdx]
        // 不含这条用户消息本身：speakOne 会把「本轮用户输入」再拼一次。
        // 以前这里写的是 lastUserIdx + 1，结果请求里出现两条一模一样的 user
        // 消息，部分端点直接 400——真机上就是「点重试永远失败」。
        val history = all.subList(0, lastUserIdx)

        // 引用只有 preview（120 字），重试用它还原最接近的引用形态
        val quote = userMessage.quoteMemberId?.let { qid ->
            all.firstOrNull { it.id == qid }
        }
        // 半截/失败的旧记录删掉，避免聊天里堆两条同一成员的失败消息
        withContext(NonCancellable) {
            store.removeLastFailedMessage(groupId, memberId)
        }
        speakOne(
            group, member, history, userMessage.text, quote, userMessage.attachments,
            // 用户已经明确要求重试，别再让自检把它跳过
            force = true,
            onEvent = onEvent,
        )
    }

    private fun nameOf(members: List<GroupMemberDef>, id: String): String =
        members.firstOrNull { it.id == id }?.displayName?.takeIf { it.isNotBlank() } ?: id

    // ---- 上下文构建 --------------------------------------------------------

    /**
     * 只喂「最终答复」。`thinking` 不参与构建——这就是「别的 AI 看不到你的思考过程」
     * 的实现方式：不是靠提示词劝阻，而是根本不给。
     */
    private fun historyMessages(history: List<GroupMessage>, member: GroupMemberDef): JSONArray {
        val arr = JSONArray()
        history.forEach { m ->
            if (m.isUser) {
                // 历史里的图片一律转成文字说明（已存到工作区）：每轮都把之前的
                // 图重传一遍既贵又慢，而且模型真的需要时可以用 read_file 打开。
                val content = if (m.attachments.isEmpty()) {
                    m.text
                } else {
                    GroupAttachments.messageText(
                        text = m.text,
                        attachments = m.attachments,
                        workspace = workDir,
                        imageAsText = true,
                    )
                }
                arr.put(JSONObject().put("role", "user").put("content", content))
            } else if (m.skipped) {
                // 跳过也算一条公开事实，但不值得占篇幅
            } else if (m.memberName != null) {
                val same = m.memberId == member.id
                val body = if (same) m.text else "【${m.memberName}】${m.text}"
                arr.put(JSONObject().put("role", "assistant").put("content", body))
            }
        }
        return arr
    }

    private fun systemPrompt(
        persona: Persona?,
        memberName: String,
        member: GroupMemberDef,
        skillFragment: String,
    ): String {
        val effort = when (member.reasoning) {
            "low" -> "回答尽量简短直接，不要展开推理。"
            "high" -> "回答前充分推演，给出完整论证；但只输出最终答复，不要输出思考过程。"
            else -> ""
        }
        return buildString {
            val base = persona?.systemPrompt?.trim().orEmpty()
            append(base.ifBlank { "你是一个可靠的中文助手。" })
            append("\n\n你正在一个多人群聊里，以「$memberName」的身份发言。")
            append("只输出你这一轮的发言内容，不要写「$memberName：」这样的前缀。")
            append("你看不到其他成员的思考过程，也不要假装看过；")
            append("其他成员的发言会以【名字】标注，需要时可以回应，但不要替他们说话。")
            append("不要复述别人刚说过的话，直接给出你的观点或结果。")
            if (effort.isNotBlank()) append("\n").append(effort)
            if (skillFragment.isNotBlank()) append(skillFragment)
        }
    }

    // ---- 任务适配自检 ------------------------------------------------------

    private suspend fun shouldSkip(
        endpoint: MemberEndpoint,
        persona: Persona?,
        memberName: String,
        task: String,
    ): Boolean {
        // 极短的消息（寒暄、追问）不做自检：既省一次调用，也避免把闲聊判成任务
        if (task.trim().length < 12) return false
        val system = buildString {
            append("你在判断一个任务是否适合「$memberName」来做。")
            val s = persona?.systemPrompt?.trim().orEmpty()
            if (s.isNotBlank()) append("它的职责是：").append(s.take(400))
            append("\n只回答一个词：SUITABLE 或 UNSUITABLE，不要解释。")
        }
        val body = JSONObject()
            .put("model", endpoint.model)
            .put("stream", false)
            .put("temperature", 0.0)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", "任务：${task.take(800)}")))
        val answer = try {
            callOnce(endpoint, body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 自检失败（含被停止断连）不该让这一轮变失败，但取消必须继续往上抛
            currentCoroutineContext().ensureActive()
            return false
        }
        return answer.contains("UNSUITABLE", ignoreCase = true)
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun callOnce(endpoint: MemberEndpoint, body: JSONObject): String {
        val req = Request.Builder()
            .url("${endpoint.baseUrl}/chat/completions")
            .post(body.toString().toRequestBody(jsonMedia))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${endpoint.apiKey}")
            .build()
        val call = http.newCall(req)
        // 自检也是一次真实请求（虽然 stream=false）：点停止时同样要能被掐断，
        // 否则用户会看到「点了停止，还要再等几秒才停」。
        val onDone = currentCoroutineContext()[Job]
            ?.invokeOnCompletion(onCancelling = true) { call.cancel() }
        try {
            call.execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} ${text.take(200)}")
                val choices = JSONObject(text).optJSONArray("choices") ?: return ""
                return choices.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
            }
        } finally {
            onDone?.dispose()
        }
    }

    // ---- 流式调用 ----------------------------------------------------------

    /**
     * 单个成员的一次完整生成。
     *
     * 两条来源的工具执行方式完全不同：
     *
     *  - **免费通道**：工具由 OpenCode 在设备上执行，白名单通过会话权限下发，
     *    桥接把「调用了什么、结果是什么」渲染进正文；
     *  - **自己存的 API**：上游只会返回 `tool_calls`，没有任何执行能力，
     *    由 [GroupToolRunner] 在本机执行后回填，循环到模型不再调用为止。
     */
    private suspend fun streamMember(
        endpoint: MemberEndpoint,
        persona: Persona?,
        member: GroupMemberDef,
        memberName: String,
        history: List<GroupMessage>,
        userText: String,
        quote: GroupMessage?,
        attachments: List<GroupAttachment>,
        skillFragment: String,
        freeToolRule: Set<String>?,
        onDelta: suspend (String) -> Unit,
        onThinking: suspend (String) -> Unit,
        onTool: suspend (String) -> Unit,
        allowedTools: Set<String>,
    ): TokenUsage? {
        val messages = historyMessages(history, member)
        val current = buildString {
            if (quote != null && quote.memberName != null) {
                append("【引用 ${quote.memberName} 的发言】\n")
                append(quote.text.take(QuoteRef.SNAPSHOT_LIMIT))
                append("\n\n【我的回复】\n")
            }
            append(userText)
        }
        messages.put(
            JSONObject()
                .put("role", "user")
                .put("content", currentContent(current, attachments, endpoint.isFree)),
        )

        val full = JSONArray()
        full.put(
            JSONObject()
                .put("role", "system")
                .put("content", systemPrompt(persona, memberName, member, skillFragment)),
        )
        for (i in 0 until messages.length()) {
            messages.optJSONObject(i)?.let { full.put(it) }
        }

        if (endpoint.isFree) {
            // null = 用户没限制过且没用技能，等价于 OpenCode 的默认全开；显式集合才下推权限。
            return streamOnce(
                endpoint = endpoint,
                messages = full,
                tools = null,
                freeToolRule = freeToolRule,
                onDelta = onDelta,
                onThinking = onThinking,
            ).usage
        }

        return streamWithTools(
            endpoint = endpoint,
            messages = full,
            allowed = allowedTools,
            onDelta = onDelta,
            onThinking = onThinking,
            onTool = onTool,
        )
    }

    /**
     * 本轮用户输入的内容块。
     *
     * 文本 + 文本类附件始终拼成一段字符串；图片在看得到图的成员那边走
     * OpenAI 多模态（content 数组），在免费通道那边退化成「已存到工作区，
     * 用 read_file 看」——桥接只接受文本，硬塞 image_url 会被上游直接丢掉。
     */
    private fun currentContent(
        text: String,
        attachments: List<GroupAttachment>,
        free: Boolean,
    ): Any {
        val merged = GroupAttachments.messageText(
            text = text,
            attachments = attachments,
            workspace = workDir,
            imageAsText = free,
        )
        if (free || attachments.none { it.isImage }) return merged

        val images = attachments.filter { it.isImage }.take(GroupAttachments.MAX_IMAGES)
        val parts = JSONArray()
        parts.put(JSONObject().put("type", "text").put("text", merged))
        var encoded = 0
        images.forEach { a ->
            val bytes = runCatching { encodeImage(File(a.path)) }.getOrNull() ?: return@forEach
            if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return@forEach
            val mime = a.mime.takeIf { it.startsWith("image/") } ?: "image/jpeg"
            parts.put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject().put(
                            "url",
                            "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes),
                        ),
                    ),
            )
            encoded++
        }
        if (encoded == 0) {
            // 一张都没编码成功（格式不支持 / 太大）：退回文字版，
            // 模型至少知道「有图、图在哪」，不会以为消息是空的。
            return GroupAttachments.messageText(text, attachments, workDir, imageAsText = true)
        }
        return parts
    }

    /**
     * 直连 API 的工具循环。
     *
     * `tool_calls` 必须原样回填成 assistant 消息，再逐条补 `role=tool` 的结果——
     * 少一条上游就会 400，这是 OpenAI 协议里最容易踩的坑。
     */
    private suspend fun streamWithTools(
        endpoint: MemberEndpoint,
        messages: JSONArray,
        allowed: Set<String>,
        onDelta: suspend (String) -> Unit,
        onThinking: suspend (String) -> Unit,
        onTool: suspend (String) -> Unit,
    ): TokenUsage? {
        // 只声明本机真的能执行的工具；联网搜索没有可靠后端，不在此列。
        val localOnly = allowed.intersect(
            setOf(
                GroupTools.READ, GroupTools.EDIT, GroupTools.GLOB,
                GroupTools.GREP, GroupTools.SHELL, GroupTools.WEBFETCH,
            ),
        )
        val declarations = GroupTools.openAiTools(localOnly)
        var total: TokenUsage? = null

        for (round in 0 until MAX_TOOL_ROUNDS) {
            val res = try {
                streamOnce(
                    endpoint = endpoint,
                    messages = messages,
                    tools = declarations.takeIf { it.length() > 0 },
                    freeToolRule = null,
                    onDelta = onDelta,
                    onThinking = onThinking,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 被「停止」断开的连接会抛 IOException，它不是取消异常。
                // 先看一眼协程是不是已经取消：是就把取消抛回去，让上层走
                // 「打断」分支（保留半截内容），而不是记成一次调用失败。
                currentCoroutineContext().ensureActive()
                // 兼容性兜底：不少「OpenAI 兼容」模型其实不支持 function calling，
                // 带 tools 直接 400。旧群成员升级后默认全开工具，如果不降级，
                // 原本能聊的群会突然整条失败——这比「工具没生效」严重得多。
                // 只在第一轮（还没有任何工具结果回填）降级，避免把真实错误吞掉。
                if (round == 0 && declarations.length() > 0 && isToolsUnsupported(e)) {
                    onTool("\n\n（这个模型不支持工具调用，本轮已按普通对话重试）\n\n")
                    streamOnce(
                        endpoint = endpoint,
                        messages = messages,
                        tools = null,
                        freeToolRule = null,
                        onDelta = onDelta,
                        onThinking = onThinking,
                    )
                } else {
                    throw e
                }
            }
            total = when {
                res.usage == null -> total
                total == null -> res.usage
                else -> total + res.usage!!
            }

            if (res.calls.isEmpty()) break
            if (round == MAX_TOOL_ROUNDS - 1) {
                onTool("\n\n（本轮工具调用达到上限 $MAX_TOOL_ROUNDS 次，已停止继续调用）\n\n")
                break
            }

            val callsJson = JSONArray()
            res.calls.values.forEach { c ->
                callsJson.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject().put("name", c.name).put("arguments", c.args.toString()),
                        ),
                )
            }
            messages.put(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", res.text.toString())
                    .put("tool_calls", callsJson),
            )

            for (c in res.calls.values) {
                val argsObj = runCatching {
                    JSONObject(c.args.toString().ifBlank { "{}" })
                }.getOrNull()
                val result = tools.execute(c.name, c.args.toString(), localOnly)
                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", c.id)
                        .put("name", c.name)
                        .put("content", result.output),
                )
                onTool(GroupTools.renderToolBlock(c.name, argsObj, result.output, result.isError))
            }
        }
        return total
    }

    /**
     * 这次失败是不是「端点不认 tools」。
     *
     * 只认 400/422：这是 OpenAI 兼容端点对「不认识请求体字段」的典型回应。
     * 401（key 错）、429（限流）、5xx 都不降级——降级重试只会再撞一次同样的墙，
     * 还把真正的错误信息盖掉。
     */
    private fun isToolsUnsupported(e: Exception): Boolean {
        val code = Regex("HTTP (\\d{3})")
            .find(e.message.orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
        return code == 400 || code == 422
    }

    /** 一次流式请求（不含工具循环）。tool_calls 按 index 累积成完整调用。 */
    @OptIn(InternalCoroutinesApi::class)
    private suspend fun streamOnce(
        endpoint: MemberEndpoint,
        messages: JSONArray,
        tools: JSONArray?,
        freeToolRule: Set<String>?,
        onDelta: suspend (String) -> Unit,
        onThinking: suspend (String) -> Unit,
    ): RoundResult {
        val body = JSONObject()
            .put("model", endpoint.model)
            .put("stream", true)
            .put("temperature", 0.7)
            // 兼容 OpenAI 标准端点：最后一块带 usage。不认识的端点会忽略它。
            .put("stream_options", JSONObject().put("include_usage", true))
            .put("messages", messages)
        if (tools != null && tools.length() > 0) body.put("tools", tools)
        if (freeToolRule != null) {
            // 桥接的私有扩展：转成 OpenCode 的会话权限。OpenAI 兼容端点不会看到
            // 这个字段（只有免费通道会走桥接）。
            body.put("x_minis_tools", JSONArray(freeToolRule.toList()))
        }

        val req = Request.Builder()
            .url("${endpoint.baseUrl}/chat/completions")
            .post(body.toString().toRequestBody(jsonMedia))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${endpoint.apiKey}")
            .build()

        // 用户点「停止」时协程被取消，但 readUtf8Line 是阻塞的 socket 读——
        // 不主动断连的话读线程会一直挂到 readTimeout。
        //
        // 这里必须用 `onCancelling = true`：默认的 invokeOnCompletion 只在协程
        // **完全结束**后回调，而协程正卡在这次阻塞读上，两者互等到天荒地老——
        // 真机表现就是「点了暂停没反应，AI 一直在吐字，转圈也不停」。
        // 加上 onCancelling 后，cancel 的瞬间就会断开连接，读操作立刻抛
        // IOException，下面的 catch 再把它还原成 CancellationException。
        val result = RoundResult()
        val call = http.newCall(req)
        val job = currentCoroutineContext()[Job]
        val onDone = job?.invokeOnCompletion(onCancelling = true) { call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string()?.take(300).orEmpty()
                    throw IllegalStateException("HTTP ${resp.code} $err")
                }
                val source = resp.body?.source() ?: throw IllegalStateException("响应为空")
                while (true) {
                    // 断连后 readUtf8Line 返回 null 会走到 break，看起来像「正常结束」
                    // ——那样半截回复会被当成完整回答。取消优先，直接抛出去。
                    if (!currentCoroutineContext().isActive) throw CancellationException("已停止")
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val obj = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    obj.optJSONObject("error")?.let {
                        throw IllegalStateException(it.optString("message", "上游错误"))
                    }
                    // include_usage 的最后一块：choices 为空、usage 有值
                    obj.optJSONObject("usage")?.let { u ->
                        result.usage = TokenUsage(
                            input = u.optLong("prompt_tokens"),
                            output = u.optLong("completion_tokens"),
                            cacheRead = u.optJSONObject("prompt_tokens_details")?.optLong("cached_tokens") ?: 0L,
                        )
                    }
                    val choices = obj.optJSONArray("choices") ?: continue
                    val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: continue
                    val piece = delta.optString("content", "")
                    if (piece.isNotEmpty()) {
                        result.text.append(piece)
                        onDelta(piece)
                    }
                    val reason = delta.optString("reasoning_content", "")
                    if (reason.isNotEmpty()) onThinking(reason)
                    delta.optJSONArray("tool_calls")?.let { tcs ->
                        for (i in 0 until tcs.length()) {
                            val tc = tcs.optJSONObject(i) ?: continue
                            val idx = if (tc.has("index")) tc.optInt("index") else i
                            val draft = result.calls.getOrPut(idx) { ToolCallDraft() }
                            tc.optString("id").takeIf { it.isNotBlank() }?.let { draft.id = it }
                            val fn = tc.optJSONObject("function") ?: continue
                            fn.optString("name").takeIf { it.isNotBlank() }?.let { draft.name = it }
                            fn.optString("arguments").takeIf { it.isNotEmpty() }?.let { draft.args.append(it) }
                        }
                    }
                }
            }
        } finally {
            onDone?.dispose()
            call.cancel()
        }
        // 部分兼容端点不给 id / index，补一个稳定的，否则回填 tool 消息会被拒
        result.calls.forEach { (idx, c) ->
            if (c.id.isBlank()) c.id = "call_${idx}_${System.currentTimeMillis()}"
        }
        return result
    }

    private class ToolCallDraft {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()
    }

    private class RoundResult {
        val text = StringBuilder()
        val calls = LinkedHashMap<Int, ToolCallDraft>()
        var usage: TokenUsage? = null
    }

    companion object {
        /** 单轮最多几个成员发言。免费池限速下，超过这个数基本只会换来 429。 */
        const val MAX_SPEAKERS = 3

        /** 带进上下文的历史条数。群聊越长，越需要压住 token。 */
        const val HISTORY_LIMIT = 24

        /** 流式草稿落盘节流：每个字都写文件会把 IO 打满。 */
        const val STREAM_PERSIST_MS = 700L

        /** 一条回复里最多允许模型来回调用几轮工具，防止死循环烧 token。 */
        const val MAX_TOOL_ROUNDS = 6

        /**
         * 单张图片编码后的上限。base64 会膨胀约 4/3，2 MB 原图大约 2.7 MB 请求体，
         * 再大很多「OpenAI 兼容」端点会直接 413。
         */
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
    }
}
