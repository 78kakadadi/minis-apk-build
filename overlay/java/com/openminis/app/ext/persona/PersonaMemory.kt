package com.openminis.app.ext.persona

import com.openminis.app.ext.opencode.memory.MemoryConfig
import com.openminis.app.ext.opencode.memory.MemoryScope

/**
 * 记忆条目的种类（规格 §4.5「记忆内容」）。
 *
 * 只存摘要与关键事实，**不存完整 transcript**。完整对话一是不便宜（每轮注入会顶到窗口），
 * 二是隐私面太大（用户随口说的一句私事会被永久带着走）。
 */
enum class MemoryKind {
    /** LLM 压缩后的对话摘要。每个 (persona, scope, session) 至多一条活跃摘要。 */
    SUMMARY,

    /** 关键事实：用户偏好、项目背景、已确认决策。条数受 maxInjectedFacts 限制。 */
    FACT,
}

/**
 * (人设, 作用域, 会话) 三元组的隔离键（规格 §4.5「隔离：记忆按 (personaId, scope) 严格隔离」）。
 *
 * [sessionId] 的取值规则就是这个类的全部意义：
 *  - `CROSS_SESSION` → **必须为 null**，表示"人设级共享库"；
 *  - `SINGLE_SESSION` → **必须非 null**，且为具体会话 ID，表示"只属于这个会话"；
 *  - `NONE` → 不产生任何 key（[MemoryStore] 会拒绝写入）。
 *
 * 为什么把 sessionId 编码进 key 而不是在查询时过滤：如果 CROSS_SESSION 的条目
 * 也带 sessionId，那么"同一群聊里两个成员绑同一人设且均 CROSS_SESSION → 共享记忆库"
 * 这条就退化成按会话隔离，跨对话记忆永久失效，而不会有任何报错。
 */
data class MemoryKey(
    val personaId: String,
    val scope: MemoryScope,
    val sessionId: String?,
) {
    init {
        // 构造期就拦住非法组合，比运行期默默用错库安全
        when (scope) {
            MemoryScope.CROSS_SESSION -> require(sessionId == null) {
                "CROSS_SESSION 的记忆是人设级共享库，不能绑定具体会话"
            }
            MemoryScope.SINGLE_SESSION -> require(!sessionId.isNullOrBlank()) {
                "SINGLE_SESSION 的记忆必须绑定具体会话"
            }
            MemoryScope.NONE -> require(sessionId == null) {
                "NONE 不保留记忆，不应构造 key"
            }
        }
    }

    companion object {
        fun forSession(personaId: String, scope: MemoryScope, sessionId: String?): MemoryKey? =
            when (scope) {
                MemoryScope.CROSS_SESSION -> MemoryKey(personaId, scope, null)
                MemoryScope.SINGLE_SESSION ->
                    sessionId?.takeIf { it.isNotBlank() }?.let { MemoryKey(personaId, scope, it) }
                MemoryScope.NONE -> null
            }
    }
}

/** 一条记忆。 */
data class MemoryEntry(
    val id: String,
    val key: MemoryKey,
    val kind: MemoryKind,
    val text: String,
    /** 产生该条目的会话轮次，用于"压缩强度随轮次递增"的加权淘汰。 */
    val turn: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    val personaId: String get() = key.personaId
    val scope: MemoryScope get() = key.scope
    val sessionId: String? get() = key.sessionId
}

/**
 * 记忆库（规格 §4.5）。
 *
 * 单一职责：按 [MemoryKey] 存取条目。不做摘要、不做注入决策——那些在
 * [PersonaMemoryManager] 里，因为它们需要模型调用与预算上下文。
 *
 * 线程安全：所有方法 `synchronized`。群聊里多个成员并发写记忆是常态
 * （并行流式发言时各自收尾都会写摘要），用 ConcurrentHashMap 只能保证
 * 单个操作原子，跨操作的"先查后写"仍会丢条目。
 */
class MemoryStore(
    private val idGen: () -> String = { "mem_" + java.util.UUID.randomUUID().toString().take(8) },
) {

    private val entries = LinkedHashMap<String, MemoryEntry>()

    /**
     * 写入一条记忆。返回 null 表示被拒绝（作用域为 NONE）。
     *
     * SUMMARY 是**替换语义**：同一 key 下只保留最新一条摘要，
     * 因为摘要是对累积对话的重新压缩，旧摘要的每一句都已包含在新摘要里。
     * 保留多条会让注入预算被同一份信息重复占用。
     */
    @Synchronized
    fun put(
        key: MemoryKey,
        kind: MemoryKind,
        text: String,
        turn: Int = 0,
        now: Long = 0L,
    ): MemoryEntry? {
        if (key.scope == MemoryScope.NONE) return null
        if (text.isBlank()) return null

        if (kind == MemoryKind.SUMMARY) {
            entries.entries.removeAll { (_, e) -> e.key == key && e.kind == MemoryKind.SUMMARY }
        }

        val entry = MemoryEntry(
            id = idGen(),
            key = key,
            kind = kind,
            text = text,
            turn = turn,
            createdAt = now,
            updatedAt = now,
        )
        entries[entry.id] = entry
        return entry
    }

    /** 取某个 key 下的全部条目，事实在前、按更新时间倒序（越新越重要）。 */
    @Synchronized
    fun list(key: MemoryKey): List<MemoryEntry> =
        entries.values
            .filter { it.key == key }
            .sortedWith(
                compareBy<MemoryEntry> { if (it.kind == MemoryKind.FACT) 0 else 1 }
                    .thenByDescending { it.updatedAt }
                    .thenByDescending { it.turn },
            )

    /** 删除单条（规格 §4.5「可单条删除」）。 */
    @Synchronized
    fun delete(entryId: String): Boolean = entries.remove(entryId) != null

    /**
     * 清空。`key` 为 null 时清空该人设的**所有**作用域（"一键清空"）。
     */
    @Synchronized
    fun clear(personaId: String, key: MemoryKey? = null): Int {
        val targets = entries.values.filter {
            it.personaId == personaId && (key == null || it.key == key)
        }
        targets.forEach { entries.remove(it.id) }
        return targets.size
    }

    /** 人设库卡片上显示的"记忆库条数"（跨所有会话，含单会话归档）。 */
    @Synchronized
    fun countOf(personaId: String): Int = entries.values.count { it.personaId == personaId }

    @Synchronized
    fun latestUpdate(personaId: String): Long? =
        entries.values.filter { it.personaId == personaId }.maxOfOrNull { it.updatedAt }

    @Synchronized
    fun all(): List<MemoryEntry> = entries.values.toList()

    /**
     * 会话结束时把 SINGLE_SESSION 记忆转为"归档"（规格 §4.5：结束落盘归档，
     * **不参与后续注入**）。
     *
     * 实现方式：保留条目但打上 [MemoryEntry.turn] = [ARCHIVED_TURN]。
     * 不删除是因为用户可能还想在"记忆管理"里看到；不参与注入由
     * [PersonaMemoryManager] 检查 turn 决定。这样既满足"不参与后续注入"，
     * 又不丢数据。
     */
    @Synchronized
    fun archiveSession(personaId: String, sessionId: String): Int {
        val key = MemoryKey.forSession(personaId, MemoryScope.SINGLE_SESSION, sessionId) ?: return 0
        var n = 0
        entries.entries.forEach { (id, e) ->
            if (e.key == key && e.turn != ARCHIVED_TURN) {
                entries[id] = e.copy(turn = ARCHIVED_TURN)
                n++
            }
        }
        return n
    }

    companion object {
        /** 归档标记。负数与真实轮次（从 0 开始）不冲突。 */
        const val ARCHIVED_TURN = -1
    }
}

/** 一次注入的产物：直接拼进 system/prefix 的文本 + 诊断信息。 */
data class InjectionPlan(
    val facts: List<MemoryEntry>,
    val summary: MemoryEntry?,
    /** 因预算或条数上限被丢弃的条目数。 */
    val dropped: Int,
    val estimatedTokens: Int,
    /** 本轮生效的压缩系数（1.0 = 未压缩）。 */
    val compression: Double,
) {
    val isEmpty: Boolean get() = facts.isEmpty() && summary == null

    /** 渲染为注入正文。空计划返回 null，调用方据此跳过拼接。 */
    fun render(): String? {
        if (isEmpty) return null
        return buildString {
            summary?.let {
                append("[记忆摘要]\n")
                append(it.text.trim())
                append("\n")
            }
            if (facts.isNotEmpty()) {
                append("[已知事实]\n")
                facts.forEach { f -> append("- ").append(f.text.trim()).append("\n") }
            }
        }.trimEnd()
    }
}

/**
 * 记忆的注入决策与压缩强度（规格 §4.5「注入策略」/ §4.6）。
 *
 * 三层约束同时生效，取最紧的那个：
 *  1. 条数上限 `memoryConfig.maxInjectedFacts`
 *  2. token 预算（免费通道更小）
 *  3. 轮次压缩系数（SINGLE_SESSION 每轮累积，必须越压越紧，否则上下文单调增长直到爆窗）
 */
object PersonaMemoryManager {

    /** 粗略 token 估算：CJK 每字 1 token，其余 4 字符 1 token。 */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (isCjk(ch)) cjk++ else other++
        }
        return cjk + (other + 3) / 4
    }

    private fun isCjk(ch: Char): Boolean {
        val c = ch.code
        return c in 0x4E00..0x9FFF ||   // 基本汉字
            c in 0x3400..0x4DBF ||      // 扩展 A
            c in 0x3000..0x303F ||      // 中文标点
            c in 0xFF00..0xFFEF         // 全角
    }

    /**
     * 压缩系数：随轮次递增而收紧，下限 0.4。
     *
     * 为什么要有下限：如果一路压到 0，等于 SINGLE_SESSION 在长会话里退化成 NONE，
     * 用户会觉得"记忆坏了"。保留 4 成仍能记住最近的关键事实。
     */
    fun compression(turn: Int, isFreeTier: Boolean): Double {
        val base = (1.0 - 0.12 * (turn.coerceAtLeast(0))).coerceIn(0.4, 1.0)
        // 免费成员预算更小，压得更狠（规格 §4.5「免费成员上下文预算更小」）
        return if (isFreeTier) (base * 0.75).coerceAtLeast(0.3) else base
    }

    /**
     * 计算本轮注入计划。
     *
     * @param entries 该 key 下的全部条目（[MemoryStore.list] 的输出）
     * @param turn 当前轮次（0 起）
     * @param isFreeTier 免费通道成员预算减半
     */
    fun plan(
        entries: List<MemoryEntry>,
        config: MemoryConfig,
        turn: Int,
        isFreeTier: Boolean,
    ): InjectionPlan {
        // 归档条目永不注入（规格 §4.5：结束归档，不参与后续注入）
        val live = entries.filter { it.turn != MemoryStore.ARCHIVED_TURN }
        if (live.isEmpty()) return InjectionPlan(emptyList(), null, 0, 0, 1.0)

        val factor = compression(turn, isFreeTier)

        // 总预算 = summaryMaxTokens × 压缩系数。摘要与事实共享这一个额度，
        // 否则摘要上限 + 事实上限会叠加成两倍预算，轮次一多就顶穿窗口。
        val totalBudget = (config.summaryMaxTokens * factor).toInt().coerceAtLeast(120)
        val summaryBudget = (totalBudget / 2).coerceAtLeast(80)
        val factBudget = (config.maxInjectedFacts * factor).toInt()
            .coerceAtLeast(if (config.extractKeyFacts) 1 else 0)

        val summary = live.firstOrNull { it.kind == MemoryKind.SUMMARY }
            ?.let { s ->
                if (estimateTokens(s.text) <= summaryBudget) s
                else s.copy(text = truncateToTokens(s.text, summaryBudget))
            }

        var used = summary?.let { estimateTokens(it.text) } ?: 0
        val picked = mutableListOf<MemoryEntry>()
        var dropped = 0

        val facts = live.filter { it.kind == MemoryKind.FACT }
        for (f in facts) {
            if (picked.size >= factBudget) {
                dropped++
                continue
            }
            val t = estimateTokens(f.text)
            // 预算不够时**跳过而不是中止**：后面可能有更短的事实能塞进去
            if (used + t > totalBudget) {
                dropped++
                continue
            }
            picked += f
            used += t
        }

        return InjectionPlan(
            facts = picked,
            summary = summary,
            dropped = dropped,
            estimatedTokens = used,
            compression = factor,
        )
    }

    fun truncateToTokens(text: String, maxTokens: Int): String {
        if (estimateTokens(text) <= maxTokens) return text
        // 按比例估字符数，再在句读处回退，避免切断句子
        val approxChars = (maxTokens * 2).coerceAtMost(text.length)
        val cut = text.take(approxChars)
        val stop = cut.lastIndexOfAny(charArrayOf('。', '；', '\n', '！', '？'))
        return (if (stop > approxChars / 3) cut.substring(0, stop + 1) else cut).trimEnd() + "…"
    }
}

/**
 * 关键事实的**候选准备与去重**（规格 §4.5「关键事实抽取」）。
 *
 * 边界说明（必须诚实）：真正判断"这句是不是关键事实"需要一次 LLM 调用，
 * 本类不做那件事。它负责 LLM 前后两端的确定性工作：
 *  - 前端：切句、丢弃明显无信息量的句子、按长度裁剪；
 *  - 后端：归一化去重（同一事实反复出现时只留一条）、数量上限。
 *
 * 这样 LLM 只处理"是不是事实"这一个判断题，成本可控，且重复条目不会
 * 因为模型措辞微变而无限堆积。
 */
object KeyFactExtractor {

    /** 单条事实的字符上限，超出按句截断。 */
    const val MAX_FACT_CHARS = 200

    private val NOISE = listOf(
        "好的", "嗯", "谢谢", "收到", "明白", "ok", "okay", "thanks",
    )

    /**
     * 从一段文本里切出候选句。
     *
     * 只保留含"事实信号"的句子：出现偏好/决策/背景类标记词，或含具体专名、路径、数字。
     * 纯客套与纯疑问句直接丢弃——它们进入记忆只会稀释注入预算。
     */
    fun candidates(text: String): List<String> =
        text.split(Regex("[。！？!?\\n]+"))
            .map { it.trim().trimEnd('，', ',', '；', ';') }
            .filter { it.length in 6..MAX_FACT_CHARS * 2 }
            .filterNot { s -> NOISE.any { n -> s.equals(n, ignoreCase = true) } }
            .filterNot { it.contains('?') || it.contains('？') }
            .filter { hasSignal(it) }

    private fun hasSignal(s: String): Boolean {
        val markers = listOf(
            "我", "我们", "我的", "偏好", "习惯", "喜欢", "不喜欢", "不要", "必须",
            "项目", "决定", "约定", "采用", "使用", "部署", "路径", "环境",
            "以后", "记住", "默认", "规范", "禁止", "要求",
        )
        if (markers.any { s.contains(it) }) return true
        // 含路径、代码标识、数字的句子通常携带可复用事实
        if (s.contains('/') || s.contains('\\') || s.contains('_')) return true
        if (s.any { it.isDigit() }) return true
        return false
    }

    /** 归一化：去空白与标点、转小写，用于判重。 */
    fun normalize(s: String): String =
        s.lowercase().replace(Regex("[\\s\\p{Punct}，。；：！？、“”‘’（）《》]"), "")

    /**
     * 判重：归一化后相同，或字符二元组 Jaccard 相似度 ≥ 0.8。
     *
     * 用 bigram 而不是编辑距离：中文没有词边界，编辑距离对"把 A 改成 B"
     * 这种同义改写几乎无感，而 bigram 重叠能抓住措辞微调。
     */
    fun isDuplicate(a: String, b: String): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        if (na == nb) return true
        if (na.isEmpty() || nb.isEmpty()) return false
        if (na.length < 8 || nb.length < 8) return false
        val ba = bigrams(na)
        val bb = bigrams(nb)
        if (ba.isEmpty() || bb.isEmpty()) return false
        val inter = ba.intersect(bb).size
        val union = ba.union(bb).size
        return union > 0 && inter.toDouble() / union >= 0.8
    }

    private fun bigrams(s: String): Set<String> {
        if (s.length < 2) return emptySet()
        val out = HashSet<String>(s.length)
        for (i in 0 until s.length - 1) out.add(s.substring(i, i + 2))
        return out
    }

    /**
     * 过滤候选：去掉与已有事实重复的，并按上限截断。
     * @return 值得交给 LLM 判定的候选（已去重、已限长）
     */
    fun prepare(text: String, existing: List<String>, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        val accepted = mutableListOf<String>()
        for (c in candidates(text)) {
            val trimmed = if (c.length > MAX_FACT_CHARS) {
                c.take(MAX_FACT_CHARS - 1).trimEnd() + "…"
            } else c
            if (existing.any { isDuplicate(it, trimmed) }) continue
            if (accepted.any { isDuplicate(it, trimmed) }) continue
            accepted += trimmed
            if (accepted.size >= limit) break
        }
        return accepted
    }
}
