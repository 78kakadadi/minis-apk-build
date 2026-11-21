package com.openminis.app.ext.opencode.bridge

import com.openminis.app.ext.opencode.api.StreamEvent
import com.openminis.app.ext.opencode.model.FreeModel
import com.openminis.app.ext.opencode.runtime.ServiceConfig
import com.openminis.app.ext.opencode.sse.OpencodeSseClient
import com.openminis.app.ext.opencode.sse.PartDeltaTracker
import com.openminis.app.ext.opencode.stream.OpencodeChunkMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一个极小的 OpenAI 兼容服务，把 `POST /v1/chat/completions` 翻译成
 * OpenCode 的 `POST /session` + `POST /session/:id/prompt_async` + SSE 事件流。
 *
 * ## 为什么是「无状态」的
 *
 * 早期版本复用同一个 OpenCode 会话、只发最后一条 user 消息，省 token，但有两个
 * 致命问题：
 *  1. 不同会话（两个聊天、群聊里的两个成员）会互相看到对方的上下文；
 *  2. 上游每次发完整 transcript，桥接却只取最后一条，历史等于被丢掉。
 *
 * 现在改成：**每个请求新建一个 OpenCode 会话，把整段 transcript 作为一段文本发过去，
 * 结束后删掉会话**。多花一些输入 token，换来的是「谁的上下文就是谁的」——
 * 群聊里 A 看不到 B 的思考过程，靠的就是这一条。
 *
 * 桥接只监听 `127.0.0.1`，随机端口，外部不可达。
 */
class OpenAiBridgeServer(
    private val service: () -> ServiceConfig?,
    private val http: OkHttpClient,
    private val models: () -> List<FreeModel>,
    private val log: (String) -> Unit = {},
) {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var port: Int = 0

    private val pool = Executors.newFixedThreadPool(6) { r ->
        Thread(r, "opencode-bridge").apply { isDaemon = true }
    }

    private val seq = AtomicInteger(0)

    val baseUrl: String get() = "http://127.0.0.1:$port"

    fun start(): Int {
        if (server != null) return port
        val s = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        port = s.localPort
        server = s
        Thread({
            while (!s.isClosed) {
                val client = try {
                    s.accept()
                } catch (e: Exception) {
                    break
                }
                pool.execute { handle(client) }
            }
        }, "opencode-bridge-accept").apply { isDaemon = true }.start()
        log("bridge listening on $baseUrl")
        return port
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        port = 0
    }

    /** 保留接口：无状态桥接下开新对话不需要清任何东西。 */
    fun resetSession() = Unit

    // ---- HTTP ----------------------------------------------------------

    private data class Req(
        val method: String,
        val path: String,
        val body: ByteArray,
        val headers: Map<String, String>,
    )

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 180_000
            try {
                val input = BufferedInputStream(socket.getInputStream())
                val req = readRequest(input) ?: return
                val out = socket.getOutputStream()
                when {
                    req.method == "GET" && req.path.startsWith("/health") -> {
                        writeJson(out, 200, """{"ok":true,"service":"opencode-bridge"}""")
                    }
                    req.method == "GET" && req.path.startsWith("/v1/models") -> {
                        val list = models()
                        val data = JSONArray()
                        list.forEach { m ->
                            data.put(
                                JSONObject()
                                    .put("id", m.id)
                                    .put("object", "model")
                                    .put("owned_by", m.providerId)
                                    .put("name", m.name),
                            )
                        }
                        writeJson(
                            out,
                            200,
                            JSONObject().put("object", "list").put("data", data).toString(),
                        )
                    }
                    req.method == "POST" && req.path.startsWith("/v1/chat/completions") -> {
                        chatCompletions(req, out)
                    }
                    else -> writeJson(
                        out,
                        404,
                        """{"error":{"message":"unknown path ${req.path}","type":"not_found"}}""",
                    )
                }
            } catch (e: Exception) {
                log("bridge error: ${e.javaClass.simpleName}: ${e.message}")
                runCatching {
                    writeJson(
                        client.getOutputStream(),
                        500,
                        JSONObject().put(
                            "error",
                            JSONObject().put("message", e.message ?: "bridge failure")
                                .put("type", "bridge_error"),
                        ).toString(),
                    )
                }
            }
        }
    }

    /** 逐行读请求头，再按 Content-Length 读 body。 */
    private fun readRequest(input: InputStream): Req? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val idx = h.indexOf(':')
            if (idx > 0) headers[h.substring(0, idx).trim().lowercase()] = h.substring(idx + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(body, read, len - read)
            if (n <= 0) break
            read += n
        }
        return Req(parts[0].uppercase(), parts[1], body, headers)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun writeJson(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun writeSseHeader(out: OutputStream) {
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/event-stream; charset=utf-8\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    // ---- Chat ----------------------------------------------------------

    private fun chatCompletions(req: Req, out: OutputStream) {
        val svc = service() ?: throw IllegalStateException("免费通道尚未就绪（本地服务未启动）")
        val body = JSONObject(String(req.body, Charsets.UTF_8))
        val model = body.optString("model").ifBlank { models().firstOrNull()?.id ?: "big-pickle" }
        val stream = body.optBoolean("stream", false)
        val messages = body.optJSONArray("messages") ?: JSONArray()

        val transcript = renderTranscript(messages)
        if (transcript.isBlank()) {
            writeJson(out, 400, """{"error":{"message":"messages 内容为空"}}""")
            return
        }

        val sid = createSession(svc)
        val mapper = OpencodeChunkMapper(sid, PartDeltaTracker())
        val sse = OpencodeSseClient(http, svc.baseUrl, svc.authorization)
        val id = "chatcmpl-${seq.incrementAndGet()}"
        val created = System.currentTimeMillis() / 1000

        if (stream) writeSseHeader(out)

        val text = StringBuilder()
        var finished = false
        var failure: String? = null

        runBlocking {
            try {
                coroutineScope {
                    launch(Dispatchers.IO) { runCatching { prompt(svc, sid, transcript, model) } }
                    sse.frames(sid).collect { frame ->
                        mapper.map(frame).events.forEach { ev ->
                            when (ev) {
                                is StreamEvent.Text -> {
                                    text.append(ev.text)
                                    if (stream) emitDelta(out, id, created, model, ev.text, null, null)
                                }
                                is StreamEvent.Reasoning -> {
                                    // OpenAI 客户端不认识这个字段，但自家的群聊引擎会读
                                    if (stream) emitDelta(out, id, created, model, null, null, ev.text)
                                }
                                is StreamEvent.Finished -> {
                                    finished = true
                                    if (stream) emitDelta(out, id, created, model, null, ev.stopReason, null)
                                }
                                is StreamEvent.Failed -> failure = ev.message
                                else -> Unit
                            }
                        }
                        if (finished || failure != null) throw StopStream()
                    }
                }
            } catch (e: StopStream) {
                // 正常收尾
            } catch (e: Exception) {
                failure = failure ?: (e.message ?: e.javaClass.simpleName)
            } finally {
                runCatching { deleteSession(svc, sid) }
            }
        }

        if (stream) {
            runCatching {
                if (failure != null) {
                    out.write(
                        "data: ${JSONObject().put("error", JSONObject().put("message", failure)).toString()}\n\n"
                            .toByteArray(Charsets.UTF_8),
                    )
                }
                out.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                out.flush()
            }
            return
        }

        if (failure != null) {
            writeJson(
                out,
                502,
                JSONObject().put(
                    "error",
                    JSONObject().put("message", failure).put("type", "upstream_error"),
                ).toString(),
            )
            return
        }

        val resp = JSONObject()
            .put("id", id)
            .put("object", "chat.completion")
            .put("created", created)
            .put("model", model)
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("message", JSONObject().put("role", "assistant").put("content", text.toString()))
                        .put("finish_reason", "stop"),
                ),
            )
        writeJson(out, 200, resp.toString())
    }

    private class StopStream : RuntimeException()

    private fun emitDelta(
        out: OutputStream,
        id: String,
        created: Long,
        model: String,
        content: String?,
        stopReason: String?,
        reasoning: String?,
    ) {
        val delta = JSONObject()
        if (content != null) delta.put("content", content)
        if (reasoning != null) delta.put("reasoning_content", reasoning)
        val choice = JSONObject()
            .put("index", 0)
            .put("delta", delta)
            .put("finish_reason", stopReason ?: JSONObject.NULL)
        val chunk = JSONObject()
            .put("id", id)
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", model)
            .put("choices", JSONArray().put(choice))
        out.write("data: $chunk\n\n".toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /**
     * 把 OpenAI 的 messages 铺成一段文本。
     *
     * OpenCode 的 prompt 只有一个 `parts[].text`，没有 role 结构；用带标签的文本
     * 表达角色，模型仍能正确区分，而历史/系统设定都不会丢。
     */
    private fun renderTranscript(messages: JSONArray): String {
        val sb = StringBuilder()
        var systemSeen = false
        val turns = StringBuilder()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role")
            val content = contentText(m.opt("content"))
            if (content.isBlank()) continue
            when (role) {
                "system", "developer" -> {
                    if (!systemSeen) {
                        sb.append("【系统设定】\n").append(content).append("\n\n")
                        systemSeen = true
                    }
                }
                "user" -> turns.append("【用户】").append(content).append("\n")
                "assistant" -> turns.append("【助手】").append(content).append("\n")
                "tool" -> turns.append("【工具结果】").append(content.take(2000)).append("\n")
                else -> Unit
            }
        }
        if (!systemSeen) sb.append("【系统设定】\n你是一个可靠的中文助手。\n\n")
        sb.append("【对话记录】\n").append(turns)
        sb.append("\n请沿用上面的设定，只输出对最后一条用户消息的回复，不要复述对话记录，")
        sb.append("也不要输出「【用户】」「【助手】」这类标签。")
        return sb.toString().trim()
    }

    private fun contentText(c: Any?): String = when (c) {
        is String -> c
        is JSONArray -> buildString {
            for (j in 0 until c.length()) {
                val part = c.optJSONObject(j) ?: continue
                if (part.optString("type") == "text") append(part.optString("text"))
            }
        }
        else -> ""
    }

    // ---- OpenCode 原生调用 ------------------------------------------------

    private fun createSession(svc: ServiceConfig): String {
        val body = JSONObject().put("title", "minis-${System.currentTimeMillis()}").toString()
        val req = Request.Builder()
            .url("${svc.baseUrl}/session")
            .header("Authorization", svc.authorization)
            .post(body.toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("创建会话失败 HTTP ${resp.code}: ${text.take(200)}")
            return JSONObject(text).optString("id").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("会话响应缺少 id: ${text.take(200)}")
        }
    }

    /** 尽力而为：删不掉只是服务端多留一条空会话，不影响本次回复。 */
    private fun deleteSession(svc: ServiceConfig, sid: String) {
        val req = Request.Builder()
            .url("${svc.baseUrl}/session/$sid")
            .header("Authorization", svc.authorization)
            .delete()
            .build()
        runCatching { http.newCall(req).execute().close() }
    }

    /**
     * 发送本轮文本。带 `model` 是让用户在设置里选的模型真正生效；
     * 老版本 OpenCode 不认识该字段会 400，此时退回不带 model 的请求。
     */
    private fun prompt(svc: ServiceConfig, sid: String, text: String, model: String) {
        val parts = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        val withModel = JSONObject().put("parts", parts)
        if (model.isNotBlank()) {
            val slash = model.indexOf('/')
            val providerID = if (slash > 0) model.substring(0, slash) else "opencode"
            val modelID = if (slash > 0) model.substring(slash + 1) else model
            withModel.put(
                "model",
                JSONObject().put("providerID", providerID).put("modelID", modelID),
            )
        }

        fun send(payload: JSONObject): Int {
            val req = Request.Builder()
                .url("${svc.baseUrl}/session/$sid/prompt_async")
                .header("Authorization", svc.authorization)
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()
            http.newCall(req).execute().use { return it.code }
        }

        var code = send(withModel)
        if (code == 400 && model.isNotBlank()) {
            code = send(JSONObject().put("parts", parts))
        }
        if (code !in 200..299) throw IllegalStateException("prompt_async 返回 HTTP $code")
    }

    companion object {
        /** 供 UI 展示的固定路径说明。 */
        const val PATH = "/v1/chat/completions"
    }
}
