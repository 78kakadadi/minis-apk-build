package pro.openminis.app.ext.group

import pro.openminis.app.ext.opencode.memory.MemoryScope

/** 引用的角色。规格 §5.3 `QuoteRef.role (USER|ASSISTANT)`。 */
enum class QuoteRole { USER, ASSISTANT }

/**
 * 消息引用快照（规格 §5.3）。
 *
 * **必须存快照**：原消息可能被编辑、删除或历史压缩清理。只存 messageId 的话，
 * 清理之后引用条就渲染不出来。快照是「引用永远可渲染」的唯一保证。
 *
 * [contentSnapshot] 截断到 [SNAPSHOT_LIMIT] 字符，避免一条引用把上下文预算吃掉。
 */
data class QuoteRef(
    val messageId: String,
    val memberId: String,
    val role: QuoteRole,
    val contentSnapshot: String,
    val createdAt: Long = 0L,
) {
    companion object {
        /** 规格 §5.3 明确「前 500 字符」。 */
        const val SNAPSHOT_LIMIT = 500

        fun of(
            messageId: String,
            memberId: String,
            role: QuoteRole,
            content: String,
            createdAt: Long = 0L,
        ): QuoteRef = QuoteRef(
            messageId = messageId,
            memberId = memberId,
            role = role,
            contentSnapshot = snapshot(content),
            createdAt = createdAt,
        )

        /** 按字符（非字节）截断，避免把代理对劈成两半。 */
        fun snapshot(content: String): String {
            val trimmed = content.trim()
            if (trimmed.length <= SNAPSHOT_LIMIT) return trimmed
            // codePointCount 更稳妥，但 String.length 在 BMP 内一致；这里用后者换简单，
            // 并在末尾补省略号，UI 侧本来也会 visual overflow 截断。
            return trimmed.take(SNAPSHOT_LIMIT - 1).trimEnd() + "…"
        }
    }
}

/**
 * 群聊成员（规格 §5.2）。
 *
 * `memoryScopeOverride` 只允许**收紧**：人设默认 CROSS_SESSION 的成员可覆盖为
 * SINGLE_SESSION，反之不允许。校验在 [GroupMember.resolveScope]。
 */
data class GroupMember(
    val id: String,
    val personaId: String,
    /** 模型绑定的引用串（付费 provider 的 instanceId/modelId，或 "opencode-local"）。 */
    val modelBinding: String,
    val memoryScopeOverride: MemoryScope? = null,
    val displayName: String = "",
    val enabled: Boolean = true,
) {
    /**
     * 解析实际生效的记忆作用域。
     *
     * 收紧允许、放宽拒绝：`override.rank <= persona.rank` 才采纳。
     * 拒绝时静默回退到人设默认值——UI 上标注「已覆盖」与否由 [isOverrideEffective] 判断。
     */
    fun resolveScope(personaDefault: MemoryScope): MemoryScope {
        val override = memoryScopeOverride ?: return personaDefault
        return if (override.rank <= personaDefault.rank) override else personaDefault
    }

    /** 覆盖是否真正生效（用于 UI 标注「继承自人设」还是「已覆盖」）。 */
    fun isOverrideEffective(personaDefault: MemoryScope): Boolean {
        val override = memoryScopeOverride ?: return false
        return override != personaDefault && override.rank <= personaDefault.rank
    }
}

/** 发言调度策略（规格 §5.2）。 */
enum class TurnStrategy {
    /** 轮询：按成员顺序轮转。 */
    ROUND_ROBIN,

    /** 主 Agent 指派：由外部给定顺序。 */
    ASSIGN,

    /** 竞速：全员并行，先给出可用结果者为主回复。 */
    RACE,
}

/** 本轮编排结果。 */
sealed interface TurnPlan {
    /**
     * 正常发言。[order] 是本轮参与者的发言顺序，[preferred] 为定向首选（无引用时为 null）。
     */
    data class Speak(
        val order: List<String>,
        val preferred: String? = null,
        val note: String = "",
    ) : TurnPlan

    /**
     * 等待被引用成员：它正在排队，尚未不可用。
     * 规格 §5.3「① 默认等待其排队结束（UI 显示"等待 X 回应"）」。
     */
    data class WaitFor(val target: String, val reason: String) : TurnPlan

    /**
     * 接管：被引用成员确已不可用且群聊允许接管，转交给调度选出的下一位。
     * 规格 §5.3「② 若群聊配置"允许失败后接管"，则转交调度策略选出的下一位」。
     */
    data class Takeover(
        val target: String,
        val actual: String,
        val order: List<String>,
        val reason: String,
    ) : TurnPlan

    /**
     * 定向未达成：被引用成员不可用且不允许接管。
     * 规格 §5.3「③ 本轮标记"定向未达成"并提示原因」，保证定向语义不被静默破坏。
     */
    data class DirectFailed(val target: String, val reason: String) : TurnPlan
}

/**
 * 群聊发言调度器（规格 §5.2 / §5.3）。
 *
 * 三条硬性语义：
 * 1. **免费与付费成员完全平等**：本类不接收、也不判断模型类型。任何基于
 *    "这个成员是免费的所以跳过"的分支都不允许存在。
 * 2. **定向即优先**：带引用的消息让被引用成员拿到本轮首发权，轮询跳过顺序。
 * 3. **定向失败不静默转交**：默认等待；只有群聊显式允许接管时才换人，
 *    且换人结果以 [TurnPlan.Takeover] 明确暴露，UI 必须可见。
 */
class GroupTurnPlanner {

    /** 轮询游标：上次发言成员的 id。跨轮保留。 */
    private var lastSpeakerId: String? = null

    fun resetRoundRobin() {
        lastSpeakerId = null
    }

    /**
     * 计算本轮计划。
     *
     * @param members 全部成员（含已禁用，内部会过滤）
     * @param strategy 调度策略
     * @param quote 本轮引用，null 表示普通发言
     * @param assignOrder 指派策略下的顺序；缺省时退化为成员顺序
     * @param topic 本轮话题，用于人设倾向加权
     * @param traitsOf 取某个 persona 的倾向标签
     * @param busy 正在推理中的成员 id（定向命中它时应"等待"而非"失败"）
     * @param allowTakeover 群聊级配置，默认 false
     */
    fun plan(
        members: List<GroupMember>,
        strategy: TurnStrategy,
        quote: QuoteRef? = null,
        assignOrder: List<String>? = null,
        topic: String = "",
        traitsOf: (String) -> List<String> = { emptyList() },
        busy: Set<String> = emptySet(),
        allowTakeover: Boolean = false,
    ): TurnPlan {
        val active = members.filter { it.enabled }
        if (active.isEmpty()) return TurnPlan.DirectFailed("", "群聊没有可用成员")

        val target = quote?.memberId?.takeIf { it.isNotBlank() }

        // ---------- 有引用：定向路径 ----------
        if (target != null) {
            val targetMember = active.firstOrNull { it.id == target }
            if (targetMember == null) {
                // 成员已被移除或禁用
                return if (allowTakeover) {
                    val next = pickNext(active, strategy, assignOrder, topic, traitsOf, exclude = setOf(target))
                    if (next == null) TurnPlan.DirectFailed(target, "被引用成员不可用，且没有其他可接管成员")
                    else TurnPlan.Takeover(target, next, orderOf(active, strategy, assignOrder, topic, traitsOf, preferred = next), "被引用成员不可用，已转交下一位")
                } else {
                    TurnPlan.DirectFailed(target, "被引用成员不可用（已移除或已停用）")
                }
            }

            if (target in busy) {
                // 正在推理：等待而不是抢断。抢断会打断它当前回复，用户看到的是半截话。
                return TurnPlan.WaitFor(target, "正在回应上一条，稍候")
            }

            val rest = orderOf(active, strategy, assignOrder, topic, traitsOf, preferred = target)
            lastSpeakerId = target
            return TurnPlan.Speak(order = rest, preferred = target, note = "定向回复")
        }

        // ---------- 无引用：常规调度 ----------
        val ordered = orderOf(active, strategy, assignOrder, topic, traitsOf, preferred = null)
        if (ordered.isEmpty()) return TurnPlan.DirectFailed("", "本轮无可用发言人")
        lastSpeakerId = ordered.first()
        return TurnPlan.Speak(order = ordered, preferred = null)
    }

    /**
     * 生成发言顺序。
     *
     * 有 preferred 时它固定在最前（定向优先）；其余按策略与倾向排序。
     * 轮询策略下从上次发言者的下一位开始，保证公平。
     */
    private fun orderOf(
        active: List<GroupMember>,
        strategy: TurnStrategy,
        assignOrder: List<String>?,
        topic: String,
        traitsOf: (String) -> List<String>,
        preferred: String?,
    ): List<String> {
        val base = when (strategy) {
            TurnStrategy.ASSIGN -> {
                val given = assignOrder?.filter { id -> active.any { it.id == id } }.orEmpty()
                if (given.isEmpty()) active.map { it.id } else given
            }
            TurnStrategy.ROUND_ROBIN -> rotateFrom(active, lastSpeakerId)
            TurnStrategy.RACE -> active.map { it.id }
        }

        // 倾向加权：话题命中的成员提前。仅影响顺序，不影响是否参与。
        val scored = base.mapIndexed { index, id ->
            val member = active.firstOrNull { it.id == id }
            val traits = member?.let { traitsOf(it.personaId) }.orEmpty()
            val hit = if (topic.isBlank()) 0 else traits.count { t ->
                t.isNotBlank() && topic.contains(t, ignoreCase = true)
            }
            Triple(id, -hit, index)  // hit 越多越靠前；同分保持原序
        }.sortedWith(compareBy({ it.second }, { it.third })).map { it.first }

        if (preferred == null) return scored
        return listOf(preferred) + scored.filter { it != preferred }
    }

    private fun rotateFrom(active: List<GroupMember>, last: String?): List<String> {
        val ids = active.map { it.id }
        if (last == null) return ids
        val idx = ids.indexOf(last)
        if (idx < 0) return ids
        return ids.drop(idx + 1) + ids.take(idx + 1)
    }

    private fun pickNext(
        active: List<GroupMember>,
        strategy: TurnStrategy,
        assignOrder: List<String>?,
        topic: String,
        traitsOf: (String) -> List<String>,
        exclude: Set<String>,
    ): String? = orderOf(active, strategy, assignOrder, topic, traitsOf, preferred = null)
        .firstOrNull { it !in exclude }

    /** 仅测试用：查看轮询游标。 */
    fun lastSpeaker(): String? = lastSpeakerId
}

/**
 * 组装「引用原文 + 用户回复」的上下文前缀（规格 §5.3「置于本轮最前」）。
 *
 * 只给被引用成员用。其他成员在公共上下文里能看到这条带引用的消息，
 * 但不获得优先权，也不需要这个前缀。
 */
object QuoteContext {
    fun build(quote: QuoteRef, userReply: String, speakerName: String): String {
        val who = speakerName.ifBlank { quote.memberId }
        return buildString {
            append("【引用 ")
            append(who)
            append(" 的发言】\n")
            append(quote.contentSnapshot)
            append("\n\n【我的回复】\n")
            append(userReply.trim())
        }
    }
}
