package pro.openminis.app.ext.opencode.api

/**
 * 与 Minis `LLMUsage` 对齐的中立用量模型（规格 §9.7 / §9.9）。
 *
 * 刻意不复用 `pro.openminis.app.data.model.LLMUsage`：那个类带
 * `@Serializable` 且被 Room 的 `UsageChunk` 反向引用，把它拖进纯 JVM 模块会连带
 * Android 依赖。适配层（`OpencodeProviderAdapter`）负责一对一转换。
 */
data class TokenUsage(
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
) {
    /** 热力图的 `tokens` 字段（规格 §6 端点：tokens = 四项之和）。 */
    val total: Long get() = input + output + cacheRead + cacheWrite

    operator fun plus(other: TokenUsage) = TokenUsage(
        input = input + other.input,
        output = output + other.output,
        cacheRead = cacheRead + other.cacheRead,
        cacheWrite = cacheWrite + other.cacheWrite,
    )

    operator fun minus(other: TokenUsage) = TokenUsage(
        input = input - other.input,
        output = output - other.output,
        cacheRead = cacheRead - other.cacheRead,
        cacheWrite = cacheWrite - other.cacheWrite,
    )

    val isEmpty: Boolean get() = total == 0L

    companion object {
        val ZERO = TokenUsage()
    }
}
