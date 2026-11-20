package com.openminis.app.ext.opencode.memory

/**
 * 群聊场景下的人设注入（规格 §4.6 的**降级路径**，明确记录）。
 *
 * ## 为什么需要降级
 *
 * 规格同时要求两件事：
 *  - §9.10「每个免费成员映射独立 OpenCode 会话，**共享同一服务器进程**」；
 *  - §4.6「通过该会话独立目录的 opencode.jsonc 写入 agent 指令」。
 *
 * 这两条在单进程下无法同时成立：`XDG_CONFIG_HOME` 是**进程级**环境变量，
 * 一台 server 只有一个 config，`instructions` 列表也是全局的。若为每个成员
 * 单开进程，就违背了"共享同一服务器"（会成倍占用内存与免费配额）。
 *
 * 因此这里走规格 §4.6 自己给出的降级分支：
 * > 若 OpenCode 版本不支持按会话配置，则在每轮新消息前附加轻量人设重申
 * > （受 token 预算约束），并记录降级行为。
 *
 * 单聊（一个会话只有一个成员）时仍用 config 的 `instructions`（一次性写入，
 * 不重复消耗 token）；群聊时每轮附加前缀，保证成员之间不串人设。
 *
 * 预算：前缀必须短。免费模型每轮已有 7k–9k 输入底噪，前缀挤占的是可用上下文，
 * 所以默认只给 120 token（约 480 字符），超出就截断并在末尾加省略号。
 */
object PersonaInjector {

    /** 粗略估算：中英混排约 4 字符 / token。只用于预算裁剪，不用于计费。 */
    const val CHARS_PER_TOKEN = 4

    const val DEFAULT_PREFIX_TOKENS = 120

    /**
     * 生成前缀正文。`systemPrompt` 为空或全空白时返回 null——此时不该插入
     * 任何"角色设定"字样，否则模型会困惑于一个空设定。
     */
    fun prefix(systemPrompt: String, maxTokens: Int = DEFAULT_PREFIX_TOKENS): String? {
        val trimmed = systemPrompt.trim()
        if (trimmed.isEmpty()) return null
        val budget = (maxTokens.coerceAtLeast(1)) * CHARS_PER_TOKEN
        if (trimmed.length <= budget) return trimmed
        // 尽量在句读处断开，避免把一句话切成半个词
        val cut = trimmed.take(budget)
        val lastStop = cut.lastIndexOfAny(charArrayOf('。', '；', '\n', '.', ';'))
        val body = if (lastStop > budget / 2) cut.substring(0, lastStop + 1) else cut
        return body.trimEnd() + "……"
    }

    /** 拼成一条完整的用户消息。 */
    fun compose(prefix: String?, userText: String): String {
        if (prefix.isNullOrBlank()) return userText
        return buildString {
            append("[角色设定]\n")
            append(prefix)
            append("\n\n[用户消息]\n")
            append(userText)
        }
    }

    /**
     * 判断本轮是否需要附加前缀。
     *
     * 单聊首轮之后不需要（config 已带 instructions，重复加是浪费）。
     * 群聊每轮都需要（同一 server 上多个成员的人设会互相覆盖）。
     */
    fun needed(isGroupChat: Boolean, isFirstTurn: Boolean): Boolean = isGroupChat || isFirstTurn
}
