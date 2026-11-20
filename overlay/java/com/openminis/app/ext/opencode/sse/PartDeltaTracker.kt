package com.openminis.app.ext.opencode.sse

/**
 * [PartDeltaTracker] 的判定结果。
 */
sealed interface DeltaResult {
    /** 有新增内容，需转发。 */
    data class Emit(val text: String) : DeltaResult

    /** 上游重放了已转发过的快照，无新增（重连幂等的主要路径）。 */
    data object Duplicate : DeltaResult

    /**
     * 快照与本地记录不一致：长度回退，或等长但尾部指纹不同。
     * 不重发已发内容（否则 token 翻倍、UI 重复），仅修正偏移并记日志。
     */
    data class Diverged(val previousOffset: Int, val newLength: Int) : DeltaResult
}

/**
 * 按 part 记录「已转发字符偏移」，把 OpenCode 的**累积全文快照**降成**增量**。
 *
 * 为什么必须做：OpenCode 的 `message.part.updated` 携带的是该 part 的**当前完整文本**，
 * 不是增量。每来一次就把全文丢给 Minis，会得到「你好你好你好」这种重复渲染，热力图
 * 也会因为重复 usage 翻倍（规格 §6 聚合规则、§9.7 text part 增量去重）。
 *
 * 三重防护：
 *  1. 长度增长 → 只取 `[offset, len)` 后缀。
 *  2. 长度相等 → 比尾部指纹；相同判 `Duplicate`（重连重放），不同判 `Diverged`。
 *  3. 长度回退 → `Diverged`，偏移收敛到新长度，绝不重发。
 *
 * [snapshot]/[restore] 支持断线重连续传（规格 §9.7「从上次 partID 与字符偏移继续」）。
 *
 * 内存上界：每个 part 只留 32 字符尾部指纹，不保留全文。
 */
class PartDeltaTracker(private val tailLength: Int = 32) {

    private data class State(val offset: Int, val tail: String)

    private val states = HashMap<String, State>(16)

    /** 处理一次累积全文快照。 */
    fun accept(partId: String, accumulated: String): DeltaResult {
        val state = states[partId]
        val offset = state?.offset ?: 0

        if (accumulated.length > offset) {
            val delta = accumulated.substring(offset)
            states[partId] = State(accumulated.length, tailOf(accumulated))
            return DeltaResult.Emit(delta)
        }

        if (accumulated.length == offset) {
            val expectedTail = state?.tail ?: ""
            val actualTail = tailOf(accumulated)
            return if (actualTail == expectedTail) {
                DeltaResult.Duplicate
            } else {
                // 等长但内容变了：上游重写了该 part（不应发生），不重发
                states[partId] = State(offset, actualTail)
                DeltaResult.Diverged(offset, accumulated.length)
            }
        }

        // 长度回退：快照变短，可能是上游重放旧版本或历史被压缩
        states[partId] = State(accumulated.length, tailOf(accumulated))
        return DeltaResult.Diverged(offset, accumulated.length)
    }

    /**
     * 处理**真增量**事件（OpenCode 部分版本发 `message.part.delta`）。
     * 直接把偏移推后，保证随后到达的 `part.updated` 全文不会重复转发。
     */
    fun acceptDelta(partId: String, delta: String) {
        if (delta.isEmpty()) return
        val state = states[partId]
        val newOffset = (state?.offset ?: 0) + delta.length
        val newTail = ((state?.tail ?: "") + delta).takeLast(tailLength)
        states[partId] = State(newOffset, newTail)
    }

    fun offsetOf(partId: String): Int = states[partId]?.offset ?: 0

    /** 导出偏移表，随重连状态一起持久化。 */
    fun snapshot(): Map<String, Int> = states.mapValues { it.value.offset }

    /** 从快照恢复。尾部指纹无法还原，恢复后第一次等长重放会走一次 `Diverged`，不影响正确性。 */
    fun restore(snapshot: Map<String, Int>) {
        states.clear()
        snapshot.forEach { (id, offset) -> states[id] = State(offset, "") }
    }

    fun forget(partId: String) {
        states.remove(partId)
    }

    fun clear() = states.clear()

    private fun tailOf(text: String): String =
        if (text.length <= tailLength) text else text.substring(text.length - tailLength)
}
