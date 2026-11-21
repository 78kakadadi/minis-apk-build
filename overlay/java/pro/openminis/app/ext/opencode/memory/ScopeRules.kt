package pro.openminis.app.ext.opencode.memory

/**
 * 记忆作用域三档（规格 §4.5）。rank 用于「只能收紧、不能放宽」的比较。
 */
enum class MemoryScope(val rank: Int, val label: String) {
    NONE(0, "不保留记忆"),
    SINGLE_SESSION(1, "仅当前会话"),
    CROSS_SESSION(2, "跨对话持久"),
}

/** 人设上的记忆参数（规格 §4.2 memoryConfig）。 */
data class MemoryConfig(
    val summaryMaxTokens: Int = 2000,
    val extractKeyFacts: Boolean = true,
    val maxInjectedFacts: Int = 8,
) {
    companion object {
        val DEFAULT = MemoryConfig()
    }
}

sealed interface ScopeResolution {
    data class Ok(val scope: MemoryScope, val overridden: Boolean) : ScopeResolution
    data class Rejected(val reason: String) : ScopeResolution
}

/**
 * 人设 → 成员的作用域/预算规则（规格 §4.4 / §4.5 / §9.6）。
 *
 * 核心不变量：**成员只能收紧作用域，不能放宽**。
 * 「放宽」的后果是隐私事故——用户把一个 SINGLE_SESSION 人设拉进私密群聊，
 * 若成员级配置能把它改成 CROSS_SESSION，那么在别的会话里说过的话会被注入进来。
 * 所以这里把比较做成结构性约束，而不是靠 UI 不提供选项。
 */
object ScopeRules {

    /**
     * 计算成员的实际作用域。
     * [memberOverride] 为 null 表示继承人设默认。
     */
    fun resolve(personaScope: MemoryScope, memberOverride: MemoryScope?): ScopeResolution {
        if (memberOverride == null) return ScopeResolution.Ok(personaScope, overridden = false)
        if (memberOverride.rank > personaScope.rank) {
            return ScopeResolution.Rejected(
                "人设声明为「${personaScope.label}」，成员不能放宽为「${memberOverride.label}」。" +
                    "只能收紧（例如跨对话 → 单会话）。",
            )
        }
        return ScopeResolution.Ok(memberOverride, overridden = memberOverride != personaScope)
    }

    /**
     * 免费成员的记忆预算（规格 §4.5「免费成员上下文预算更小」/ §9.6）。
     *
     * 免费模型每轮有 7k–9k 的输入底噪（系统提示 + 工具 schema），可用的上下文
     * 远小于付费模型，所以摘要与注入条数都要减半左右，避免第一轮就顶到窗口。
     */
    fun effectiveConfig(config: MemoryConfig, isFreeTier: Boolean): MemoryConfig {
        if (!isFreeTier) return config
        return config.copy(
            summaryMaxTokens = minOf(config.summaryMaxTokens, FREE_SUMMARY_MAX_TOKENS),
            maxInjectedFacts = minOf(config.maxInjectedFacts, FREE_MAX_INJECTED_FACTS),
        )
    }

    const val FREE_SUMMARY_MAX_TOKENS = 800
    const val FREE_MAX_INJECTED_FACTS = 4

    /** 是否写入共享记忆库（按 personaId 索引）。 */
    fun persistsToLibrary(scope: MemoryScope): Boolean = scope == MemoryScope.CROSS_SESSION

    /** 新会话首轮是否注入摘要。 */
    fun injectsOnNewSession(scope: MemoryScope): Boolean =
        scope == MemoryScope.CROSS_SESSION || scope == MemoryScope.SINGLE_SESSION

    /** 每轮是否注入本会话累积记忆。 */
    fun injectsPerTurn(scope: MemoryScope): Boolean = scope == MemoryScope.SINGLE_SESSION

    /** 会话结束是否落盘归档（不参与后续注入）。 */
    fun archivesOnEnd(scope: MemoryScope): Boolean = scope == MemoryScope.SINGLE_SESSION

    /**
     * 同一群聊里两个成员是否共享记忆：
     * 同 persona 且都为 CROSS_SESSION 才共享（规格 §4.5「隔离」）。
     */
    fun sharesMemory(
        personaA: String, scopeA: MemoryScope,
        personaB: String, scopeB: MemoryScope,
    ): Boolean = personaA == personaB &&
        scopeA == MemoryScope.CROSS_SESSION &&
        scopeB == MemoryScope.CROSS_SESSION
}
