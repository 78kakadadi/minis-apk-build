package pro.openminis.app.ext.opencode.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 一个免费模型。 */
data class FreeModel(val id: String, val name: String, val providerId: String) {
    /** 气泡/设置页展示用。 */
    val label: String get() = name.ifBlank { id }
}

/**
 * 免费模型发现与缓存（规格 §9.8）。
 *
 * 不硬编码模型清单的原因很实际：免费池的名单每周都在变，写死的 id 一旦下线，
 * 用户看到的是「模型不存在」，而真实原因只是名单过期。所以：
 *  - 以 `GET /config/providers` 的实时结果为准；
 *  - 1 小时 TTL，避免每次发言都多一次往返；
 *  - 调用报"不可用"的模型进**短期黑名单**，下轮探测重新纳入。
 *
 * [knownFreeCandidates] 只作**顺序偏好**（优先 big-pickle），绝不作为唯一来源。
 */
class ModelDiscoveryCache(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 60 * 60 * 1000L,
) {

    data class Cached(val models: List<FreeModel>, val cachedAt: Long)

    /** 失效黑名单：id -> 失效时刻。TTL 内不再选它，过期自动复活。 */
    private val unavailable = HashMap<String, Long>()

    fun shouldRefresh(cachedAt: Long?): Boolean =
        cachedAt == null || clock() - cachedAt >= ttlMs || cachedAt > clock() + 60_000

    /** 从模型清单里挑出免费模型。兼容 2.x 扁平结构与 1.x 的 provider 分组结构。 */
    fun selectFree(payload: String): List<FreeModel> {
        val root = try {
            Json.parseToJsonElement(payload)
        } catch (e: Exception) {
            return emptyList()
        }

        // 2.x：{ location, data: [ {id, modelID, providerID, name, cost:[{input,output}]} ] }
        val flat = (root as? JsonObject)?.get("data") as? JsonArray
        if (flat != null) return preferKnown(parseFlat(flat))

        // 1.x：{ providers: [ {id, models:{ id: {...} }} ] }
        val providers = when (root) {
            is JsonArray -> root
            is JsonObject -> root["providers"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }

        val out = LinkedHashSet<FreeModel>()
        providers.forEach { p ->
            val po = p as? JsonObject ?: return@forEach
            val pid = po.str("id") ?: po.str("name") ?: "opencode"
            val models = po["models"] ?: return@forEach
            when (models) {
                is JsonArray -> models.forEach { m ->
                    val mo = m as? JsonObject ?: return@forEach
                    addIfFree(out, mo, pid)
                }
                is JsonObject -> models.forEach { (key, value) ->
                    val mo = value as? JsonObject ?: return@forEach
                    addIfFree(out, mo, pid, fallbackId = key)
                }
                else -> Unit
            }
        }
        return preferKnown(out.toList())
    }

    /**
     * 2.x 的扁平列表。id 取 `modelID`（有些版本 id 带 provider 前缀）。
     *
     * `enabled` 是服务端给出的「当前账号/计划是否可用」。它默认 true，但清单里
     * 常有一批 `enabled:false` 的模型（存在、可展示，但请求会被 403
     * `model is not available in the current token plan`）。以前忽略了它，
     * 结果选择器里一排模型点哪个都发不出去。这里直接排除，从源头少踩一脚。
     */
    private fun parseFlat(flat: JsonArray): List<FreeModel> {
        // 整份清单都不带价格，说明这个服务端根本不填 cost（免费池就是这样）。
        // 此时「没有价格」等价于「没有计费」，不能因为字段缺失就把模型全丢掉。
        // 反过来，只要清单里有模型标了价，就说明 cost 是可信的，缺失的不能算免费。
        val costReported = flat.any { e ->
            val c = (e as? JsonObject)?.get("cost") as? JsonArray
            c != null && c.isNotEmpty()
        }
        return flat.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("modelID") ?: o.str("id") ?: return@mapNotNull null
            val name = o.str("name") ?: ""
            val pid = o.str("providerID") ?: "opencode"
            if (!isEnabled(o)) return@mapNotNull null
            if (!isFree(o, id, name, missingCostIsFree = !costReported)) return@mapNotNull null
            FreeModel(id = id, name = name, providerId = pid)
        }
    }

    /** 只有显式 `false` 才算禁用；字段缺失按可用处理（旧版本没有这个字段）。 */
    private fun isEnabled(model: JsonObject): Boolean {
        val v = model["enabled"] as? JsonPrimitive ?: return true
        return v.content.toBooleanStrictOrNull() ?: true
    }

    /**
     * 免费判定。
     *
     * 2.x 给了 `cost` 数组，全 0 就是免费——这比匹配名字可靠得多：
     * 名字里的 "free" 是营销文案，会变；价格字段是计费口径，骗不了人。
     * `cost` 缺失时才回落到名字/已知清单。
     */
    private fun isFree(
        model: JsonObject,
        id: String,
        name: String,
        missingCostIsFree: Boolean = false,
    ): Boolean {
        val cost = model["cost"] as? JsonArray
        if (cost == null || cost.isEmpty()) {
            if (missingCostIsFree) return true
            val known = id in KNOWN_FREE_SET
            return known || looksFree(id) || looksFree(name)
        }
        val allZero = cost.all { c ->
            val o = c as? JsonObject ?: return@all false
            val input = (o["input"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return@all false
            val output = (o["output"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return@all false
            input == 0.0 && output == 0.0
        }
        if (allZero) return true
        val known = id in KNOWN_FREE_SET
        return known || looksFree(id) || looksFree(name)
    }

    private fun addIfFree(
        out: MutableSet<FreeModel>,
        model: JsonObject,
        providerId: String,
        fallbackId: String? = null,
    ) {
        val id = model.str("id") ?: fallbackId ?: return
        val name = model.str("name") ?: ""
        // 名字里没有 "free" 不代表不免费：`big-pickle` 就是免费池的常驻模型，
        // 规格 §9.8 点名它是首选。所以已知候选表也要参与判定，不能只靠字符串匹配。
        val known = id in KNOWN_FREE_SET
        if (!known && !looksFree(id) && !looksFree(name)) return
        out.add(FreeModel(id = id, name = name, providerId = providerId))
    }

    private fun looksFree(s: String): Boolean {
        val t = s.lowercase()
        // "free" 必须是独立词或后缀，避免把 "freedom" 之类误判
        return t == "free" || t.endsWith("-free") || t.endsWith("_free") ||
            t.contains("-free-") || t.contains(" free") || t.startsWith("free-")
    }

    /** 已知候选只影响排序：big-pickle 优先，其余按原顺序。 */
    private fun preferKnown(models: List<FreeModel>): List<FreeModel> {
        if (models.isEmpty()) return models
        val preferred = KNOWN_FREE_CANDIDATES
        return models.sortedBy { m ->
            val idx = preferred.indexOfFirst { it == m.id }
            if (idx >= 0) idx else preferred.size
        }
    }

    fun markUnavailable(modelId: String) {
        unavailable[modelId] = clock()
    }

    /** 过滤掉仍在黑名单里的模型；黑名单同样按 TTL 过期。 */
    fun available(models: List<FreeModel>): List<FreeModel> {
        val now = clock()
        unavailable.entries.removeAll { now - it.value >= ttlMs }
        return models.filterNot { it.id in unavailable }
    }

    /** 默认模型：优先 big-pickle，否则首个可用项（规格 §9.9）。 */
    fun defaultModel(models: List<FreeModel>): FreeModel? =
        available(models).firstOrNull { it.id == "big-pickle" } ?: available(models).firstOrNull()

    // ---- 持久化（~/.minis-ext/storages/opencode-models-cache.json）----

    fun encode(cached: Cached): String = buildString {
        append("{\"cachedAt\":").append(cached.cachedAt).append(",\"models\":[")
        cached.models.forEachIndexed { i, m ->
            if (i > 0) append(',')
            append("{\"id\":").append(q(m.id))
            append(",\"name\":").append(q(m.name))
            append(",\"providerId\":").append(q(m.providerId))
            append('}')
        }
        append("]}")
    }

    fun decode(text: String): Cached? {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            return null
        } as? JsonObject ?: return null
        val at = (root["cachedAt"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return null
        val arr = root["models"] as? JsonArray ?: return null
        val models = arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            FreeModel(id, o.str("name") ?: "", o.str("providerId") ?: "opencode")
        }
        return Cached(models, at)
    }

    companion object {
        /**
         * 已知免费候选（仅排序偏好）。以实时 `/config/providers` 为准，
         * 这里出现但已下线的 id 会被自动忽略。
         */
        val KNOWN_FREE_CANDIDATES = listOf(
            "big-pickle",
            // 2026-11 的免费池：清单里最常见的几个，放在前面让回退先试
            "deepseek-v4.1-flash-free",
            "glm-5.3-flash-free",
            "deepseek-v4-flash-free",
            "mimo-v2.5-free",
            "nemotron-3-ultra-free",
            "laguna-s-2.1-free",
            "longcat-2.0-free",
            "north-mini-code-free",
        )

        internal val KNOWN_FREE_SET: Set<String> = KNOWN_FREE_CANDIDATES.toHashSet()

        private fun q(s: String) = buildString {
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
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
