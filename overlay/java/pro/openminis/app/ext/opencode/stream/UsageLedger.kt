package pro.openminis.app.ext.opencode.stream

import pro.openminis.app.ext.opencode.api.TokenUsage

/** 流水主键：(messageID, stepIndex)。规格 §9.7 / §6 聚合规则。 */
data class UsageKey(val messageId: String, val stepIndex: Int) {
    companion object {
        /**
         * 对账差额用的伪 step。真实 step 从 0 开始，负数不会与它们冲突。
         * 用固定 key 而不是新造一条记录，是为了重复对账时能**替换**而非累加。
         */
        const val RECONCILE_STEP = -1
    }
}

/** [UsageLedger.reconcile] 的结果，供日志与热力图缓存局部失效使用。 */
data class Reconciliation(
    /** 为使合计等于上游总量而补入的差额（可能为 [TokenUsage.ZERO]）。 */
    val adjusted: TokenUsage = TokenUsage.ZERO,
    /** 上游总量小于流水合计时的差值（正数），仅记录不扣减。 */
    val mismatch: TokenUsage = TokenUsage.ZERO,
) {
    val hasMismatch: Boolean get() = !mismatch.isEmpty
}

/**
 * 按 (messageID, stepIndex) 去重的用量流水。
 *
 * 为什么必须去重：OpenCode 在流式过程中会多次推送同一 step 的 usage
 * （`message.updated` 随每个 part 刷新一次 `info.tokens`），累加会让热力图翻几倍。
 * 规格 §6「同一 (turn, step) 或 (messageID, stepIndex) 的重复 usage 样本替换而非累加」
 * 与 §9.7「usage 采集：按 (messageID, stepIndex) 键替换」是同一条约束的两种表述。
 *
 * 与 Minis 现有 `usage/UsageChunkMerger` 的区别：那个是**字段级合并**（Anthropic 的
 * message_delta 只带 output_tokens，需要与 message_start 的 input 合并）；这里是
 * **整块替换**（OpenCode 每次给的是完整 tokens 对象）。两者不能互换。
 */
class UsageLedger {

    private val steps = LinkedHashMap<UsageKey, TokenUsage>()

    /** 记录一个 step 的用量。同一 key 重复记录时**覆盖**。 */
    fun record(messageId: String, stepIndex: Int, usage: TokenUsage) {
        if (messageId.isEmpty()) return
        val key = UsageKey(messageId, stepIndex)
        steps[key] = steps[key]?.let { existing ->
            // 同一 step 的重复样本：取字段最大值而非相加。
            // 取 max 而不是直接覆盖，是因为偶发的乱序事件可能先到小值后到大值，
            // 也可能先到大值后到重放的小值；max 在两种顺序下都单调正确。
            TokenUsage(
                input = maxOf(existing.input, usage.input),
                output = maxOf(existing.output, usage.output),
                cacheRead = maxOf(existing.cacheRead, usage.cacheRead),
                cacheWrite = maxOf(existing.cacheWrite, usage.cacheWrite),
            )
        } ?: usage
    }

    fun total(): TokenUsage = steps.values.fold(TokenUsage.ZERO) { acc, u -> acc + u }

    fun stepCount(): Int = steps.size

    fun snapshot(): Map<UsageKey, TokenUsage> = LinkedHashMap(steps)

    /**
     * 用上游最终总量对账（`session.idle` 时调用）。
     *
     * 只**向上**修正：上游总量更大说明有 step 事件丢失（例如重连期间），补一条差额；
     * 上游更小则保留流水原值并报告 mismatch——扣减会让已经入账的真实消耗凭空消失，
     * 热力图的日粒度数字也随之倒退，比"略微偏高"更糟。
     */
    fun reconcile(messageId: String, finalTotal: TokenUsage): Reconciliation {
        if (messageId.isEmpty() || finalTotal.isEmpty) return Reconciliation()
        val messageSum = steps.entries
            .filter { it.key.messageId == messageId }
            .fold(TokenUsage.ZERO) { acc, e -> acc + e.value }

        val diff = finalTotal - messageSum
        return when {
            diff.isEmpty -> Reconciliation()
            diff.total > 0 -> {
                val reconcileKey = UsageKey(messageId, UsageKey.RECONCILE_STEP)
                steps[reconcileKey] = diff
                Reconciliation(adjusted = diff)
            }
            else -> Reconciliation(mismatch = TokenUsage(-diff.input, -diff.output, -diff.cacheRead, -diff.cacheWrite))
        }
    }

    fun clear() = steps.clear()
}
