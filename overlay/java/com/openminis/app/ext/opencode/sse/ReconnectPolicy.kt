package com.openminis.app.ext.opencode.sse

/**
 * 重连退避策略（规格 §9.7「指数退避重连 1s、2s、4s，上限 30s，最多 5 次」）。
 *
 * 抽成纯函数是为了让「第 6 次还该不该重连」这种边界能被直接断言，
 * 而不是靠读代码猜。
 */
class ReconnectPolicy(
    private val initialMs: Long = 1_000L,
    private val maxMs: Long = 30_000L,
    private val maxAttempts: Int = 5,
) {
    /** [attempt] 从 1 开始（第 1 次重试）。 */
    fun delayFor(attempt: Int): Long {
        if (attempt <= 0) return initialMs
        var d = initialMs
        repeat(attempt - 1) {
            d *= 2
            if (d >= maxMs) return maxMs
        }
        return d.coerceAtMost(maxMs)
    }

    /** 是否还应重试。[attempt] 是**已经**失败的次数。 */
    fun shouldRetry(attempt: Int): Boolean = attempt < maxAttempts

    val attempts: Int get() = maxAttempts
}
