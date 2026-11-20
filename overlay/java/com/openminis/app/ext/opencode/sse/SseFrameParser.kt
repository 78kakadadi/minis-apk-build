package com.openminis.app.ext.opencode.sse

/**
 * 一个完整的 SSE 帧。
 *
 * [isHeartbeat] 用于 `: ping` 这类注释行——它们不携带数据，但**必须刷新空闲计时器**，
 * 否则规格 §9.7 要求的「空闲超时 ≥ 5 分钟配心跳」会把健康连接误判为挂死。
 */
data class SseFrame(
    val event: String?,
    val data: String,
    val id: String? = null,
    val isHeartbeat: Boolean = false,
)

/**
 * 增量式 SSE 解析器（WHATWG EventSource 语义的一个子集）。
 *
 * 为什么需要「增量」：OkHttp 的 `EventSource` 在重连/背压下会给出任意切分的 text，
 * 一次回调可能停在半行中间。若按 `split("\n")` 粗暴解析，半行会被当成完整帧丢进
 * JSON 解析器，产生 `Unexpected end of input`，进而被误判为协议错误——这正是规格
 * §9.7「重连时 OpenCode 重放 part 快照，去重表保证幂等」要避免的抖动来源。
 *
 * 支持：`\n` / `\r\n` 行尾、多行 `data:` 以 `\n` 拼接、注释行、`id:`/`event:` 字段、
 * 未知字段忽略、末尾无空行时由 [finish] 冲刷。
 *
 * 线程约束：非线程安全，一个连接一个实例，仅在 SSE 读协程内调用。
 */
class SseFrameParser {

    private val pending = StringBuilder()
    private var eventName: String? = null
    private val dataLines = ArrayList<String>(4)
    private var lastId: String? = null

    /** 喂入任意切分的文本，返回本次可完整产出的帧（可能为空）。 */
    fun feed(text: String): List<SseFrame> {
        if (text.isEmpty()) return emptyList()
        pending.append(text)

        val out = ArrayList<SseFrame>(4)
        var consumed = 0
        while (true) {
            val nl = pending.indexOf("\n", consumed)
            if (nl < 0) break
            // 行内容不含 '\n'；CRLF 时去掉尾部 '\r'
            var line = pending.substring(consumed, nl)
            if (line.endsWith('\r')) line = line.dropLast(1)
            consumed = nl + 1
            handleLine(line, out)
        }
        if (consumed > 0) pending.delete(0, consumed)
        return out
    }

    /**
     * 流结束时调用：把残留的半行当完整行处理，并冲刷未以空行结尾的帧。
     * 断线重连时**不要**调用（残留半行要保留，等重连后补全）。
     */
    fun finish(): List<SseFrame> {
        val out = ArrayList<SseFrame>(2)
        if (pending.isNotEmpty()) {
            var line = pending.toString()
            pending.setLength(0)
            if (line.endsWith('\r')) line = line.dropLast(1)
            handleLine(line, out)
        }
        dispatch(out, force = true)
        return out
    }

    /** 清空缓冲（用于重连失败后彻底放弃该连接）。 */
    fun reset() {
        pending.setLength(0)
        eventName = null
        dataLines.clear()
        lastId = null
    }

    private fun handleLine(line: String, out: MutableList<SseFrame>) {
        if (line.isEmpty()) {
            dispatch(out, force = false)
            return
        }
        if (line[0] == ':') {
            // 注释 / 心跳：保留为心跳帧，让上层刷新空闲计时器
            out.add(SseFrame(event = null, data = "", isHeartbeat = true))
            return
        }
        val colon = line.indexOf(':')
        val field: String
        var value: String
        if (colon < 0) {
            field = line
            value = ""
        } else {
            field = line.substring(0, colon)
            value = line.substring(colon + 1)
            // 规范：冒号后若有且仅有一个前导空格，去掉它
            if (value.startsWith(" ")) value = value.substring(1)
        }
        when (field) {
            "data" -> dataLines.add(value)
            "event" -> eventName = value
            "id" -> lastId = value
            // retry / 未知字段按规范忽略
        }
    }

    private fun dispatch(out: MutableList<SseFrame>, force: Boolean) {
        val hasData = dataLines.isNotEmpty()
        val hasEvent = eventName != null
        if (!hasData && !hasEvent) {
            if (force) {
                eventName = null
                dataLines.clear()
            }
            return
        }
        // 只有 event 没有 data（如 `event: ping` 后接空行）也产出帧，交上层判断
        val data = if (hasData) dataLines.joinToString("\n") else ""
        out.add(SseFrame(event = eventName, data = data, id = lastId))
        eventName = null
        dataLines.clear()
    }
}
