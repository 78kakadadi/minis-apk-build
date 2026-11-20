package com.openminis.app.ext.persona

import com.openminis.app.ext.opencode.memory.MemoryConfig
import com.openminis.app.ext.opencode.memory.MemoryScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 人设来源（规格 §4.2 `source`）。
 *
 * `BUILTIN` 不可删除：内置示例是用户理解"人设是什么"的入口，
 * 删掉后新用户面对空白页无从下手。允许复制后修改，改动落在 [USER]。
 */
enum class PersonaSource { BUILTIN, USER, IMPORTED }

/**
 * 一个 Agent 人设（规格 §4.2）。
 *
 * 设计要点：人设与模型**完全解耦**——这里没有任何 provider / model 的必填字段，
 * [defaultModel] 只是"建议"，成员绑定时可以覆盖成任意模型。
 * 规格 §4.4 明确要求"同一人设可被绑到任意模型，免费与付费成员的人设机制完全一致"，
 * 若把人设和模型耦合，免费通道成员就会被迫用另一套人设语义。
 *
 * [description] 明确**不进入模型上下文**，只用于 UI 卡片展示。
 * 这不是可选项：简介里常常写着"用于内部代码库"，属于元信息，注入后会污染行为。
 */
data class Persona(
    val id: String,
    val name: String,
    val avatar: String? = null,
    val description: String = "",
    /** 人设核心指令：角色、语气、专长、约束。唯一必填的行为字段。 */
    val systemPrompt: String,
    /** 结构化标签，供 UI 展示与筛选，不注入模型。 */
    val traits: List<String> = emptyList(),
    /** 默认绑定建议，不强制；成员绑定时可覆盖。 */
    val defaultModel: String? = null,
    /** 默认记忆作用域；成员绑定时只能收紧（见 ScopeRules.resolve）。 */
    val memoryScope: MemoryScope = MemoryScope.CROSS_SESSION,
    val memoryConfig: MemoryConfig = MemoryConfig.DEFAULT,
    /** 工具白名单；null 表示全部允许（与空列表语义不同）。 */
    val allowedTools: List<String>? = null,
    val source: PersonaSource = PersonaSource.USER,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {

    val isBuiltin: Boolean get() = source == PersonaSource.BUILTIN

    /**
     * 工具是否在白名单内。
     *
     * `null`（未设置）= 全部允许；空列表 = 全部禁止。两者不能合并：
     * 用户清空白名单是想"这个成员不调任何工具"（纯对话角色），
     * 而不是"恢复默认"。
     */
    fun allowsTool(toolName: String): Boolean =
        allowedTools == null || toolName in allowedTools

    /** 复制一份作为用户人设，用于"复制后修改"内置项。 */
    fun toEditableCopy(newId: String, now: Long): Persona = copy(
        id = newId,
        name = "$name 副本",
        source = PersonaSource.USER,
        createdAt = now,
        updatedAt = now,
    )

    /** 轻量校验，供仓库在写入前拦截明显无效的数据。 */
    fun validate(): List<String> = buildList {
        if (id.isBlank()) add("id 不能为空")
        if (name.isBlank()) add("名称不能为空")
        if (systemPrompt.isBlank()) add("systemPrompt 不能为空，否则人设等于没生效")
        if (systemPrompt.length > MAX_PROMPT_CHARS) {
            add("systemPrompt 超过 $MAX_PROMPT_CHARS 字符，会挤占免费通道本就有限的上下文预算")
        }
        if (name.length > 60) add("名称超过 60 字符")
    }

    companion object {
        /**
         * 单个 systemPrompt 的字符上限。
         *
         * 取 8000 而不是更多：免费通道每轮输入底噪已有 7k–9k tokens
         * （系统提示 + 工具 schema），人设再长会直接把可用轮次压到个位数。
         */
        const val MAX_PROMPT_CHARS = 8000
    }
}

/** 内置示例人设（规格 §4.2「内置若干示例人设」）。 */
object BuiltinPersonas {

    const val GENERAL = "builtin.general"
    const val CODE_REVIEWER = "builtin.code-reviewer"
    const val PRODUCT_MANAGER = "builtin.product-manager"

    fun all(now: Long = 0L): List<Persona> = listOf(
        Persona(
            id = GENERAL,
            name = "通用助手",
            description = "不预设专长的默认人设，适合日常问答与轻量任务。",
            systemPrompt = """
                你是一个可靠的通用助手。回答直接、具体，不确定时说明不确定的原因而不是猜测。
                涉及本机操作时优先使用工具验证事实，不要凭记忆断言文件内容或命令输出。
            """.trimIndent(),
            traits = listOf("通用", "直接"),
            memoryScope = MemoryScope.CROSS_SESSION,
            source = PersonaSource.BUILTIN,
            createdAt = now,
            updatedAt = now,
        ),
        Persona(
            id = CODE_REVIEWER,
            name = "代码审查员",
            description = "以审查者视角读代码，只报可复现的问题，不写客套话。",
            systemPrompt = """
                你是一名严格的代码审查员。审查时遵循：
                1. 只报告能指出具体文件与行的问题，不写"建议优化"这类空话。
                2. 按严重度排序：会导致错误 > 会导致误解 > 风格。
                3. 每条意见给出触发条件（什么输入/什么时序会出问题）。
                4. 不确定是否为 bug 时明确标注"待确认"，不要伪装成结论。
            """.trimIndent(),
            traits = listOf("严谨", "代码优先"),
            memoryScope = MemoryScope.SINGLE_SESSION,
            allowedTools = listOf("read", "grep", "glob", "bash"),
            source = PersonaSource.BUILTIN,
            createdAt = now,
            updatedAt = now,
        ),
        Persona(
            id = PRODUCT_MANAGER,
            name = "产品经理",
            description = "把模糊需求拆成可验收的条目，主动指出范围蔓延。",
            systemPrompt = """
                你是一名产品经理。收到需求时先确认三件事：目标用户、成功判据、不做什么。
                输出结构：一句话结论 → 用户故事 → 验收条目（每条可判定真/假）→ 风险与依赖。
                遇到范围蔓延要当场指出，不要默默扩大交付面。
            """.trimIndent(),
            traits = listOf("结构化", "边界清晰"),
            memoryScope = MemoryScope.CROSS_SESSION,
            source = PersonaSource.BUILTIN,
            createdAt = now,
            updatedAt = now,
        ),
    )
}

sealed interface PersonaDecodeResult {
    data class Ok(val persona: Persona, val warnings: List<String> = emptyList()) : PersonaDecodeResult
    data class Failed(val reason: String) : PersonaDecodeResult
}

/**
 * `.mpersona` JSON 编解码（规格 §4.3「导入 / 导出（.mpersona JSON，便于分享）」）。
 *
 * 刻意不用 kotlinx.serialization 的 `@Serializable`：本模块要求零编译器插件、
 * 可在纯 JVM 上编译（CI 里只跑 kotlinc），注解在缺插件时会静默失效。
 * 手写解析还有一个好处：能对**未知字段**宽容、对**类型错误**给出可读原因。
 *
 * 导出**不包含记忆库**（规格 §4.5「导出人设不包含记忆库」）——记忆是运行数据，
 * 分享人设时带出去等同于泄露用户在别处说过的话。
 */
object PersonaJson {

    const val SCHEMA = "mpersona/1"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun encode(persona: Persona): String = buildJsonObject {
        put("schema", SCHEMA)
        put("id", persona.id)
        put("name", persona.name)
        persona.avatar?.let { put("avatar", it) }
        put("description", persona.description)
        put("systemPrompt", persona.systemPrompt)
        put("traits", buildJsonArray { persona.traits.forEach { add(it) } })
        persona.defaultModel?.let { put("defaultModel", it) }
        put("memoryScope", persona.memoryScope.name)
        put(
            "memoryConfig",
            buildJsonObject {
                put("summaryMaxTokens", persona.memoryConfig.summaryMaxTokens)
                put("extractKeyFacts", persona.memoryConfig.extractKeyFacts)
                put("maxInjectedFacts", persona.memoryConfig.maxInjectedFacts)
            },
        )
        persona.allowedTools?.let { tools ->
            put("allowedTools", buildJsonArray { tools.forEach { add(it) } })
        }
        put("source", persona.source.name)
        put("createdAt", persona.createdAt)
        put("updatedAt", persona.updatedAt)
    }.toString()

    fun decode(text: String, now: Long = 0L): PersonaDecodeResult {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return PersonaDecodeResult.Failed("不是合法的 JSON 对象")

        val warnings = mutableListOf<String>()

        val schema = root.str("schema")
        if (schema != null && schema != SCHEMA) {
            // 不直接拒绝：未来 schema 可能只是加了字段，能读出必填项就先放行并提示。
            warnings += "schema 为「$schema」，当前支持 $SCHEMA，已按兼容模式读取"
        }

        val name = root.str("name")?.trim().orEmpty()
        if (name.isEmpty()) return PersonaDecodeResult.Failed("缺少 name")
        val prompt = root.str("systemPrompt")?.trim().orEmpty()
        if (prompt.isEmpty()) return PersonaDecodeResult.Failed("缺少 systemPrompt")

        // id 缺失时用 name 派生，保证导入后可用；真正的唯一化交给仓库。
        val id = root.str("id")?.trim().takeUnless { it.isNullOrEmpty() }
            ?: "imported." + name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

        val scope = when (val raw = root.str("memoryScope")?.uppercase()) {
            null -> MemoryScope.CROSS_SESSION
            "CROSS_SESSION", "SINGLE_SESSION", "NONE" -> MemoryScope.valueOf(raw)
            else -> {
                warnings += "未知 memoryScope「$raw」，已回退为 CROSS_SESSION"
                MemoryScope.CROSS_SESSION
            }
        }

        val configObj = root["memoryConfig"] as? JsonObject
        val config = MemoryConfig(
            summaryMaxTokens = configObj?.int("summaryMaxTokens")?.coerceIn(200, 8000)
                ?: MemoryConfig.DEFAULT.summaryMaxTokens,
            extractKeyFacts = configObj?.bool("extractKeyFacts") ?: true,
            maxInjectedFacts = configObj?.int("maxInjectedFacts")?.coerceIn(0, 50)
                ?: MemoryConfig.DEFAULT.maxInjectedFacts,
        )

        val tools = (root["allowedTools"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull() }

        val persona = Persona(
            id = id,
            name = name,
            avatar = root.str("avatar"),
            description = root.str("description").orEmpty(),
            systemPrompt = prompt,
            traits = (root["traits"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull() }
                .orEmpty(),
            defaultModel = root.str("defaultModel"),
            memoryScope = scope,
            memoryConfig = config,
            allowedTools = tools,
            // 导入来的人设永远不是内置——否则用户可以伪造一个"内置"人设绕过删除保护。
            source = PersonaSource.IMPORTED,
            createdAt = root.long("createdAt") ?: now,
            updatedAt = now,
        )

        val errors = persona.validate()
        if (errors.isNotEmpty()) return PersonaDecodeResult.Failed(errors.joinToString("；"))
        return PersonaDecodeResult.Ok(persona, warnings)
    }
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull()

private fun JsonPrimitive.contentOrNull(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.contentOrNull()?.toIntOrNull()

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.contentOrNull()?.toLongOrNull()

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.contentOrNull()?.toBooleanStrictOrNull()
