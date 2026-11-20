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
 * ## 为什么需要这一层
 *
 * Minis 的聊天链路只认 `LLMProvider` 的 `OpenAIProvider`（`provider/openai`），
 * 它把请求发往某个 `http(s)://host/v1/chat/completions`。OpenCode 的 serve
 * 说的是自己的原生协议（`/session`、`/event`），不兼容 OpenAI。因此
 * 「免费 Token 通道」要真正可用，只有两条路：
 *
 *  1. 写一个新的 `ProviderType` 并改上游工厂——侵入面大，上游一升级就冲突；
 *  2. 在本机起一个 OpenAI 兼容端点，让上游把它当成普通的「自定义 baseURL」
 *     provider——零侵入，会话、工具、流式、错误重试全部复用上游成熟实现。
 *
 * 这里选 2。桥接只监听 `127.0.0.1`，随机端口，外部不可达。
 *
 * ## 会话语义
 *
 * OpenAI 协议里没有「会话」概念，而 OpenCode 有。桥接用「本轮只发新消息」
 * 的判据：请求里只有 1 条 user 消息 = 新会话（`POST /session`），否则复用
 * 当前会话并只发最后一条增量。这与上游每次发送完整 transcript 的行为兼容，
 * 又不会把历史重复喂给 OpenCode（重复会破坏前缀缓存）。
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

    /** 当前对话对应的 OpenCode 会话。桥接是单会话的（一次只服务一个聊天）。 */
    @Volatile
    private var sessionId: String? = null

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
        sessionId = null
        runCatching { server?.close() }
        server = null
        port = 0
    }

    /** 新建会话（用户开新对话 / 切模型时调用）。 */
    fun resetSession() {
        sessionId = null
    }

    // ---- HTTP ----------------------------------------------------------

    private data class Req(
        val method: String,
        val path: String,
        val body: ByteArray,
        val headers: Map<String, String>,
    )

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 120_000
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

        val lastUser = lastUserText(messages)
        if (lastUser.isBlank()) {
            writeJson(out, 400, """{"error":{"message":"messages 中没有 user 内容"}}""")
            return
        }

        // 只有一条 user 消息 = 新对话：新建 OpenCode 会话，避免上一轮上下文串进来
        val isNewConversation = countUser(messages) <= 1
        val sid = runBlocking { ensureSession(svc, isNewConversation) }

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
                    launch(Dispatchers.IO) { runCatching { prompt(svc, sid, lastUser, model) } }
                    sse.frames(sid).collect { frame ->
                        mapper.map(frame).events.forEach { ev ->
                            when (ev) {
                                is StreamEvent.Text -> {
                                    text.append(ev.text)
                                    if (stream) emitDelta(out, id, created, model, ev.text)
                                }
                                is StreamEvent.Finished -> {
                                    finished = true
                                    if (stream) emitDelta(out, id, created, model, null, ev.stopReason)
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
        stopReason: String? = null,
    ) {
        val delta = JSONObject()
        if (content != null) delta.put("content", content)
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

    private fun countUser(messages: JSONArray): Int {
        var n = 0
        for (i in 0 until messages.length()) {
            if (messages.optJSONObject(i)?.optString("role") == "user") n++
        }
        return n
    }

    private fun lastUserText(messages: JSONArray): String {
        for (i in messages.length() - 1 downTo 0) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") != "user") continue
            val c = m.opt("content")
            return when (c) {
                is String -> c
                is JSONArray -> buildString {
                    for (j in 0 until c.length()) {
                        val part = c.optJSONObject(j) ?: continue
                        if (part.optString("type") == "text") append(part.optString("text"))
                    }
                }
                else -> ""
            }
        }
        return ""
    }

    // ---- OpenCode 原生调用 ------------------------------------------------

    private fun ensureSession(svc: ServiceConfig, recreate: Boolean): String = synchronized(this) {
        if (!recreate) sessionId?.let { return it }
        val newId = createSession(svc)
        sessionId = newId
        newId
    }

    private fun createSession(svc: ServiceConfig): String {
        val body = JSONObject().put("title", "minis-free-${System.currentTimeMillis()}").toString()
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

    /**
     * 发送本轮增量。带 `model` 是让用户在设置里选的模型真正生效；
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
