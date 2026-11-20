package com.openminis.app.ext.dispatch

/**
 * 复用 Minis 已有的 agent_report 六态（规格 §2 第 14 条）。
 *
 * **不重新定义语义**：这里的枚举只是让本模块的校验逻辑能编译与测试，
 * 真实状态由宿主存储持有。wire 值与上游一致，禁止改动。
 */
enum class AgentReportStatus(val wire: String, val needsBanner: Boolean) {
    PROGRESS("progress", false),
    WAITING("waiting", false),
    READY("ready", false),
    /** 需要用户回答才能继续——主会话必须显示横幅（规格 §5.1）。 */
    BLOCKED("blocked", true),
    FAILED("failed", true),
    /** 被主动丢弃，例如用户取消或波次重复。 */
    DROPPED("dropped", true),
    ;

    companion object {
        fun fromWire(raw: String): AgentReportStatus? = entries.firstOrNull { it.wire == raw }
    }
}

/**
 * task_mandate 授权令牌（规格 §5.1「explore / worker / edit:」）。
 *
 * 这是子代理的能力上限。派发前必须校验，越界由宿主**直接拒绝**，
 * 不能靠提示词自我约束——模型完全可能在简报里要求超出范围的操作。
 */
sealed interface TaskMandate {
    /** 只读探索：读文件、搜索、列举。 */
    data object Explore : TaskMandate

    /** 工作模式：在探索之上允许执行 shell。 */
    data object Worker : TaskMandate

    /** 编辑模式：允许写文件，但仅限给定路径前缀。 */
    data class Edit(val pathPrefixes: List<String>) : TaskMandate

    val wire: String
        get() = when (this) {
            Explore -> "explore"
            Worker -> "worker"
            is Edit -> "edit:" + pathPrefixes.joinToString(",")
        }
}

/** 工具的能力分级。任何未列入的工具按 WRITE 处理——默认拒绝比默认放行安全。 */
enum class ToolCapability { READ, EXEC, WRITE }

/** 校验结论。 */
sealed interface MandateDecision {
    data object Allowed : MandateDecision
    data class Denied(val reason: String) : MandateDecision
}

object MandateGuard {

    /** 工具名 → 能力。名字用 Minis 侧的实际工具名（见 McpToolAlias）。 */
    private val READ_TOOLS = setOf(
        "file_read", "glob", "grep", "list_dir", "file_list",
        "read", "search", "web_fetch",
    )
    private val EXEC_TOOLS = setOf("shell_execute")
    private val WRITE_TOOLS = setOf("file_write", "file_edit", "file_delete", "apply_patch")

    fun capabilityOf(toolName: String): ToolCapability = when (toolName) {
        in READ_TOOLS -> ToolCapability.READ
        in EXEC_TOOLS -> ToolCapability.EXEC
        in WRITE_TOOLS -> ToolCapability.WRITE
        // 未知工具按写权限处理：宁可误拒，不可误放
        else -> ToolCapability.WRITE
    }

    /**
     * 判断 [toolName] 在 [mandate] 下是否允许，以及（写操作时）目标路径是否在范围内。
     *
     * @param targetPath 写类工具的目标路径，相对沙箱根；只读/执行工具可传 null
     */
    fun check(mandate: TaskMandate, toolName: String, targetPath: String? = null): MandateDecision {
        val cap = capabilityOf(toolName)

        when (mandate) {
            TaskMandate.Explore -> {
                if (cap == ToolCapability.READ) return MandateDecision.Allowed
                return MandateDecision.Denied(
                    "explore 令牌只允许只读操作，$toolName 属于" +
                        if (cap == ToolCapability.EXEC) "执行类" else "写入类",
                )
            }

            TaskMandate.Worker -> {
                if (cap == ToolCapability.WRITE) {
                    return MandateDecision.Denied("worker 令牌不允许写文件，$toolName 需要 edit: 令牌")
                }
                return MandateDecision.Allowed
            }

            is TaskMandate.Edit -> {
                if (cap != ToolCapability.WRITE) return MandateDecision.Allowed
                val path = targetPath?.trim().orEmpty()
                if (path.isEmpty()) {
                    return MandateDecision.Denied("edit 令牌下写操作必须给出目标路径，$toolName 未提供")
                }
                val normalized = normalize(path)
                if (normalized == null) {
                    return MandateDecision.Denied("目标路径含越界段（..），已拒绝：$path")
                }
                val allowed = mandate.pathPrefixes.any { prefix ->
                    val p = normalize(prefix) ?: return@any false
                    // 前缀匹配必须落在路径边界上：`src` 不应放行 `srcx/a.kt`
                    normalized == p || normalized.startsWith("$p/")
                }
                return if (allowed) {
                    MandateDecision.Allowed
                } else {
                    MandateDecision.Denied(
                        "edit 令牌范围是 [${mandate.pathPrefixes.joinToString(", ")}]，" +
                            "写入 $normalized 越界",
                    )
                }
            }
        }
    }

    /** 归一化路径；含 `..` 或绝对路径返回 null。 */
    private fun normalize(raw: String): String? {
        val p = raw.replace('\\', '/').trim()
        if (p.startsWith("/")) return null
        if (p.length >= 2 && p[1] == ':') return null
        val parts = p.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }
}

/** 派发种类（规格 §5.1 三个工具）。 */
enum class DispatchKind(val toolName: String) {
    SPAWN("spawn_agent"),
    DISPATCH("dispatch_agents"),
    WOLFPACK("wolfpack_run"),
}

/** 波次去重结果。 */
sealed interface WaveClaim {
    /** 新批次，已授予句柄。 */
    data class Granted(val handle: String, val fingerprint: String) : WaveClaim

    /**
     * 命中了 10 分钟内未完成的同波次——**拒绝重复派发**，返回已有句柄供 check_agent 收取。
     * 规格 §5.1 明确要求返回已有句柄，而不是简单报错。
     */
    data class Duplicate(val existingHandle: String, val fingerprint: String, val ageSeconds: Long) : WaveClaim
}

/**
 * 波次指纹去重（规格 §5.1）。
 *
 * 指纹 = 种类 + 简报内容（**顺序无关**）。顺序无关很关键：同一批子任务
 * 只是排列顺序不同，属于同一波次，不该重复派发。
 *
 * 命中条件：同指纹且该批次在 [ttlMs] 内**仍未完成**。已完成的批次不阻止再次派发
 * ——用户可能就是想重跑一次。
 */
class WaveDeduplicator(
    private val ttlMs: Long = 10 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private data class Wave(
        val fingerprint: String,
        val handle: String,
        val createdAt: Long,
        var completed: Boolean = false,
    )

    private val waves = HashMap<String, Wave>()
    private var counter = 0L

    /**
     * 指纹计算。对简报做排序 + 去重后再哈希，保证顺序无关。
     * 用长度前缀分隔，避免 ["ab","c"] 与 ["a","bc"] 撞成同一个串。
     */
    fun fingerprint(kind: DispatchKind, briefs: List<String>): String {
        val normalized = briefs.map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }
            .sorted()
        val sb = StringBuilder(kind.toolName)
        for (b in normalized) {
            sb.append('|').append(b.length).append(':').append(b)
        }
        return sb.toString()
    }

    /** 申请派发。命中未完成同波次则返回 [WaveClaim.Duplicate]。 */
    fun claim(kind: DispatchKind, briefs: List<String>, now: Long = clock()): WaveClaim {
        val fp = fingerprint(kind, briefs)
        val existing = waves[fp]
        if (existing != null && !existing.completed) {
            val age = now - existing.createdAt
            if (age in 0..ttlMs) {
                return WaveClaim.Duplicate(existing.handle, fp, age / 1000)
            }
            // 超时未完成：允许重新派发（可能是卡死的批次），并替换记录
        }
        counter += 1
        val handle = "wave-${kind.toolName}-$counter"
        waves[fp] = Wave(fp, handle, now)
        return WaveClaim.Granted(handle, fp)
    }

    /** 标记批次完成，解除去重锁。 */
    fun complete(handle: String) {
        waves.values.firstOrNull { it.handle == handle }?.completed = true
    }

    /** 未完成批次，供「未完成泳道」渲染（规格 §5.1「标注不要重新派发」）。 */
    fun pending(now: Long = clock()): List<Triple<String, Long, Boolean>> =
        waves.values
            .filter { !it.completed }
            .map { Triple(it.handle, (now - it.createdAt) / 1000, now - it.createdAt > ttlMs) }

    fun clear() = waves.clear()
}

/**
 * web-anon 补充通道（规格 §9.11）。
 *
 * 它是**最低优先级**：仅在付费 Key 与 opencode-local 都不可用时才启用。
 * 它没有系统消息能力，也常不提供 token 用量，因此：
 * - 人设以结构化前缀拼进首条用户消息（规格 §4.6）；
 * - 用量记请求数并标注「该通道未提供 token 用量」；
 * - **工具能力不可信**：回复里声称的「我已读取文件」不能当真。
 */
object WebAnonChannel {

    /** 路由优先级：付费 Key(100) > opencode-local(50) > web-anon(10)。 */
    const val PRIORITY = 10

    const val USAGE_NOTE = "该通道未提供 token 用量"

    /** 网页端通常几分钟内就会限流，冷却给短一点，避免长时间不可用。 */
    const val DEFAULT_COOLDOWN_SECONDS = 120L

    /**
     * 判定一条回复是否在「声称执行了工具」。
     *
     * 网页端没有工具通道，若模型输出「我已经读取了 xxx 文件」「已执行命令」，
     * 那是幻觉。这里给出可疑标记，让编排器可以在气泡上提示，
     * 而不是把幻觉当成事实继续推理。
     */
    private val TOOL_CLAIM_PATTERNS = listOf(
        Regex("我(已|已经)?(读取|打开|查看)了.{0,20}(文件|目录)"),
        Regex("我(已|已经)?(执行|运行)了.{0,20}(命令|脚本)"),
        Regex("(执行结果|命令输出|终端输出)\\s*[:：]"),
        Regex("我已(修改|写入|保存)"),
    )

    fun claimsToolUse(reply: String): Boolean =
        TOOL_CLAIM_PATTERNS.any { it.containsMatchIn(reply) }

    /**
     * 把系统提示塞进首条用户消息（网页端不支持 system role）。
     *
     * 用明确分隔符包住，避免与用户正文混淆；同时告知模型不要声称执行工具。
     */
    fun wrapWithPersona(personaPrompt: String, userMessage: String): String {
        if (personaPrompt.isBlank()) return userMessage
        return buildString {
            append("<<<角色设定>>>\n")
            append(personaPrompt.trim())
            append("\n<<<角色设定结束>>>\n")
            append("（注意：当前通道没有工具能力，不要声称已读取文件或执行命令。）\n\n")
            append(userMessage)
        }
    }
}
