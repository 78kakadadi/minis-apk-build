package pro.openminis.app.ext.group

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 一个群。成员绑定「人设 + 模型」，与上游会话完全解耦。 */
data class GroupDef(
    val id: String,
    val name: String,
    val strategy: TurnStrategy = TurnStrategy.ROUND_ROBIN,
    val allowTakeover: Boolean = false,
    val createdAt: Long = 0L,
    /**
     * 成员发言前先自检「这个任务适合我吗」，不适合就跳过本轮。
     * 群设置里可关；关掉后所有成员都会发言。
     */
    val autoTaskFit: Boolean = true,
)

/**
 * 群成员。`modelId` 是模型 id；[source] 决定去哪个端点请求：
 *  - `free`：本机免费通道的桥接（OpenCode）
 *  - `provider`：用户自己存的 API（[providerInstanceId] + `modelId`）
 */
data class GroupMemberDef(
    val id: String,
    val groupId: String,
    val personaId: String,
    val modelId: String,
    val displayName: String,
    val enabled: Boolean = true,
    val source: String = SOURCE_FREE,
    val providerInstanceId: String = "",
    /** low / medium / high —— 只影响这个成员的思考强度，不影响别人。 */
    val reasoning: String = "medium",
    /** 头像字母（留空则按模型自动取厂商标识）。 */
    val avatarLabel: String = "",
    /** 头像底色 ARGB（0 = 按模型自动配色）。 */
    val avatarColor: Int = 0,
) {
    companion object {
        const val SOURCE_FREE = "free"
        const val SOURCE_PROVIDER = "provider"
    }
}

/** 群消息。`memberId` 为空表示用户消息。 */
data class GroupMessage(
    val id: String,
    val groupId: String,
    val role: String,
    val memberId: String?,
    val memberName: String?,
    val text: String,
    val createdAt: Long,
    val quoteMemberId: String? = null,
    val quotePreview: String? = null,
    val failed: Boolean = false,
    /** 该成员的思考过程。只给用户看，绝不进入其他成员的上下文。 */
    val thinking: String? = null,
    /** 成员自检「不适合自己」而跳过本轮。 */
    val skipped: Boolean = false,
    /** 用户主动打断时留下的半截回复。 */
    val interrupted: Boolean = false,
) {
    val isUser: Boolean get() = role == "user"
}

/**
 * 群聊持久化：一个 JSON 文件存群与成员，每个群一个消息文件。
 *
 * 为什么不用 Room：群聊是新增功能，它的表结构不该混进上游 `minis.db`——
 * 一旦用户降级到不含这些表的版本，Room 的 schema 校验会让整个库打不开，
 * 连带正常聊天记录一起完蛋。独立文件最坏情况是"群聊数据丢失"，不会波及主库。
 */
class GroupStore(context: Context) {

    private val dir = File(context.filesDir, "minis-ext/groups")
    private val indexFile = File(dir, "groups.json")

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    // ---- 群 ---------------------------------------------------------------

    fun groups(): List<GroupDef> = readIndex().optJSONArray("groups").toList { o ->
        GroupDef(
            id = o.str("id"),
            name = o.str("name"),
            strategy = runCatching { TurnStrategy.valueOf(o.str("strategy")) }
                .getOrDefault(TurnStrategy.ROUND_ROBIN),
            allowTakeover = o.optBoolean("allowTakeover", false),
            createdAt = o.optLong("createdAt"),
            autoTaskFit = o.optBoolean("autoTaskFit", true),
        )
    }.sortedByDescending { it.createdAt }.filter { it.id.isNotBlank() }

    fun group(groupId: String): GroupDef? = groups().firstOrNull { it.id == groupId }

    fun createGroup(name: String): GroupDef {
        val group = GroupDef(
            id = "g_" + UUID.randomUUID().toString().take(8),
            name = name.trim().ifBlank { "新群聊" },
            createdAt = System.currentTimeMillis(),
        )
        val root = readIndex()
        val arr = root.optJSONArray("groups") ?: JSONArray()
        arr.put(
            JSONObject()
                .put("id", group.id)
                .put("name", group.name)
                .put("strategy", group.strategy.name)
                .put("allowTakeover", group.allowTakeover)
                .put("autoTaskFit", group.autoTaskFit)
                .put("createdAt", group.createdAt),
        )
        root.put("groups", arr)
        writeIndex(root)
        return group
    }

    fun renameGroup(groupId: String, name: String) {
        updateGroup(groupId) { it.put("name", name.trim().ifBlank { "新群聊" }) }
    }

    fun deleteGroup(groupId: String) {
        val root = readIndex()
        val arr = root.optJSONArray("groups") ?: JSONArray()
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.str("id") != groupId) kept.put(o)
        }
        root.put("groups", kept)
        // 成员一起清掉，否则删除后重建同 id 群会"继承"幽灵成员
        val members = root.optJSONArray("members") ?: JSONArray()
        val keptMembers = JSONArray()
        for (i in 0 until members.length()) {
            val o = members.optJSONObject(i) ?: continue
            if (o.str("groupId") != groupId) keptMembers.put(o)
        }
        root.put("members", keptMembers)
        writeIndex(root)
        runCatching { messagesFile(groupId).delete() }
    }

    fun setStrategy(groupId: String, strategy: TurnStrategy) {
        updateGroup(groupId) { it.put("strategy", strategy.name) }
    }

    fun setAllowTakeover(groupId: String, allow: Boolean) {
        updateGroup(groupId) { it.put("allowTakeover", allow) }
    }

    fun setAutoTaskFit(groupId: String, enabled: Boolean) {
        updateGroup(groupId) { it.put("autoTaskFit", enabled) }
    }

    private fun updateGroup(groupId: String, block: (JSONObject) -> Unit) {
        val root = readIndex()
        val arr = root.optJSONArray("groups") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.str("id") == groupId) block(o)
        }
        writeIndex(root)
    }

    // ---- 成员 -------------------------------------------------------------

    fun members(groupId: String): List<GroupMemberDef> =
        readIndex().optJSONArray("members").toList { o ->
            GroupMemberDef(
                id = o.str("id"),
                groupId = o.str("groupId"),
                personaId = o.str("personaId"),
                modelId = o.str("modelId"),
                displayName = o.str("displayName"),
                enabled = o.optBoolean("enabled", true),
                source = o.optString("source").ifBlank { GroupMemberDef.SOURCE_FREE },
                providerInstanceId = o.optString("providerInstanceId"),
                reasoning = o.optString("reasoning").ifBlank { "medium" },
                avatarLabel = o.optString("avatarLabel"),
                avatarColor = o.optInt("avatarColor", 0),
            )
        }.filter { it.groupId == groupId && it.id.isNotBlank() }

    fun addMember(
        groupId: String,
        personaId: String,
        modelId: String,
        displayName: String,
        source: String = GroupMemberDef.SOURCE_FREE,
        providerInstanceId: String = "",
        reasoning: String = "medium",
    ): GroupMemberDef {
        val member = GroupMemberDef(
            id = "m_" + UUID.randomUUID().toString().take(8),
            groupId = groupId,
            personaId = personaId,
            modelId = modelId,
            displayName = displayName,
            source = source,
            providerInstanceId = providerInstanceId,
            reasoning = reasoning,
        )
        val root = readIndex()
        val arr = root.optJSONArray("members") ?: JSONArray()
        arr.put(JSONObject().apply { writeMember(this, member) })
        root.put("members", arr)
        writeIndex(root)
        return member
    }

    private fun writeMember(o: JSONObject, member: GroupMemberDef) {
        o.put("id", member.id)
        o.put("groupId", member.groupId)
        o.put("personaId", member.personaId)
        o.put("modelId", member.modelId)
        o.put("displayName", member.displayName)
        o.put("enabled", member.enabled)
        o.put("source", member.source)
        o.put("providerInstanceId", member.providerInstanceId)
        o.put("reasoning", member.reasoning)
        o.put("avatarLabel", member.avatarLabel)
        o.put("avatarColor", member.avatarColor)
    }

    fun updateMember(memberId: String, block: (JSONObject) -> Unit) {
        val root = readIndex()
        val arr = root.optJSONArray("members") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.str("id") == memberId) block(o)
        }
        writeIndex(root)
    }

    fun setMemberEnabled(memberId: String, enabled: Boolean) =
        updateMember(memberId) { it.put("enabled", enabled) }

    fun setMemberReasoning(memberId: String, reasoning: String) =
        updateMember(memberId) { it.put("reasoning", reasoning) }

    fun setMemberAvatar(memberId: String, label: String, color: Int) =
        updateMember(memberId) {
            it.put("avatarLabel", label)
            it.put("avatarColor", color)
        }

    fun setMemberModel(
        memberId: String,
        modelId: String,
        source: String,
        providerInstanceId: String,
    ) = updateMember(memberId) {
        it.put("modelId", modelId)
        it.put("source", source)
        it.put("providerInstanceId", providerInstanceId)
    }

    fun removeMember(memberId: String) {
        val root = readIndex()
        val arr = root.optJSONArray("members") ?: return
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.str("id") != memberId) kept.put(o)
        }
        root.put("members", kept)
        writeIndex(root)
    }

    /** 引用计数：人设删除前提示"有 N 个成员在用"。 */
    fun countMemberUsage(personaId: String): Int =
        readIndex().optJSONArray("members").toList { it }.count { it.str("personaId") == personaId }

    // ---- 消息 -------------------------------------------------------------

    fun messages(groupId: String): List<GroupMessage> {
        val f = messagesFile(groupId)
        if (!f.exists()) return emptyList()
        val root = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return emptyList()
        return root.optJSONArray("messages").toList { o ->
            GroupMessage(
                id = o.str("id"),
                groupId = groupId,
                role = o.str("role"),
                memberId = o.optString("memberId").ifBlank { null },
                memberName = o.optString("memberName").ifBlank { null },
                text = o.optString("text"),
                createdAt = o.optLong("createdAt"),
                quoteMemberId = o.optString("quoteMemberId").ifBlank { null },
                quotePreview = o.optString("quotePreview").ifBlank { null },
                failed = o.optBoolean("failed", false),
                thinking = o.optString("thinking").ifBlank { null },
                skipped = o.optBoolean("skipped", false),
                interrupted = o.optBoolean("interrupted", false),
            )
        }
    }

    /** 主页列表用：最后一条消息的预览文本。 */
    fun lastMessage(groupId: String): GroupMessage? = messages(groupId).lastOrNull()

    fun appendMessage(message: GroupMessage) {
        val f = messagesFile(message.groupId)
        val root = if (f.exists()) {
            runCatching { JSONObject(f.readText()) }.getOrNull() ?: JSONObject()
        } else JSONObject()
        val arr = root.optJSONArray("messages") ?: JSONArray()
        arr.put(
            JSONObject()
                .put("id", message.id)
                .put("role", message.role)
                .put("memberId", message.memberId ?: "")
                .put("memberName", message.memberName ?: "")
                .put("text", message.text)
                .put("createdAt", message.createdAt)
                .put("quoteMemberId", message.quoteMemberId ?: "")
                .put("quotePreview", message.quotePreview ?: "")
                .put("failed", message.failed)
                .put("thinking", message.thinking ?: "")
                .put("skipped", message.skipped)
                .put("interrupted", message.interrupted),
        )
        root.put("messages", arr)
        f.parentFile?.mkdirs()
        f.writeText(root.toString())
    }

    /**
     * 重试前清掉该成员最近一条失败/半截的记录，聊天里不堆重复的失败消息。
     * 成功过的最终答复不动——那是要进上下文的历史。
     */
    fun removeLastFailedMessage(groupId: String, memberId: String) {
        val f = messagesFile(groupId)
        if (!f.exists()) return
        runCatching {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("messages") ?: return
            var idx = -1
            for (i in arr.length() - 1 downTo 0) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("memberId") == memberId && o.optString("role") == "assistant" &&
                    (o.optBoolean("failed") || o.optBoolean("interrupted"))
                ) {
                    idx = i
                    break
                }
            }
            if (idx >= 0) {
                arr.remove(idx)
                f.writeText(root.toString())
            }
        }
    }

    fun clearMessages(groupId: String) {
        runCatching { messagesFile(groupId).delete() }
    }

    // ---- 内部 -------------------------------------------------------------

    private fun messagesFile(groupId: String) = File(dir, "$groupId.messages.json")

    private fun readIndex(): JSONObject {
        if (!indexFile.exists()) return JSONObject()
        return runCatching { JSONObject(indexFile.readText()) }.getOrNull() ?: JSONObject()
    }

    private fun writeIndex(root: JSONObject) {
        indexFile.parentFile?.mkdirs()
        indexFile.writeText(root.toString())
    }
}

// ---- JSON 小工具 ----------------------------------------------------------

private fun JSONObject.str(key: String): String = optString(key).ifBlank { "" }

private fun <T> JSONArray?.toList(map: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    val out = ArrayList<T>(length())
    for (i in 0 until length()) {
        val o = optJSONObject(i) ?: continue
        runCatching { out.add(map(o)) }
    }
    return out
}
