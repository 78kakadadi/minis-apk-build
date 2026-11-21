package pro.openminis.app.ext.opencode.stream

import pro.openminis.app.ext.opencode.api.StreamEvent
import pro.openminis.app.ext.opencode.api.TokenUsage
import pro.openminis.app.ext.opencode.sse.DeltaResult
import pro.openminis.app.ext.opencode.sse.PartDeltaTracker
import pro.openminis.app.ext.opencode.sse.SseFrame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 把 OpenCode `/event` 的 SSE 帧映射为 [StreamEvent]（规格 §9.7）。
 *
 * 设计原则：
 *  - **绝不抛异常**。一条坏事件只能降级为 [Output.warning]，不能让整轮对话死掉。
 *  - **按 sessionID 过滤**。一个 `/event` 连接会推送服务器上所有会话的事件，
 *    群聊里多个成员共享一个 OpenCode 进程，串流就是从这里进来的。
 *  - **顺序即语义**。文本 delta 立即返回，工具事件不阻塞文本（规格 §9.7「工具交错时序」）。
 *
 * 事件形状（OpenCode server，v1.x）：
 * ```json
 * {"type":"message.part.updated","properties":{"sessionID":"ses_1","part":{
 *    "id":"prt_1","messageID":"msg_1","type":"text","text":"完整累积文本"}}}
 * {"type":"message.part.updated","properties":{"sessionID":"ses_1","part":{
 *    "id":"prt_2","messageID":"msg_1","type":"tool","callID":"call_1","tool":"bash",
 *    "state":{"status":"running","input":{"command":"ls"}}}}}
 * {"type":"message.part.updated","properties":{"sessionID":"ses_1","part":{
 *    "id":"prt_3","type":"step-finish","tokens":{"input":10,"output":2,
 *    "cache":{"read":3,"write":0}}}}}
 * {"type":"message.updated","properties":{"info":{"id":"msg_1","tokens":{...}}}}
 * {"type":"session.idle","properties":{"sessionID":"ses_1"}}
 * ```
 */
class OpencodeChunkMapper(
    private val sessionId: String,
    private val tracker: PartDeltaTracker,
) {

    data class Output(
        val events: List<StreamEvent> = emptyList(),
        val warning: String? = null,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /** step 序号：遇到 step-start 自增，step-finish 携带当前序号（0 基）。 */
    private var stepIndex: Int = -1

    /** callID -> 已发出的入参长度，避免同一份 input 重复发 ToolInputDelta。 */
    private val toolInputSent = HashMap<String, Int>(8)

    /** 已经发过 ToolStart 的 callID。 */
    private val toolStarted = HashSet<String>(8)

    /** 已经发过 ToolSettled 的 callID，保证卡片状态只落一次。 */
    private val toolSettled = HashSet<String>(8)

    fun map(frame: SseFrame): Output {
        if (frame.isHeartbeat) return Output()
        if (frame.data.isBlank()) return Output()

        val root: JsonElement = try {
            json.parseToJsonElement(frame.data)
        } catch (e: Exception) {
            return Output(warning = "SSE data is not valid JSON: ${e.message}")
        }
        val obj = root as? JsonObject
            ?: return Output(warning = "SSE data is not a JSON object")

        // 事件名：以 data.type 为准（OpenCode 两者一致），缺失时回落到 event: 字段
        val type = obj.str("type") ?: frame.event ?: return Output()
        val props = obj["properties"] as? JsonObject ?: JsonObject(emptyMap())

        // 会话过滤：session.idle / session.error 也带 sessionID
        val eventSession = props.str("sessionID") ?: props.obj("part")?.str("sessionID")
            ?: props.obj("info")?.str("sessionID")
        if (eventSession != null && eventSession != sessionId) return Output()

        return try {
            when (type) {
                "message.part.updated" -> onPartUpdated(props.obj("part"))
                "message.part.delta" -> onPartDelta(props)
                "message.updated" -> onMessageUpdated(props.obj("info"))
                "session.idle" -> Output(listOf(StreamEvent.Finished(StopReasonsLocal.END_TURN)))
                "session.error" -> Output(listOf(StreamEvent.Failed(describeError(props), retryable = true)))
                "session.compacted" -> Output() // 历史压缩，不改变 UI 流
                else -> Output()
            }
        } catch (e: Exception) {
            Output(warning = "failed to map '$type': ${e.message}")
        }
    }

    // ---- part 更新 -------------------------------------------------------

    private fun onPartUpdated(part: JsonObject?): Output {
        if (part == null) return Output()
        return when (val partType = part.str("type")) {
            "text" -> onText(part)
            "reasoning" -> onReasoning(part)
            "tool" -> onTool(part)
            "step-start" -> {
                stepIndex += 1
                Output()
            }
            "step-finish" -> {
                val usage = parseUsage(part["tokens"])
                Output(listOf(StreamEvent.StepFinish(stepIndex.coerceAtLeast(0), usage)))
            }
            "file", "snapshot", "patch", "agent", "retry" -> Output()
            else -> Output(warning = "unknown part type '$partType'")
        }
    }

    private fun onText(part: JsonObject): Output {
        val partId = part.str("id") ?: return Output(warning = "text part without id")
        val accumulated = part.str("text") ?: return Output()
        return when (val r = tracker.accept(partId, accumulated)) {
            is DeltaResult.Emit -> if (r.text.isEmpty()) Output() else
                Output(listOf(StreamEvent.Text(r.text)))
            DeltaResult.Duplicate -> Output()
            is DeltaResult.Diverged -> Output(
                warning = "text part $partId diverged (offset ${r.previousOffset} -> ${r.newLength})",
            )
        }
    }

    private fun onReasoning(part: JsonObject): Output {
        val partId = part.str("id") ?: return Output(warning = "reasoning part without id")
        val accumulated = part.str("text") ?: return Output()
        // 推理内容走同一个去重表：同一 partID 命名空间，不会与正文互相干扰
        return when (val r = tracker.accept("reasoning:$partId", accumulated)) {
            is DeltaResult.Emit -> if (r.text.isEmpty()) Output() else
                Output(listOf(StreamEvent.Reasoning(r.text)))
            DeltaResult.Duplicate -> Output()
            is DeltaResult.Diverged -> Output(warning = "reasoning part $partId diverged")
        }
    }

    /** 真增量事件：部分 OpenCode 版本直接下发 delta，省去去重猜测。 */
    private fun onPartDelta(props: JsonObject): Output {
        val delta = props.str("delta") ?: return Output()
        if (delta.isEmpty()) return Output()
        val field = props.str("field") ?: "text"
        val partId = props.str("partID") ?: props.str("id") ?: return Output()
        val key = if (field == "reasoning") "reasoning:$partId" else partId
        tracker.acceptDelta(key, delta)
        return if (field == "reasoning") {
            Output(listOf(StreamEvent.Reasoning(delta)))
        } else {
            Output(listOf(StreamEvent.Text(delta)))
        }
    }

    private fun onTool(part: JsonObject): Output {
        val callId = part.str("callID") ?: part.str("id")
            ?: return Output(warning = "tool part without callID")
        val name = part.str("tool") ?: part.str("name") ?: "tool"
        val state = part.obj("state") ?: JsonObject(emptyMap())
        val status = state.str("status") ?: "pending"
        val input = state["input"]

        val events = ArrayList<StreamEvent>(3)

        if (toolStarted.add(callId)) {
            events.add(StreamEvent.ToolStart(callId, name))
        }

        // 入参：仅在内容变化时下发，避免 UI 反复重绘
        if (input != null) {
            val inputJson = input.toString()
            val sent = toolInputSent[callId] ?: -1
            if (inputJson.length != sent) {
                toolInputSent[callId] = inputJson.length
                events.add(StreamEvent.ToolInputDelta(callId, inputJson))
            }
        }

        when (status) {
            "completed" -> if (toolSettled.add(callId)) {
                val args = input?.toString() ?: "{}"
                events.add(
                    StreamEvent.ToolSettled(
                        callId = callId,
                        name = name,
                        argsJson = args,
                        outputPreview = preview(state["output"]),
                        isError = false,
                    ),
                )
            }
            "error" -> if (toolSettled.add(callId)) {
                // 工具失败不终止流：错误由 OpenCode 自行决策，UI 只标红
                events.add(
                    StreamEvent.ToolSettled(
                        callId = callId,
                        name = name,
                        argsJson = input?.toString() ?: "{}",
                        outputPreview = preview(state["error"]) ?: "tool failed",
                        isError = true,
                    ),
                )
            }
            else -> Unit // pending / running
        }
        return Output(events)
    }

    /** 把工具输出压成一段可展示的预览，避免把整个文件内容灌进 Compose 状态。 */
    private fun preview(element: JsonElement?, limit: Int = 2000): String? {
        if (element == null) return null
        val raw = when (element) {
            is JsonPrimitive -> element.content
            else -> element.toString()
        }
        if (raw.isEmpty()) return null
        return if (raw.length <= limit) raw else raw.take(limit) + "…"
    }

    private fun onMessageUpdated(info: JsonObject?): Output {
        if (info == null) return Output()
        // 只关心 assistant 消息的累计用量；user 消息不带 tokens
        val role = info.str("role")
        if (role != null && role != "assistant") return Output()
        val tokens = info["tokens"] ?: return Output()
        val usage = parseUsage(tokens)
        if (usage.isEmpty) return Output()
        return Output(listOf(StreamEvent.UsageTotal(usage)))
    }

    // ---- 工具方法 --------------------------------------------------------

    private fun parseUsage(element: JsonElement?): TokenUsage {
        val o = element as? JsonObject ?: return TokenUsage.ZERO
        val cache = o.obj("cache")
        return TokenUsage(
            input = o.lng("input") ?: 0,
            output = o.lng("output") ?: 0,
            cacheRead = cache?.lng("read") ?: 0,
            cacheWrite = cache?.lng("write") ?: 0,
        )
    }

    private fun describeError(props: JsonObject): String {
        val err = props["error"]
        if (err is JsonObject) {
            val name = err.str("name") ?: err.str("type")
            val msg = (err["data"] as? JsonObject)?.str("message") ?: err.str("message")
            return listOfNotNull(name, msg).joinToString(": ").ifBlank { err.toString() }
        }
        return err?.toString() ?: "unknown session error"
    }
}

/** 内部常量，避免在 core 里引用 Android 侧 StopReasons。 */
private object StopReasonsLocal {
    const val END_TURN = "end_turn"
}

// ---- JsonElement 便捷扩展（null 安全，永不抛） ---------------------------

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNullSafe()

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.lng(key: String): Long? =
    (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.intOrNull?.toLong() }

private fun JsonPrimitive.contentOrNullSafe(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content.takeIf { it.isNotEmpty() }

/** 供测试与其他模块复用的数值读取。 */
internal fun JsonElement?.asLongOrNull(): Long? {
    val p = this as? JsonPrimitive ?: return null
    return p.longOrNull ?: p.intOrNull?.toLong()
}

internal fun JsonElement?.asBoolOrNull(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

/** 强转 JsonObject（供其他模块读取嵌套结构）。 */
internal fun JsonElement?.requireObj(): JsonObject? = this as? JsonObject

/** 暴露给测试：不依赖 jsonPrimitive 抛异常行为的读取。 */
internal fun JsonObject.readString(key: String): String? =
    (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }

internal fun jsonObjectOf(vararg pairs: Pair<String, JsonElement>): JsonObject =
    JsonObject(pairs.toMap())
