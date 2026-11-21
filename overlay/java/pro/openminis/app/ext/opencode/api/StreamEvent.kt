package pro.openminis.app.ext.opencode.api

/**
 * OpenCode 事件映射后的中立流式事件。
 *
 * 与 Minis `LLMStreamChunk`（sealed class）**逐一对应**，适配层只做机械转换：
 *
 * | 本类型             | Minis LLMStreamChunk        |
 * |--------------------|-----------------------------|
 * | [Started]          | `Started`                   |
 * | [Text]             | `Text(text)`                |
 * | [Reasoning]        | `ThinkingDelta(text)`       |
 * | [ToolStart]        | `ToolUseStart(id, name)`    |
 * | [ToolInputDelta]   | `ToolInputDelta(id, acc)`   |
 * | [ToolSettled]      | **不是** `ToolCallComplete`（见下） |
 * | [StepFinish]       | `Usage(usage)` + 记录 step  |
 * | [UsageTotal]       | `Usage(usage)`              |
 * | [Finished]         | `Finished(stopReason)`      |
 * | [Failed]           | 抛 `LLMError`（适配层）      |
 *
 * ## 为什么没有 `ToolCallComplete`
 *
 * Minis 的 agent loop 收到 `ToolCallComplete` 会把工具塞进待执行队列
 * （`ChatViewModel.kt:12228` 把 block 置为 PENDING，"waiting for execution
 * dispatcher to invoke the tool"）。但免费通道的工具**已经**由本地 MCP 端点
 * 执行过一遍（规格 §9.6：MCP 请求挂起 → 转交 Minis 工具 → 回填同一 held request）。
 * 再发一个 `ToolCallComplete` 就是**双重执行**：`bash` 跑两次，`edit` 改两遍。
 *
 * 所以这里用 [ToolSettled]：宿主只更新卡片状态与输出，**不得**再调用执行器。
 * 适配层必须显式处理这个分支，且绝不能把它翻译成 `ToolCallComplete`。

 *
 * 之所以不直接复用 `LLMStreamChunk`：该 sealed class 依赖 `org.json.JSONObject`
 * 且与 Android 侧零散工具耦合；core 模块要能在纯 JVM 上跑测试（规格 §10.3
 * 要求逻辑验证走 headless JVM 测试），所以此处用 `argsJson: String` 保持中立。
 */
sealed interface StreamEvent {

    /** 流已建立、首个事件到达前发出，对应 Minis 的 `Started`。 */
    data object Started : StreamEvent

    /** 正文增量。**必须是新增部分**，去重由 [pro.openminis.app.ext.opencode.sse.PartDeltaTracker] 保证。 */
    data class Text(val text: String) : StreamEvent

    /** 推理/思考增量，UI 独立折叠展示（规格 §9.7）。 */
    data class Reasoning(val text: String) : StreamEvent

    data class ToolStart(val callId: String, val name: String) : StreamEvent

    /** 工具入参累积串（OpenCode 侧可能逐字段补全，这里透传累积值）。 */
    data class ToolInputDelta(val callId: String, val accumulated: String) : StreamEvent

    /**
     * 工具已由 MCP 桥执行完毕，**仅用于 UI 收尾**。
     *
     * [outputPreview] 取自 OpenCode 侧 `tool part.state.output`，可能被截断；
     * [isError] 为 true 时宿主把卡片标红。宿主实现必须**只更新展示**，
     * 不得把 [callId] 再交给工具执行器——否则同一个 `bash` 会跑两次。
     */
    data class ToolSettled(
        val callId: String,
        val name: String,
        val argsJson: String,
        val outputPreview: String?,
        val isError: Boolean,
    ) : StreamEvent

    /** 一个 step 结束，携带该 step 的 usage（规格 §9.7「step 完成 → step-finish」）。 */
    data class StepFinish(val stepIndex: Int, val usage: TokenUsage) : StreamEvent

    /** 会话级最终用量（session.idle 对账用）。 */
    data class UsageTotal(val usage: TokenUsage) : StreamEvent

    /** 正常收尾。`stopReason` 对齐 Minis 的 stop reason 词表（end_turn / tool_use / aborted / max_tokens）。 */
    data class Finished(val stopReason: String?) : StreamEvent

    /** 上游错误。适配层转成 `LLMError` 抛出，不进入正常 chunk 流。 */
    data class Failed(val message: String, val retryable: Boolean) : StreamEvent

    // ---- 气泡内可见的状态提示（群聊「排队中（第 N 位）」，规格 §9.10）----
    /** 排队提示。不属于 LLMStreamChunk，由群聊编排器消费。 */
    data class Queued(val position: Int) : StreamEvent
}

/** Minis 侧 stop reason 词表（`provider/StopReasons.kt` 同义）。 */
object StopReasons {
    const val END_TURN = "end_turn"
    const val TOOL_USE = "tool_use"
    const val MAX_TOKENS = "max_tokens"
    const val ABORTED = "aborted"
    const val ERROR = "error"
}
