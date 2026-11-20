package com.openminis.app.ext.usage

import com.openminis.app.ext.opencode.api.TokenUsage
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 一次模型调用的用量记录（规格 §6 采集）。
 *
 * 比 [TokenUsage] 多出归因维度：不记录 provider / model / member / persona，
 * 明细面板就无法按成员拆分，群聊的热力图就退化成一条总量曲线。
 */
data class UsageEvent(
    val provider: String,
    val model: String,
    val usage: TokenUsage,
    /** epoch millis。存时间戳而非 LocalDate，便于跨时区重算与增量聚合。 */
    val timestamp: Long,
    val sessionId: String = "",
    val memberId: String = "",
    val personaId: String = "",
    val agentId: String = "",
    /**
     * 该通道是否提供了 token 用量。web-anon 这类通道没有 usage，
     * 只记请求数——规格 §9.11 要求「明确标注该通道未提供 token 用量」。
     */
    val tokenReported: Boolean = true,
) {
    val day: LocalDate
        get() = java.time.Instant.ofEpochMilli(timestamp)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
}

/** 单日聚合。`byModel` 用于悬停明细（规格 §6「悬停显示当日按 provider/model 拆分明细」）。 */
data class DayBucket(
    val date: LocalDate,
    val usage: TokenUsage = TokenUsage.ZERO,
    val byModel: Map<String, TokenUsage> = emptyMap(),
    val requests: Long = 0,
    /** 有任一事件标了 tokenReported=false 时为 true，用于 UI 标注。 */
    val hasUntrackedChannel: Boolean = false,
) {
    val tokens: Long get() = usage.total
}

/** 明细面板的一行（规格 §6「按 provider / model / member / persona 归属」）。 */
data class BreakdownRow(
    val label: String,
    val usage: TokenUsage,
    val share: Double,
)

/**
 * 26 周 × 7 天的 Token 使用热力图（规格 §6）。
 *
 * 设计约束：
 * - **增量聚合**：稳态 O(新增事件)，不每次全量重算 182 天；
 * - **重复样本替换而非累加**：由 [UsageLedger] 在入账前完成，这里只收「已定稿」的事件；
 * - **5 档对数色阶**：每档 10 倍，符合「对数刻度」且人眼可分辨。
 *
 * 刻意不做持久化：落盘涉及文件 IO 与并发，放到宿主侧（规格 §6 的
 * token-heatmap-cache.json）；这里只保证内存模型与序列化结果正确。
 */
class TokenHeatmap(
    /** 展示窗口的周数，规格要求近 26 周。 */
    private val weeks: Int = 26,
) {

    private val days = HashMap<LocalDate, DayBucket>()

    /** 全部出现过的模型，用于 UI 图例与筛选。 */
    private val models = LinkedHashSet<String>()
    private val providers = LinkedHashSet<String>()

    var updatedAt: Long = 0L
        private set

    /** 记账。同一事件重复提交会重复计入——去重是 [UsageLedger] 的职责。 */
    fun record(event: UsageEvent) {
        val date = event.day
        val prev = days[date]
        val merged = (prev?.usage ?: TokenUsage.ZERO) + event.usage

        val byModel = LinkedHashMap(prev?.byModel ?: emptyMap())
        if (event.model.isNotEmpty()) {
            byModel[event.model] = (byModel[event.model] ?: TokenUsage.ZERO) + event.usage
            models += event.model
        }
        if (event.provider.isNotEmpty()) providers += event.provider

        days[date] = DayBucket(
            date = date,
            usage = merged,
            byModel = byModel,
            requests = (prev?.requests ?: 0L) + 1,
            hasUntrackedChannel = (prev?.hasUntrackedChannel ?: false) || !event.tokenReported,
        )
        updatedAt = maxOf(updatedAt, event.timestamp)
    }

    fun recordAll(events: Iterable<UsageEvent>) = events.forEach(::record)

    /** 取视图窗口：从 `today` 所在周的周一起，往前 [weeks] 周。 */
    fun window(today: LocalDate = LocalDate.now()): List<DayBucket> {
        val end = today.with(DayOfWeek.MONDAY).plusWeeks(1).minusDays(1) // 本周周日
        val start = end.minusDays((weeks * 7 - 1).toLong())
        var cursor = start
        val out = ArrayList<DayBucket>(weeks * 7)
        while (!cursor.isAfter(end)) {
            out += days[cursor] ?: DayBucket(cursor)
            cursor = cursor.plusDays(1)
        }
        return out
    }

    /**
     * 按周分列（列=周，行=星期），直接对应 Canvas 绘制。
     * 返回 `List<List<DayBucket>>`，每列 7 个元素。
     */
    fun columns(today: LocalDate = LocalDate.now()): List<List<DayBucket>> =
        window(today).chunked(7)

    /**
     * 5 档对数色阶。
     *
     * 0 单独作为第 0 档（空格子），其余按 10 倍分档：
     * `<100`→1，`<1k`→2，`<10k`→3，`<100k`→4，`>=100k`→5。
     * 用固定阈值而非分位数，好处是颜色含义稳定——同一颜色在任何时候都表示同一量级，
     * 不会因为某天用量突增导致历史颜色整体漂移。
     */
    fun level(tokens: Long): Int = when {
        tokens <= 0L -> 0
        tokens < 100L -> 1
        tokens < 1_000L -> 2
        tokens < 10_000L -> 3
        tokens < 100_000L -> 4
        else -> 5
    }

    /** 顶部 chip 的过滤（规格 §6「今日 / 本周 / 本月 / 累计」）。 */
    enum class Range { TODAY, WEEK, MONTH, ALL }

    fun rangeBuckets(range: Range, today: LocalDate = LocalDate.now()): List<DayBucket> =
        when (range) {
            Range.TODAY -> listOf(days[today] ?: DayBucket(today))
            Range.WEEK -> {
                val monday = today.with(DayOfWeek.MONDAY)
                (0..6).map { monday.plusDays(it.toLong()) }.map { days[it] ?: DayBucket(it) }
            }
            Range.MONTH -> {
                val first = today.withDayOfMonth(1)
                val n = ChronoUnit.DAYS.between(first, today).toInt()
                (0..n).map { first.plusDays(it.toLong()) }.map { days[it] ?: DayBucket(it) }
            }
            Range.ALL -> days.values.sortedBy { it.date }
        }

    fun total(buckets: List<DayBucket> = days.values.sortedBy { it.date }): TokenUsage =
        buckets.fold(TokenUsage.ZERO) { acc, b -> acc + b.usage }

    fun totalRequests(buckets: List<DayBucket> = days.values.toList()): Long =
        buckets.sumOf { it.requests }

    /**
     * 明细面板：按维度拆分明细，含占比。
     *
     * 占比以 [total] 为分母；分母为 0 时全部返回 0，避免 NaN 传到 UI。
     */
    fun breakdown(
        selector: (UsageEvent) -> String,
        events: List<UsageEvent>,
    ): List<BreakdownRow> {
        if (events.isEmpty()) return emptyList()
        val grouped = LinkedHashMap<String, TokenUsage>()
        for (e in events) {
            val key = selector(e).ifBlank { "未标注" }
            grouped[key] = (grouped[key] ?: TokenUsage.ZERO) + e.usage
        }
        val sum = grouped.values.fold(TokenUsage.ZERO) { a, b -> a + b }
        val denom = sum.total.toDouble()
        return grouped.entries
            .map { (k, v) ->
                BreakdownRow(
                    label = k,
                    usage = v,
                    share = if (denom > 0) v.total / denom else 0.0,
                )
            }
            .sortedByDescending { it.usage.total }
    }

    fun knownModels(): List<String> = models.toList()
    fun knownProviders(): List<String> = providers.toList()

    fun clear() {
        days.clear()
        models.clear()
        providers.clear()
        updatedAt = 0L
    }

    /** 规格 §6 端点 payload。手写 JSON 以对齐项目现有风格（不引入 @Serializable）。 */
    fun payloadJson(today: LocalDate = LocalDate.now()): String {
        val win = window(today)
        val total = total(win)
        val sb = StringBuilder(1024)
        sb.append("{\"ok\":true,\"days\":[")
        win.forEachIndexed { i, b ->
            if (i > 0) sb.append(',')
            sb.append("{\"date\":\"").append(b.date).append("\"")
            sb.append(",\"inputTokens\":").append(b.usage.input)
            sb.append(",\"outputTokens\":").append(b.usage.output)
            sb.append(",\"cacheReadTokens\":").append(b.usage.cacheRead)
            sb.append(",\"cacheWriteTokens\":").append(b.usage.cacheWrite)
            sb.append(",\"tokens\":").append(b.tokens)
            sb.append(",\"requests\":").append(b.requests)
            sb.append(",\"models\":[")
            b.byModel.keys.forEachIndexed { j, m ->
                if (j > 0) sb.append(',')
                sb.append(quote(m))
            }
            sb.append("]}")
        }
        sb.append("],\"models\":[")
        models.forEachIndexed { i, m ->
            if (i > 0) sb.append(',')
            sb.append(quote(m))
        }
        sb.append("],\"total\":").append(total.total)
        sb.append(",\"updatedAt\":").append(updatedAt)
        sb.append('}')
        return sb.toString()
    }

    private fun quote(raw: String): String {
        val sb = StringBuilder(raw.length + 2)
        sb.append('"')
        for (c in raw) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u").append(String.format("%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
