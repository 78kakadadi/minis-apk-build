package com.openminis.app.ext.persona

/**
 * 人设持久化端口。
 *
 * 核心模块不依赖 Android，所以这里只声明"读全部 / 写全部"。
 * 宿主用文件、Room 或 DataStore 实现都行；测试用内存实现。
 *
 * 为什么是"整表写入"而不是逐条 CRUD：人设总量是几十条量级，
 * 整表写入能天然保证"内存状态 = 磁盘状态"，不会出现部分写入导致
 * 内置人设缺失或 id 重复。真正的增量持久化是宿主的优化自由。
 */
interface PersonaStore {
    fun loadAll(): List<Persona>
    fun saveAll(items: List<Persona>)

    /** 应用级默认人设 id（未绑定的主 Agent 用它兜底）。 */
    fun loadDefaultId(): String? = null
    fun saveDefaultId(id: String) {}
}

/** 内存实现：测试与"先跑起来"的场景。 */
class InMemoryPersonaStore(initial: List<Persona> = emptyList()) : PersonaStore {
    private var items: List<Persona> = initial.toList()
    private var defaultId: String? = null

    override fun loadAll(): List<Persona> = items
    override fun saveAll(items: List<Persona>) {
        this.items = items.toList()
    }
    override fun loadDefaultId(): String? = defaultId
    override fun saveDefaultId(id: String) {
        defaultId = id
    }
}

sealed interface PersonaResult {
    data class Ok(val persona: Persona) : PersonaResult
    data class Rejected(val reason: String) : PersonaResult
    data class NotFound(val id: String) : PersonaResult
}

sealed interface PersonaDeleteResult {
    /**
     * 删除成功。[referencingMembers] > 0 时 UI 必须提示
     * "有 N 个成员正在使用，删除后这些成员将回退到默认人设"（规格 §4.3）。
     */
    data class Deleted(val referencingMembers: Int, val fallbackPersonaId: String) : PersonaDeleteResult
    data class Rejected(val reason: String) : PersonaDeleteResult
    data class NotFound(val id: String) : PersonaDeleteResult
}

/** 成员引用计数来源，由群聊/会话模块实现并注入。 */
fun interface PersonaUsageSource {
    fun countFor(personaId: String): Int

    companion object {
        val NONE = PersonaUsageSource { 0 }
    }
}

/**
 * 人设仓库：CRUD、内置保护、复制、导入导出（规格 §4.2–§4.4）。
 *
 * 三条不变量，都是"违反后不会报错、只会静默出错"的类型：
 *  1. 内置人设永远存在。缺失时自动补齐——否则用户把默认人设删了，新会话就没有人设可绑定。
 *  2. 内置人设不可修改、不可删除，只能复制后编辑。否则升级版本时内置项会被用户改动覆盖，
 *     或者用户的改动被版本升级覆盖，两边都难受。
 *  3. 删除被引用的人设是**允许**的，但必须回传引用数，由 UI 提示回退。
 *     直接拒绝删除会让用户无法清理（成员可能早就删了但引用计数没同步）。
 */
class PersonaRepository(
    private val store: PersonaStore,
    private val usage: PersonaUsageSource = PersonaUsageSource.NONE,
    private val idGen: () -> String = { "persona_" + java.util.UUID.randomUUID().toString().take(8) },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val cache = LinkedHashMap<String, Persona>()

    init {
        reload()
        ensureBuiltins()
    }

    private fun reload() {
        cache.clear()
        store.loadAll().forEach { cache[it.id] = it }
    }

    private fun flush() {
        store.saveAll(cache.values.toList())
    }

    /**
     * 补齐缺失的内置人设。
     *
     * 只补 id 不存在的，绝不覆盖已存在的——用户可能复制过内置项，
     * 或者曾用旧版本的内置文案，覆盖会把他们的编辑冲掉。
     */
    private fun ensureBuiltins() {
        var changed = false
        BuiltinPersonas.all(clock()).forEach { builtin ->
            if (builtin.id !in cache) {
                cache[builtin.id] = builtin
                changed = true
            }
        }
        if (changed) flush()
    }

    fun list(): List<Persona> = cache.values.toList()

    /** 列表排序：内置在前，其余按更新时间倒序，便于"最近编辑"在最上面。 */
    fun listForUi(): List<Persona> = cache.values.sortedWith(
        compareByDescending<Persona> { it.isBuiltin }
            .thenByDescending { it.updatedAt }
            .thenBy { it.name },
    )

    fun get(id: String): Persona? = cache[id]

    fun countOf(personaId: String): Int = usage.countFor(personaId)

    fun create(draft: Persona): PersonaResult {
        val now = clock()
        val persona = draft.copy(
            // 外部不能伪造内置身份
            source = if (draft.source == PersonaSource.BUILTIN) PersonaSource.USER else draft.source,
            createdAt = now,
            updatedAt = now,
        )
        val id = persona.id.ifBlank { idGen() }
        val final = persona.copy(id = id)

        final.validate().firstOrNull()?.let { return PersonaResult.Rejected(it) }
        if (id in cache) return PersonaResult.Rejected("已存在同 id 的人设：$id")

        cache[id] = final
        flush()
        return PersonaResult.Ok(final)
    }

    fun update(edited: Persona): PersonaResult {
        val existing = cache[edited.id] ?: return PersonaResult.NotFound(edited.id)
        if (existing.isBuiltin) {
            return PersonaResult.Rejected("内置人设不可直接修改，请先复制一份再编辑")
        }

        // source 跟随原记录：用户不能把导入的人设"洗白"成用户创建
        val merged = edited.copy(
            source = existing.source,
            createdAt = existing.createdAt,
            updatedAt = clock(),
        )
        merged.validate().firstOrNull()?.let { return PersonaResult.Rejected(it) }

        cache[merged.id] = merged
        flush()
        return PersonaResult.Ok(merged)
    }

    /**
     * 删除。内置项拒绝；被引用时允许删除并回传引用数。
     * [fallbackPersonaId] 是被删成员将回退到的人设。
     */
    fun delete(id: String): PersonaDeleteResult {
        val existing = cache[id] ?: return PersonaDeleteResult.NotFound(id)
        if (existing.isBuiltin) {
            return PersonaDeleteResult.Rejected("「${existing.name}」是内置人设，不能删除。可以复制一份再修改。")
        }

        val refs = usage.countFor(id)
        cache.remove(id)
        flush()

        if (store.loadDefaultId() == id) {
            store.saveDefaultId(defaultPersona().id)
        }
        return PersonaDeleteResult.Deleted(refs, defaultPersona().id)
    }

    /**
     * 复制（规格 §4.3「复制」）。内置项也可复制——这是使用内置人设的唯一正确姿势。
     * 新 id 若与已有冲突则追加序号，直到不冲突。
     */
    fun duplicate(id: String): PersonaResult {
        val src = cache[id] ?: return PersonaResult.NotFound(id)
        val now = clock()
        var candidate = "${src.id}.copy"
        var n = 2
        while (candidate in cache) {
            candidate = "${src.id}.copy$n"
            n++
        }
        val copy = src.toEditableCopy(candidate, now).copy(
            description = src.description,
            systemPrompt = src.systemPrompt,
            traits = src.traits,
            defaultModel = src.defaultModel,
            memoryScope = src.memoryScope,
            memoryConfig = src.memoryConfig,
            allowedTools = src.allowedTools,
        )
        cache[copy.id] = copy
        flush()
        return PersonaResult.Ok(copy)
    }

    /** 导出为 .mpersona 文本；id 不存在返回 null。 */
    fun export(id: String): String? = cache[id]?.let { PersonaJson.encode(it) }

    /**
     * 导入 .mpersona。id 冲突时重新分配而不是覆盖——
     * 覆盖会静默改写用户已有的同名人设，而导入的意图是"新增"。
     */
    fun import(text: String): PersonaResult {
        return when (val decoded = PersonaJson.decode(text, clock())) {
            is PersonaDecodeResult.Failed -> PersonaResult.Rejected(decoded.reason)
            is PersonaDecodeResult.Ok -> {
                val incoming = decoded.persona
                val finalId = if (incoming.id in cache) uniqueId(incoming.id) else incoming.id
                val final = incoming.copy(id = finalId)
                cache[finalId] = final
                flush()
                PersonaResult.Ok(final)
            }
        }
    }

    private fun uniqueId(base: String): String {
        var candidate = "$base-2"
        var n = 3
        while (candidate in cache) {
            candidate = "$base-$n"
            n++
        }
        return candidate
    }

    /** 应用级默认人设：优先用户设置，其次内置通用助手。 */
    fun defaultPersona(): Persona {
        val configured = store.loadDefaultId()?.let { cache[it] }
        if (configured != null) return configured
        return cache[BuiltinPersonas.GENERAL] ?: BuiltinPersonas.all(clock()).first()
    }

    fun setDefault(id: String): PersonaResult {
        val p = cache[id] ?: return PersonaResult.NotFound(id)
        store.saveDefaultId(id)
        return PersonaResult.Ok(p)
    }
}
