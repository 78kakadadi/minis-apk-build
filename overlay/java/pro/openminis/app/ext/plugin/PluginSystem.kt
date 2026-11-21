package pro.openminis.app.ext.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 插件分级（规格 §8.1）。 */
enum class PluginLevel {
    /** 声明式：plugin.json + main.js，QuickJS 执行。 */
    L1,

    /** 原生：AAR/DEX，DexClassLoader 加载，安装时需高风险提示。 */
    L2,
    ;

    val riskLabel: String
        get() = when (this) {
            L1 -> "声明式插件（受沙箱限制）"
            L2 -> "原生插件（可执行任意代码，高风险）"
        }
}

/**
 * 插件权限（规格 §8.5）。
 *
 * 每个权限都带**用户可读说明**——规格 §8.4 要求「列出声明的 permissions 与 slots，
 * 展示用户可读的权限说明」，不能只把 `network` 这种机器串丢给用户。
 */
enum class PluginPermission(val wire: String, val readable: String) {
    NETWORK("network", "访问网络"),
    STORAGE("storage", "读写本插件自己的数据目录"),
    CHAT_READ("chat:read", "读取聊天消息内容"),
    CHAT_WRITE("chat:write", "向聊天插入内容"),
    TOOL_REGISTER("tool:register", "向 Agent 注册新工具"),
    WEB_SERVER("webServer", "使用宿主内置的本地 Web 服务"),
    SESSIONS("sessions", "读取会话列表与元信息"),
    ;

    companion object {
        fun fromWire(raw: String): PluginPermission? = entries.firstOrNull { it.wire == raw }
    }
}

/** 插槽类型（规格 §8.1「slots」）。UI 只能通过这些位置注入，不得替换核心页面。 */
enum class SlotType(val wire: String, val readable: String) {
    CHAT_MESSAGE_ACTION("chat.message.action", "向聊天消息行添加按钮"),
    COMPOSER_TOOLBAR("composer.toolbar", "在输入框工具栏添加按钮"),
    SETTINGS_SECTION("settings.section", "在设置页添加区块"),
    SIDE_PANEL("side.panel", "在侧栏添加面板"),
    ;

    companion object {
        fun fromWire(raw: String): SlotType? = entries.firstOrNull { it.wire == raw }
    }
}

data class DeclaredSlot(val id: String, val type: SlotType, val label: String = "")

/** 插件清单（规格 §8.1 manifest 字段）。 */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val enginesMinis: String,
    val level: PluginLevel = PluginLevel.L1,
    val permissions: List<PluginPermission> = emptyList(),
    val slots: List<DeclaredSlot> = emptyList(),
    val dependencies: Map<String, String> = emptyMap(),
    val hostEntry: String = "",
    val clientEntry: String = "",
    val unknownPermissions: List<String> = emptyList(),
)

/** 校验问题。每条都必须是**用户能看懂**的原因，不能只说 "invalid"。 */
data class ValidationIssue(val field: String, val reason: String)

data class ValidationResult(
    val manifest: PluginManifest?,
    val issues: List<ValidationIssue>,
    val warnings: List<String> = emptyList(),
) {
    val ok: Boolean get() = manifest != null && issues.isEmpty()
}

/**
 * manifest 校验器（规格 §8.4 导入校验流水线的「manifest 校验」）。
 *
 * 硬性规则：
 * - `id` 必须是反向域名（至少两段，全小写，允许数字与连字符）
 * - `version` 必须语义化（x.y.z，可带预发布后缀）
 * - `engines.minis` 必须与当前版本兼容
 * - 必填字段缺失直接拒绝
 */
object ManifestValidator {

    private val ID_PATTERN = Regex("^[a-z][a-z0-9-]*(\\.[a-z0-9-]+)+$")
    private val SEMVER_PATTERN = Regex("^\\d+\\.\\d+\\.\\d+([-+][0-9A-Za-z.-]+)?$")

    fun validate(json: String, hostMinisVersion: String): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        val warnings = mutableListOf<String>()

        val root = try {
            Json.parseToJsonElement(json).jsonObject
        } catch (e: Exception) {
            return ValidationResult(
                manifest = null,
                issues = listOf(ValidationIssue("plugin.json", "不是合法 JSON：${e.message?.take(80)}")),
            )
        }

        val id = root.str("id").orEmpty()
        if (id.isBlank()) {
            issues += ValidationIssue("id", "缺少插件 id")
        } else if (!ID_PATTERN.matches(id)) {
            issues += ValidationIssue("id", "id 必须是反向域名格式（如 com.example.myplugin），当前为 $id")
        }

        val name = root.str("name").orEmpty()
        if (name.isBlank()) issues += ValidationIssue("name", "缺少插件名称")

        val version = root.str("version").orEmpty()
        if (version.isBlank()) {
            issues += ValidationIssue("version", "缺少版本号")
        } else if (!SEMVER_PATTERN.matches(version)) {
            issues += ValidationIssue("version", "版本号必须形如 1.0.0，当前为 $version")
        }

        val enginesObj = root["engines"] as? JsonObject
        val enginesMinis = enginesObj?.str("minis").orEmpty()
        if (enginesMinis.isBlank()) {
            issues += ValidationIssue("engines.minis", "缺少 engines.minis（声明兼容的 Minis 版本范围）")
        } else if (!Engines.matches(enginesMinis, hostMinisVersion)) {
            issues += ValidationIssue(
                "engines.minis",
                "要求 Minis $enginesMinis，当前 $hostMinisVersion，不兼容",
            )
        }

        // 权限：未知权限不阻断，但要明确告知（可能来自更新版本插件）
        val unknownPerms = mutableListOf<String>()
        val perms = mutableListOf<PluginPermission>()
        (root["permissions"] as? JsonArray)?.forEach { el ->
            val raw = (el as? JsonPrimitive)?.content.orEmpty()
            val p = PluginPermission.fromWire(raw)
            if (p == null) unknownPerms += raw else perms += p
        }
        if (unknownPerms.isNotEmpty()) {
            warnings += "包含本版本不认识的权限：${unknownPerms.joinToString("、")}"
        }

        // 插槽
        val slots = mutableListOf<DeclaredSlot>()
        (root["slots"] as? JsonArray)?.forEach { el ->
            val obj = el as? JsonObject ?: return@forEach
            val slotId = obj.str("id").orEmpty()
            val typeRaw = obj.str("type").orEmpty()
            val type = SlotType.fromWire(typeRaw)
            when {
                slotId.isBlank() -> issues += ValidationIssue("slots", "插槽缺少 id")
                type == null -> issues += ValidationIssue("slots.$slotId", "未知插槽类型 $typeRaw")
                else -> slots += DeclaredSlot(slotId, type, obj.str("label").orEmpty())
            }
        }

        // 依赖
        val deps = LinkedHashMap<String, String>()
        (root["dependencies"] as? JsonObject)?.forEach { (k, v) ->
            deps[k] = (v as? JsonPrimitive)?.content.orEmpty()
        }

        // 级别判定：有 client 入口（JS）为 L1，有 host 入口（DEX/AAR）为 L2
        val hostEntry = root.str("hostEntry").orEmpty()
        val clientEntry = root.str("clientEntry").orEmpty()
        val level = if (hostEntry.isNotBlank()) PluginLevel.L2 else PluginLevel.L1
        if (level == PluginLevel.L1 && clientEntry.isBlank()) {
            issues += ValidationIssue("clientEntry", "声明式插件必须提供 clientEntry 入口")
        }
        if (level == PluginLevel.L2) {
            warnings += "该插件为原生插件，安装后可执行任意代码，请确认来源可信"
        }

        if (issues.isNotEmpty()) return ValidationResult(null, issues, warnings)

        return ValidationResult(
            manifest = PluginManifest(
                id = id,
                name = name,
                version = version,
                enginesMinis = enginesMinis,
                level = level,
                permissions = perms,
                slots = slots,
                dependencies = deps,
                hostEntry = hostEntry,
                clientEntry = clientEntry,
                unknownPermissions = unknownPerms,
            ),
            issues = emptyList(),
            warnings = warnings,
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * 版本范围匹配（规格 §8.4「engines.minis 与当前版本兼容」）。
 *
 * 支持三种写法：
 * - `>=1.2.0` / `>1.2.0` / `<=` / `<` / `=`
 * - `^1.2.0`（同一主版本，>=1.2.0 且 <2.0.0）
 * - `1.2.0`（等价于 `=1.2.0`）
 */
object Engines {

    fun matches(range: String, host: String): Boolean {
        val spec = range.trim()
        if (spec.isBlank() || spec == "*") return true

        return when {
            spec.startsWith(">=") -> compare(host, spec.removePrefix(">=").trim()) >= 0
            spec.startsWith("<=") -> compare(host, spec.removePrefix("<=").trim()) <= 0
            spec.startsWith(">") -> compare(host, spec.removePrefix(">").trim()) > 0
            spec.startsWith("<") -> compare(host, spec.removePrefix("<").trim()) < 0
            spec.startsWith("^") -> {
                val base = spec.removePrefix("^").trim()
                val lower = compare(host, base) >= 0
                val baseMajor = base.substringBefore('.').toIntOrNull() ?: return false
                val hostMajor = host.substringBefore('.').toIntOrNull() ?: return false
                lower && hostMajor == baseMajor
            }
            spec.startsWith("=") -> compare(host, spec.removePrefix("=").trim()) == 0
            else -> compare(host, spec) == 0
        }
    }

    /** 语义化版本比较。先比主，再比次，再比修订；缺省段视为 0。 */
    fun compare(a: String, b: String): Int {
        val pa = a.split('.', '-', '+')
        val pb = b.split('.', '-', '+')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i)?.toIntOrNull()
            val y = pb.getOrNull(i)?.toIntOrNull()
            if (x == null || y == null) {
                // 非数字段（预发布标签）：有后缀的视为更小
                val sx = pa.getOrNull(i).orEmpty()
                val sy = pb.getOrNull(i).orEmpty()
                if (sx == sy) continue
                return if (sx.isEmpty()) 1 else if (sy.isEmpty()) -1 else sx.compareTo(sy)
            }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}

/**
 * zip-slip 防护（规格 §8.4「路径不得越界，拒绝 ../ 与绝对路径」）。
 *
 * 这是导入流水线里唯一能直接导致「写到应用私有目录之外」的漏洞，
 * 所以单独成对象并逐条覆盖测试：`..` 段、开头的 `/`、Windows 盘符、`\\` 分隔符、
 * 以及 `a/../../b` 这种绕法。
 */
object ZipSlip {

    /**
     * 把 zip 条目名解析成 root 下的安全相对路径。
     *
     * 返回 null 表示**拒绝**（调用方应中止整个导入，而不是跳过该条目——
     * 出现越界条目本身就说明包是恶意的）。
     */
    fun safeRelativePath(entryName: String): String? {
        val raw = entryName.replace('\\', '/').trim()
        if (raw.isEmpty()) return null

        // 绝对路径：Unix 与 Windows 盘符
        if (raw.startsWith("/")) return null
        if (raw.length >= 2 && raw[1] == ':') return null

        val parts = raw.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        if (parts.any { it == ".." }) return null

        return parts.joinToString("/")
    }

    /** 便捷判定。 */
    fun isSafe(entryName: String): Boolean = safeRelativePath(entryName) != null
}

/**
 * 可撤销注册句柄（规格 §8.2「每次注册返回撤销句柄，卸载或热重载时自动撤销」）。
 *
 * 用类而不是 lambda，是为了能区分「已撤销」状态——重复 dispose 必须是幂等的，
 * 否则热重载时同一插槽被撤销两次会抛错。
 */
class Registration(val slotId: String, private val onDispose: () -> Unit) {
    var disposed: Boolean = false
        private set

    fun dispose() {
        if (disposed) return
        disposed = true
        onDispose()
    }
}

/**
 * 插槽注册表。
 *
 * 只允许注册到声明过的插槽类型；同一 pluginId 的注册可一次性撤销，
 * 保证热重载后 Compose 树里不残留旧内容（规格 §8.2）。
 */
class SlotRegistry {

    private data class Entry(
        val pluginId: String,
        val slot: DeclaredSlot,
        val content: Any,
    )

    private val entries = LinkedHashMap<String, MutableList<Entry>>()

    /** 注册一段 UI 内容，返回可撤销句柄。 */
    fun register(pluginId: String, slot: DeclaredSlot, content: Any): Registration {
        require(pluginId.isNotBlank()) { "pluginId 不能为空" }
        val list = entries.getOrPut(slot.type.wire) { mutableListOf() }
        val entry = Entry(pluginId, slot, content)
        list += entry
        return Registration(slot.id) {
            entries[slot.type.wire]?.remove(entry)
        }
    }

    fun contentsOf(type: SlotType): List<Any> =
        entries[type.wire]?.map { it.content }.orEmpty()

    fun pluginsOf(type: SlotType): List<String> =
        entries[type.wire]?.map { it.pluginId }.orEmpty()

    /** 插件卸载 / 热重载时调用：撤销它的全部注册。 */
    fun disposeAllOf(pluginId: String): Int {
        var removed = 0
        entries.values.forEach { list ->
            val before = list.size
            list.removeAll { it.pluginId == pluginId }
            removed += before - list.size
        }
        return removed
    }

    fun clear() = entries.clear()
}

/**
 * 插件上下文的能力门控（规格 §8.5）。
 *
 * 未授予的 API 调用**抛错**而不是静默返回空——静默失败会让插件作者
 * 以为功能正常，实际什么也没发生，是最难排查的一类问题。
 */
class PluginContext(
    val pluginId: String,
    private val granted: Set<PluginPermission>,
) {
    fun has(permission: PluginPermission): Boolean = permission in granted

    fun require(permission: PluginPermission) {
        if (permission !in granted) {
            throw PluginPermissionDenied(pluginId, permission)
        }
    }

    /** 插件只允许读写自己的 data 目录（规格 §8.5 沙箱边界）。 */
    fun dataPath(relative: String): String? {
        val safe = ZipSlip.safeRelativePath(relative) ?: return null
        return "plugins/$pluginId/data/$safe"
    }
}

class PluginPermissionDenied(val pluginId: String, val permission: PluginPermission) :
    SecurityException("插件 $pluginId 未声明权限：${permission.wire}（${permission.readable}）")

/** 依赖检查结果（规格 §8.4「dependencies 中的其他插件是否已安装、版本是否满足」）。 */
sealed interface DependencyCheck {
    data object Satisfied : DependencyCheck
    data class Missing(val pluginId: String, val required: String) : DependencyCheck
    data class VersionTooLow(val pluginId: String, val required: String, val installed: String) : DependencyCheck
    data class Circular(val chain: List<String>) : DependencyCheck
}

object DependencyResolver {

    /**
     * 检查 [manifest] 的依赖是否满足。
     *
     * @param installed 已安装插件的 id → 版本
     */
    fun check(
        manifest: PluginManifest,
        installed: Map<String, String>,
        allManifests: Map<String, PluginManifest> = emptyMap(),
    ): DependencyCheck {
        for ((depId, range) in manifest.dependencies) {
            val version = installed[depId]
                ?: return DependencyCheck.Missing(depId, range)
            if (range.isNotBlank() && !Engines.matches(range, version)) {
                return DependencyCheck.VersionTooLow(depId, range, version)
            }
        }

        // 环检测：只在同时提供全量清单时做（升级场景）
        if (allManifests.isNotEmpty()) {
            val cycle = findCycle(manifest.id, allManifests, mutableListOf(), mutableSetOf())
            if (cycle != null) return DependencyCheck.Circular(cycle)
        }
        return DependencyCheck.Satisfied
    }

    private fun findCycle(
        id: String,
        all: Map<String, PluginManifest>,
        path: MutableList<String>,
        visited: MutableSet<String>,
    ): List<String>? {
        if (id in path) return path.drop(path.indexOf(id)) + id
        if (!visited.add(id)) return null
        path += id
        val deps = all[id]?.dependencies?.keys.orEmpty()
        for (d in deps) {
            val found = findCycle(d, all, path, visited)
            if (found != null) return found
        }
        path.removeAt(path.lastIndex)
        return null
    }
}
