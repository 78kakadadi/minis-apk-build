package pro.openminis.app.ext.group

import pro.openminis.app.ext.opencode.api.TokenUsage
import pro.openminis.app.ext.persona.Persona
import pro.openminis.app.ext.persona.PersonaRepository
import pro.openminis.app.ext.usage.UsageCenter
import pro.openminis.app.ext.usage.UsageEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
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
) {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val planner = GroupTurnPlanner()

    /** 给消息栏用：当前群可发言成员的人设名，避免 UI 再查一次仓库。 */
    fun memberViews(groupId: String): List<Pair<GroupMemberDef, Persona?>> =
        store.members(groupId).map { it to personas.get(it.personaId) }

    suspend fun send(
        groupId: String,
        text: String,
        quote: GroupMessage? = null,
        onEvent: suspend (GroupEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext

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
        val history = store.messages(groupId).takeLast(HISTORY_LIMIT)

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
        )
        store.appendMessage(userMessage)

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
            topic = text,
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
                    speakOne(group, member, history, text, quote, onEvent)
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
        onEvent: suspend (GroupEvent) -> Unit,
    ) {
        val persona = personas.get(member.personaId)
        val name = member.displayName.ifBlank { persona?.name ?: "成员" }

        val endpoint = resolver.resolve(member)
        if (endpoint == null) {
            onEvent(
                GroupEvent.MemberFailed(
                    member.id,
                    name,
                    if (member.source == GroupMemberDef.SOURCE_PROVIDER)
                        "绑定的 API 不可用（凭据缺失或该类型暂不支持），到群设置里改一下"
                    else "免费通道未启用（设置 › 免费 Token 通道）",
                ),
            )
            return
        }

        // 任务适配自检：只问一句「适合你吗」，不适合就不占用一轮发言
        if (group.autoTaskFit && shouldSkip(endpoint, persona, name, userText)) {
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
        val reply = StringBuilder()
        val thinking = StringBuilder()
        var usage: TokenUsage? = null
        var failure: Throwable? = null
        try {
            usage = streamMember(
                endpoint = endpoint,
                persona = persona,
                member = member,
                memberName = name,
                history = history,
                userText = userText,
                quote = quote,
                onDelta = { chunk ->
                    reply.append(chunk)
                    onEvent(GroupEvent.MemberDelta(member.id, chunk))
                },
                onThinking = { chunk ->
                    thinking.append(chunk)
                    onEvent(GroupEvent.MemberThinking(member.id, chunk))
                },
            )
        } catch (e: CancellationException) {
            // 打断：半截回复落盘（没字就不留空壳），再把取消抛回去让 UI 收尾
            val partial = reply.toString().trim()
            if (partial.isNotEmpty()) {
                withContext(NonCancellable) {
                    store.appendMessage(
                        GroupMessage(
                            id = "msg_" + UUID.randomUUID().toString().take(8),
                            groupId = group.id,
                            role = "assistant",
                            memberId = member.id,
                            memberName = name,
                            text = partial,
                            createdAt = System.currentTimeMillis(),
                            interrupted = true,
                        ),
                    )
                }
            }
            throw e
        } catch (e: Exception) {
            failure = e
        }

        if (failure != null) {
            onEvent(GroupEvent.MemberFailed(member.id, name, failure.message ?: "调用失败"))
            store.appendMessage(
                GroupMessage(
                    id = "msg_" + UUID.randomUUID().toString().take(8),
                    groupId = group.id,
                    role = "assistant",
                    memberId = member.id,
                    memberName = name,
                    text = "（调用失败：${failure.message}）",
                    createdAt = System.currentTimeMillis(),
                    failed = true,
                ),
            )
        } else {
            val finalText = reply.toString().trim()
            onEvent(GroupEvent.MemberFinished(member.id, name, finalText))
            store.appendMessage(
                GroupMessage(
                    id = "msg_" + UUID.randomUUID().toString().take(8),
                    groupId = group.id,
                    role = "assistant",
                    memberId = member.id,
                    memberName = name,
                    text = finalText.ifBlank { "（本轮没有输出）" },
                    createdAt = System.currentTimeMillis(),
                    thinking = thinking.toString().trim().ifBlank { null },
                ),
            )
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

        val all = store.messages(groupId)
        // 最后一条用户消息定义了「当前轮」；重试只覆盖本轮，不吃进本轮已落盘的回复
        val lastUserIdx = all.indexOfLast { it.isUser }
        if (lastUserIdx < 0) {
            onEvent(GroupEvent.Notice("没有可以重试的用户消息"))
            return@withContext
        }
        val userMessage = all[lastUserIdx]
        val history = all.subList(0, lastUserIdx + 1)

        // 引用只有 preview（120 字），重试用它还原最接近的引用形态
        val quote = userMessage.quoteMemberId?.let { qid ->
            all.firstOrNull { it.id == qid }
        }
        // 半截/失败的旧记录删掉，避免聊天里堆两条同一成员的失败消息
        withContext(NonCancellable) {
            store.removeLastFailedMessage(groupId, memberId)
        }
        speakOne(group, member, history, userMessage.text, quote, onEvent)
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
                arr.put(JSONObject().put("role", "user").put("content", m.text))
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

    private fun systemPrompt(persona: Persona?, memberName: String, member: GroupMemberDef): String {
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
        val answer = runCatching { callOnce(endpoint, body) }.getOrNull() ?: return false
        return answer.contains("UNSUITABLE", ignoreCase = true)
    }

    private fun callOnce(endpoint: MemberEndpoint, body: JSONObject): String {
        val req = Request.Builder()
            .url("${endpoint.baseUrl}/chat/completions")
            .post(body.toString().toRequestBody(jsonMedia))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${endpoint.apiKey}")
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} ${text.take(200)}")
            val choices = JSONObject(text).optJSONArray("choices") ?: return ""
            return choices.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        }
    }

    // ---- 流式调用 ----------------------------------------------------------

    private suspend fun streamMember(
        endpoint: MemberEndpoint,
        persona: Persona?,
        member: GroupMemberDef,
        memberName: String,
        history: List<GroupMessage>,
        userText: String,
        quote: GroupMessage?,
        onDelta: suspend (String) -> Unit,
        onThinking: suspend (String) -> Unit,
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
        messages.put(JSONObject().put("role", "user").put("content", current))

        val full = JSONArray()
        full.put(JSONObject().put("role", "system").put("content", systemPrompt(persona, memberName, member)))
        for (i in 0 until messages.length()) {
            messages.optJSONObject(i)?.let { full.put(it) }
        }

        val body = JSONObject()
            .put("model", endpoint.model)
            .put("stream", true)
            .put("temperature", 0.7)
            // 兼容 OpenAI 标准端点：最后一块带 usage。不认识的端点会忽略它。
            .put("stream_options", JSONObject().put("include_usage", true))
            .put("messages", full)

        val req = Request.Builder()
            .url("${endpoint.baseUrl}/chat/completions")
            .post(body.toString().toRequestBody(jsonMedia))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${endpoint.apiKey}")
            .build()

        // 用户点「停止」时协程被取消，但 readUtf8Line 是阻塞的 socket 读——
        // 不主动断连的话读线程会一直挂到 readTimeout。协程一完成（含取消）
        // 就 cancel 连接，阻塞读立刻抛 IOException 退出。
        val call = http.newCall(req)
        val job = currentCoroutineContext()[Job]
        val onDone = job?.invokeOnCompletion { call.cancel() }
        var usage: TokenUsage? = null
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string()?.take(300).orEmpty()
                    throw IllegalStateException("HTTP ${resp.code} $err")
                }
                val source = resp.body?.source() ?: throw IllegalStateException("响应为空")
                while (true) {
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
                        usage = TokenUsage(
                            input = u.optLong("prompt_tokens"),
                            output = u.optLong("completion_tokens"),
                            cacheRead = u.optJSONObject("prompt_tokens_details")?.optLong("cached_tokens") ?: 0L,
                        )
                    }
                    val choices = obj.optJSONArray("choices") ?: continue
                    val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: continue
                    val piece = delta.optString("content", "")
                    if (piece.isNotEmpty()) onDelta(piece)
                    val reason = delta.optString("reasoning_content", "")
                    if (reason.isNotEmpty()) onThinking(reason)
                }
            }
        } finally {
            onDone?.dispose()
            call.cancel()
        }
        return usage
    }

    companion object {
        /** 单轮最多几个成员发言。免费池限速下，超过这个数基本只会换来 429。 */
        const val MAX_SPEAKERS = 3

        /** 带进上下文的历史条数。群聊越长，越需要压住 token。 */
        const val HISTORY_LIMIT = 24
    }
}
