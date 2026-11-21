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
    /**
     * 允许这个成员调用的工具 id（见 [GroupTools]）。
     *
     * `null` = 没设置过，等价于全部工具——旧群成员升级后不会突然失去能力；
     * 显式空列表表示「禁用全部工具」。为什么不用空列表表示默认：那样无法区分
     * 「用户主动关掉全部」和「从没设置过」，升级后会静默把工具关掉。
     */
    val tools: List<String>? = null,
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
    /**
     * 正在流式输出中的草稿。每 0.7 秒覆盖一次同 id 的记录，用来支持
     * 「输出到一半退出界面，再进来还能看到已输出的部分」。
     * 界面上有活跃的流式气泡时这些草稿会被隐藏，避免同一条回复出现两次。
     */
    val streaming: Boolean = false,
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

    /**
     * 全进程一把锁，而不是用 `this`。
     *
     * `GroupStore(context)` 每次进群聊界面都会 new 一个实例，实例锁挡不住另一个
     * 实例；而群聊是**多个成员同时流式输出**，每次写消息都是「读整份 JSON →
     * 改 → 写回」。没有这把锁时后写的人会把先写的人整段覆盖掉——真机上的表现
     * 就是「发完消息我的消息没了」「某个 AI 的回复突然消失」。
     */
    private inline fun <T> lock(block: () -> T): T = synchronized(LOCK, block)

    private companion object {
        val LOCK = Any()
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

    fun createGroup(name: String): GroupDef = lock {
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
        group
    }

    fun renameGroup(groupId: String, name: String) {
        updateGroup(groupId) { it.put("name", name.trim().ifBlank { "新群聊" }) }
    }

    fun deleteGroup(groupId: String) = lock {
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
        Unit
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
        lock {
            val root = readIndex()
            val arr = root.optJSONArray("groups") ?: return@lock
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.str("id") == groupId) block(o)
            }
            writeIndex(root)
        }
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
                tools = o.optJSONArray("tools")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
                },
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
    ): GroupMemberDef = lock {
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
        member
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
        val tools = member.tools
        if (tools == null) {
            // 不写 key = 全部允许。必须 remove，否则重新设置成「全部」时旧数组会留着。
            o.remove("tools")
        } else {
            o.put("tools", JSONArray(tools))
        }
        o.put("avatarLabel", member.avatarLabel)
        o.put("avatarColor", member.avatarColor)
    }

    fun updateMember(memberId: String, block: (JSONObject) -> Unit) {
        lock {
            val root = readIndex()
            val arr = root.optJSONArray("members") ?: return@lock
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.str("id") == memberId) block(o)
            }
            writeIndex(root)
        }
    }

    fun setMemberEnabled(memberId: String, enabled: Boolean) =
        updateMember(memberId) { it.put("enabled", enabled) }

    fun setMemberReasoning(memberId: String, reasoning: String) =
        updateMember(memberId) { it.put("reasoning", reasoning) }

    /**
     * 设置允许调用的工具。
     *
     * `null` 是「全部」而不是「无」——UI 上点「全选」时传 null，这样将来新增
     * 工具会自动出现在这个成员的能力里，不需要用户再回来勾一次。
     */
    fun setMemberTools(memberId: String, tools: List<String>?) =
        updateMember(memberId) {
            if (tools == null) {
                it.remove("tools")
            } else {
                it.put("tools", JSONArray(tools))
            }
        }

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
        lock {
            val root = readIndex()
            val arr = root.optJSONArray("members") ?: return@lock
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.str("id") != memberId) kept.put(o)
            }
            root.put("members", kept)
            writeIndex(root)
        }
    }

    /** 引用计数：人设删除前提示"有 N 个成员在用"。 */
    fun countMemberUsage(personaId: String): Int =
        readIndex().optJSONArray("members").toList { it }.count { it.str("personaId") == personaId }

    // ---- 消息 -------------------------------------------------------------

    fun messages(groupId: String): List<GroupMessage> = lock {
        val f = messagesFile(groupId)
        if (!f.exists()) return@lock emptyList()
        val root = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return@lock emptyList()
        root.optJSONArray("messages").toList { o ->
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
                streaming = o.optBoolean("streaming", false),
            )
        }
    }

    /** 主页列表用：最后一条消息的预览文本。 */
    fun lastMessage(groupId: String): GroupMessage? = messages(groupId).lastOrNull()

    fun appendMessage(message: GroupMessage) = lock {
        mutateMessages(message.groupId) { it.put(messageJson(message)) }
    }

    /**
     * 按 id 覆盖写入：有就替换、没有就追加。
     *
     * 流式输出靠它实现「草稿实时落盘」——每 0.7 秒把同一条消息用新文本覆盖，
     * 而不是每次追加一条新的（那样会堆出一串半截消息）。
     */
    fun upsertMessage(message: GroupMessage) = lock {
        mutateMessages(message.groupId) { arr ->
            var replaced = false
            for (i in 0 until arr.length()) {
                if (arr.optJSONObject(i)?.optString("id") == message.id) {
                    arr.put(i, messageJson(message))
                    replaced = true
                    break
                }
            }
            if (!replaced) arr.put(messageJson(message))
        }
    }

    /**
     * 重试前清掉该成员最近一条失败/半截的记录，聊天里不堆重复的失败消息。
     * 成功过的最终答复不动——那是要进上下文的历史。
     */
    fun removeLastFailedMessage(groupId: String, memberId: String) = lock {
        val f = messagesFile(groupId)
        if (!f.exists()) return@lock
        runCatching {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("messages") ?: return@runCatching
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
                writeAtomically(f, root.toString())
            }
        }
    }

    fun clearMessages(groupId: String) = lock {
        runCatching { messagesFile(groupId).delete() }
        Unit
    }

    /** 删掉指定消息（流式草稿没出字时清理用）。 */
    fun removeMessage(groupId: String, messageId: String) = lock {
        mutateMessages(groupId) { arr ->
            for (i in arr.length() - 1 downTo 0) {
                if (arr.optJSONObject(i)?.optString("id") == messageId) arr.remove(i)
            }
        }
    }

    // ---- 内部 -------------------------------------------------------------

    private fun messagesFile(groupId: String) = File(dir, "$groupId.messages.json")

    /** 读 - 改 - 写三件套只在锁内调用。 */
    private fun mutateMessages(groupId: String, block: (JSONArray) -> Unit) {
        val f = messagesFile(groupId)
        val root = if (f.exists()) {
            runCatching { JSONObject(f.readText()) }.getOrNull() ?: JSONObject()
        } else JSONObject()
        val arr = root.optJSONArray("messages") ?: JSONArray()
        block(arr)
        root.put("messages", arr)
        writeAtomically(f, root.toString())
    }

    private fun messageJson(message: GroupMessage): JSONObject = JSONObject()
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
        .put("interrupted", message.interrupted)
        .put("streaming", message.streaming)

    /**
     * 先写临时文件再改名。
     *
     * 直接 `writeText` 是「截断 + 重写」：写到一半被杀（用户切走、进程回收）就
     * 留下半截 JSON，下次读不出来整段消息就没了。rename 在同一分区是原子的。
     */
    private fun writeAtomically(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            // rename 失败（极少见）：退回直接写，至少别丢内容
            runCatching { f.writeText(text) }
            tmp.delete()
        }
    }

    private fun readIndex(): JSONObject {
        if (!indexFile.exists()) return JSONObject()
        return runCatching { JSONObject(indexFile.readText()) }.getOrNull() ?: JSONObject()
    }

    private fun writeIndex(root: JSONObject) {
        writeAtomically(indexFile, root.toString())
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
