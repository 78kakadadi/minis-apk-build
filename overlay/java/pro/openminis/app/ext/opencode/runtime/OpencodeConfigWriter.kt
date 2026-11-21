package pro.openminis.app.ext.opencode.runtime

/**
 * 生成每个会话/成员**独立目录**下的 `opencode.jsonc`（规格 §4.6 / §9.6）。
 *
 * 关键取舍：人设指令通过 `instructions` 指向一个 Markdown 文件，而不是猜测
 * `agent.*.prompt` 之类版本相关的内联字段。`instructions` 是 OpenCode 稳定的
 * 「附加指令文件」机制（AGENTS.md 同源），字段少、语义清楚，升级不易失效。
 *
 * 另一个必须遵守的点：**每轮只发新消息**。人设与记忆写进配置文件一次，
 * 不随每轮 prompt 重发全文——否则前缀缓存失效，免费池的 7k–9k 输入底噪会翻倍
 * （规格 §9.6「不重新序列化完整 transcript」/ §4.6「每轮只发新消息」）。
 */
object OpencodeConfigWriter {

    private const val SCHEMA = "https://opencode.ai/config.json"

    /**
     * 最小配置。
     *
     * - `autoupdate=false`：native ELF 在只读的 nativeLibraryDir，自更新必然失败；
     * - `share=disabled`：不把会话内容上传到分享服务（免费通道的隐私底线）。
     */
    fun minimal(extraInstructions: List<String> = emptyList()): String = buildString {
        appendLine("// 由 Minis 免费通道自动生成。此文件位于应用私有目录，不读取也不修改 ~/.config/opencode。")
        appendLine("{")
        appendLine("  \"\$schema\": \"$SCHEMA\",")
        appendLine("  \"autoupdate\": false,")
        append("  \"share\": \"disabled\"")
        if (extraInstructions.isNotEmpty()) {
            appendLine(",")
            appendLine("  \"instructions\": [")
            extraInstructions.forEachIndexed { i, p ->
                append("    ").append(jsonQuote(p))
                appendLine(if (i == extraInstructions.lastIndex) "" else ",")
            }
            appendLine("  ]")
        } else {
            appendLine()
        }
        appendLine("}")
    }

    /**
     * 人设 + 记忆摘要的指令正文。
     *
     * [memorySummary] 由 [pro.openminis.app.ext.opencode.memory.ScopeRules] 决定是否为空：
     * NONE 作用域永远不传，SINGLE_SESSION 只在会话内传。
     */
    fun personaMarkdown(personaPrompt: String, memorySummary: String?): String = buildString {
        appendLine("# 角色设定")
        appendLine()
        appendLine(personaPrompt.trim())
        if (!memorySummary.isNullOrBlank()) {
            appendLine()
            appendLine("## 已知上下文（历史记忆摘要）")
            appendLine()
            appendLine(memorySummary.trim())
            appendLine()
            appendLine("以上内容来自此前的对话摘要，仅在与当前任务相关时使用，不要向用户复述其来源。")
        }
    }

    /** 判断一份已有配置文件是否由本应用生成（用于安全地覆盖，不误删用户手写配置）。 */
    fun isOurs(content: String): Boolean =
        content.contains("由 Minis 免费通道自动生成")

    private fun jsonQuote(s: String): String = buildString {
        append('"')
        s.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> append(c)
            }
        }
        append('"')
    }
}
