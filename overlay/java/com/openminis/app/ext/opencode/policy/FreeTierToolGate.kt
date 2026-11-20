package com.openminis.app.ext.opencode.policy

/**
 * 免费池的四工具硬门禁（规格 §9.6）。
 *
 * 上游对 MCP 客户端有硬性要求：**必须同时声明小写**的 `bash`、`glob`、`grep`、`read`
 * 四个工具名，缺任何一个立刻返回
 * `403 FreeTierError: "OpenCode's free tier can only be used from within OpenCode"`。
 * 这是一条反滥用的门禁，不是文档建议——少了就整条通道不可用，而且报错信息完全误导
 * （看起来像"没装 OpenCode"，实际是工具名不对）。
 *
 * 因此这里做两件事：
 *  1. 组装 MCP 工具表时，永远把这四个名字**原样**放进去；
 *  2. 人设 `allowedTools` 与它们求交集，若交集不足四个，**在绑定阶段就拒绝**
 *     该成员使用免费通道——而不是等到第一次请求 403 才报错（规格 §9.6 最后一条）。
 */
object FreeTierToolGate {

    /** 顺序固定，便于日志与快照对比。全部小写。 */
    val REQUIRED: List<String> = listOf("bash", "glob", "grep", "read")

    sealed interface Verdict {
        /** 可以走免费通道；[exposed] 是最终要声明给 MCP 的工具名。 */
        data class Allowed(val exposed: List<String>) : Verdict

        /** 白名单导致必需工具缺失，绑定阶段拒绝。 */
        data class Rejected(val missing: List<String>, val reason: String) : Verdict
    }

    /**
     * [allowedTools] 为 null 表示"不限"（人设未声明白名单），此时四件套齐全。
     * 大小写敏感：写 `Bash` 不等于 `bash`，上游只认小写。
     */
    fun check(allowedTools: Collection<String>?): Verdict {
        if (allowedTools == null) return Verdict.Allowed(REQUIRED)

        val normalized = allowedTools.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val exposed = REQUIRED.filter { it in normalized }
        val missing = REQUIRED.filterNot { it in normalized }

        if (missing.isEmpty()) return Verdict.Allowed(exposed)

        val caseHint = missing.mapNotNull { m ->
            normalized.firstOrNull { it.equals(m, ignoreCase = true) && it != m }
                ?.let { "「$it」应写成小写「$m」" }
        }
        val reason = buildString {
            append("人设的工具白名单缺少 ")
            append(missing.joinToString("、"))
            append("，免费通道必须同时暴露 bash/glob/grep/read 四个小写工具名，否则上游返回 403。")
            if (caseHint.isNotEmpty()) append("（").append(caseHint.joinToString("；")).append("）")
        }
        return Verdict.Rejected(missing, reason)
    }

    /** 派发给 MCP 的工具名列表；不合规时为空（调用方必须先 check）。 */
    fun mcpToolNames(allowedTools: Collection<String>?): List<String> =
        when (val v = check(allowedTools)) {
            is Verdict.Allowed -> v.exposed
            is Verdict.Rejected -> emptyList()
        }
}
