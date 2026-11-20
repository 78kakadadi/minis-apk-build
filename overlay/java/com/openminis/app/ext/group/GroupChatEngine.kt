package com.openminis.app.ext.group

import com.openminis.app.ext.persona.Persona
import com.openminis.app.ext.persona.PersonaRepository
import kotlinx.coroutines.Dispatchers
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
    data class MemberFinished(val memberId: String, val name: String, val text: String) : GroupEvent
    data class MemberFailed(val memberId: String, val name: String, val reason: String) : GroupEvent
    data class Notice(val text: String) : GroupEvent
}

/**
 * 群聊引擎：把「一条用户消息」变成「若干成员依次发言」。
 *
 * 模型调用走本机 OpenAI 兼容桥接（免费通道）。这样群聊与单聊用**同一条**
 * 模型链路，不存在"群聊能用单聊不能用"的分裂状态。
 *
 * 三个刻意的取舍：
 *  1. **串行而非并行**：成员之间要看到彼此本轮发言（"我同意架构师的意见"），
 *     并行会让它们互相看不见，回复变成三份互不相关的独白。
 *  2. **每成员一条独立会话**：人设写进 system，历史只带公共群消息，
 *     成员不会污染彼此的私有上下文。
 *  3. **单轮上限 [MAX_SPEAKERS]**：免费池有速率限制，全员长回复很容易撞 429，
 *     而用户真正需要的通常只是前两三个视角。
 */
class GroupChatEngine(
    private val store: GroupStore,
    private val personas: PersonaRepository,
    private val baseUrl: () -> String?,
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
        val base = baseUrl()
        if (base.isNullOrBlank()) {
            onEvent(GroupEvent.Notice("免费 Token 通道未启用：请先到「设置 › 免费 Token 通道」启用"))
            return@withContext
        }
        if (text.isBlank()) return@withContext

        val group = store.groups().firstOrNull { it.id == groupId } ?: run {
            onEvent(GroupEvent.Notice("群不存在"))
            return@withContext
        }
        val members = store.members(groupId).filter { it.enabled }
        if (members.isEmpty()) {
            onEvent(GroupEvent.Notice("这个群还没有成员：先「添加成员」"))
            return@withContext
        }

        val now = System.currentTimeMillis()
        val userMessage = GroupMessage(
            id = "msg_" + UUID.randomUUID().toString().take(8),
            groupId = groupId,
            role = "user",
            memberId = null,
            memberName = null,
            text = text.trim(),
            createdAt = now,
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

        // 去掉刚写入的这条 user 消息：它在 streamMember 里会作为 current 单独追加，
        // 不剔除就会把同一句话发两遍（模型会以为用户催了一次）
        val history = store.messages(groupId).dropLast(1).takeLast(12)
        var spoken = 0
        for (memberId in order) {
            if (spoken >= MAX_SPEAKERS) break
            val member = members.firstOrNull { it.id == memberId } ?: continue
            val persona = personas.get(member.personaId)
            val name = member.displayName.ifBlank { persona?.name ?: "成员" }

            onEvent(GroupEvent.MemberStarted(member.id, name))
            val reply = StringBuilder()
            val failure = runCatching {
                streamMember(
                    base = base,
                    persona = persona,
                    member = member,
                    history = history,
                    userText = text,
                    quote = quote,
                    onDelta = { chunk ->
                        reply.append(chunk)
                        onEvent(GroupEvent.MemberDelta(member.id, chunk))
                    },
                )
            }.exceptionOrNull()

            if (failure != null) {
                onEvent(GroupEvent.MemberFailed(member.id, name, failure.message ?: "调用失败"))
                store.appendMessage(
                    GroupMessage(
                        id = "msg_" + UUID.randomUUID().toString().take(8),
                        groupId = groupId,
                        role = "assistant",
                        memberId = member.id,
                        memberName = name,
                        text = "（调用失败：${failure.message}）",
                        createdAt = System.currentTimeMillis(),
                        failed = true,
                    ),
                )
            } else {
                onEvent(GroupEvent.MemberFinished(member.id, name, reply.toString()))
                store.appendMessage(
                    GroupMessage(
                        id = "msg_" + UUID.randomUUID().toString().take(8),
                        groupId = groupId,
                        role = "assistant",
                        memberId = member.id,
                        memberName = name,
                        text = reply.toString(),
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
            spoken += 1
        }
    }

    private fun nameOf(members: List<GroupMemberDef>, id: String): String =
        members.firstOrNull { it.id == id }?.displayName?.takeIf { it.isNotBlank() } ?: id

    private suspend fun streamMember(
        base: String,
        persona: Persona?,
        member: GroupMemberDef,
        history: List<GroupMessage>,
        userText: String,
        quote: GroupMessage?,
        onDelta: suspend (String) -> Unit,
    ) {
        val messages = JSONArray()
        history.forEach { m ->
            if (m.isUser) {
                messages.put(JSONObject().put("role", "user").put("content", m.text))
            } else if (m.memberName != null) {
                messages.put(
                    JSONObject()
                        .put("role", "assistant")
                        .put("content", "【${m.memberName}】${m.text}"),
                )
            }
        }
        val current = buildString {
            if (quote != null && quote.memberName != null) {
                append("【引用 ${quote.memberName} 的发言】\n")
                append(quote.text.take(QuoteRef.SNAPSHOT_LIMIT))
                append("\n\n【我的回复】\n")
            }
            append(userText)
        }
        messages.put(JSONObject().put("role", "user").put("content", current))

        val system = buildString {
            append(persona?.systemPrompt?.trim().orEmpty())
            if (isEmpty()) append("你是一个可靠的中文助手。")
            append("\n\n你正在一个多人群聊里发言。")
            append("只输出你这一轮的发言内容，不要写「${member.displayName}：」这样的前缀。")
            append("其他成员的发言会以【名字】标注，你可以回应他们，但不要替他们说话。")
        }

        val body = JSONObject()
            .put("model", member.modelId)
            .put("stream", true)
            .put("messages", messages)
            .put("temperature", 0.7)

        val req = Request.Builder()
            .url("$base/chat/completions")
            .post(body.toString().toRequestBody(jsonMedia))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer local")
            .build()

        http.newCall(req).execute().use { resp ->
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
                val choices = obj.optJSONArray("choices") ?: continue
                val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: continue
                val piece = delta.optString("content", "")
                if (piece.isNotEmpty()) onDelta(piece)
            }
        }
    }

    companion object {
        /** 单轮最多几个成员发言。免费池限速下，超过这个数基本只会换来 429。 */
        const val MAX_SPEAKERS = 3
    }
}
