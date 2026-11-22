package pro.openminis.app.ext.keypool

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

/**
 * 本地号池网关：对外是一个普通的 OpenAI 兼容端点，对内把同一个请求按 Key 池
 * 轮换地转发到真实上游。
 *
 * ## 为什么要有这一层
 *
 * 上游 Minis 的 provider 只认「一个 baseURL + 一把 Key」。把多 Key 直接塞进
 * 那个模型需要改工厂、模型选择器、导入导出，每次上游更新都会冲突。而把它做成
 * 「本地 OpenAI 端点」之后，Minis 侧只需要一个普通自定义 provider：
 *
 * ```
 * Minis ──(本地 127.0.0.1)──▶ KeyPoolGateway ──(真实上游)──▶ 中转站 / 官方
 * ```
 *
 * 轮换、冷却、重试全部发生在 [KeyPool] 里，聊天链路零改动，群聊也能直接绑定。
 *
 * ## 三条硬约束
 *
 *  1. **只在没写响应头之前换 Key**。流式一旦开始，客户端已经收到半句话，
 *     再换一把重来会出现两段互相矛盾的正文。
 *  2. **只对凭证/额度类错误换 Key**。400 参数错、403 内容审核换多少把都一样，
 *     白白消耗额度。
 *  3. **Key 不进日志**。日志只出现 id 与尾号。
 */
class KeyPoolGateway(
    private val config: () -> KeyPoolConfig,
    private val secretOf: (String) -> String?,
    private val pool: KeyPool,
    private val http: OkHttpClient,
    private val log: (String) -> Unit = {},
) {

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var port: Int = 0

    private val workers = Executors.newFixedThreadPool(8) { r ->
        Thread(r, "keypool-gateway").apply { isDaemon = true }
    }

    /** 直接给 provider 用的 baseURL（带 /v1，配合 appendV1Suffix）。 */
    val baseUrl: String get() = "http://127.0.0.1:$port/v1"

    val listeningPort: Int get() = port

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
                workers.execute { handle(client) }
            }
        }, "keypool-gateway-accept").apply { isDaemon = true }.start()
        log("号池网关监听 $baseUrl")
        return port
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        port = 0
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
            socket.soTimeout = 300_000
            try {
                val input = BufferedInputStream(socket.getInputStream())
                val req = readRequest(input) ?: return
                val out = socket.getOutputStream()
                when {
                    req.method == "GET" && req.path.startsWith("/health") ->
                        writeJson(out, 200, """{"ok":true,"service":"keypool-gateway"}""")

                    req.method == "GET" && req.path.contains("/models") -> proxyModels(out)

                    req.method == "POST" && req.path.contains("/chat/completions") ->
                        proxyChat(req, out)

                    else -> writeJson(
                        out,
                        404,
                        """{"error":{"message":"unknown path ${req.path}","type":"not_found"}}""",
                    )
                }
            } catch (e: Exception) {
                log("网关异常：${e.javaClass.simpleName}: ${e.message}")
                runCatching {
                    writeJson(
                        client.getOutputStream(),
                        502,
                        JSONObject().put(
                            "error",
                            JSONObject().put("message", "号池网关内部错误：${e.message}").put("type", "gateway_error"),
                        ).toString(),
                    )
                }
            }
        }
    }

    // ---- 聊天转发 -------------------------------------------------------

    private fun proxyChat(req: Req, out: OutputStream) {
        val obj = runCatching { JSONObject(String(req.body, Charsets.UTF_8)) }.getOrNull()
        if (obj == null) {
            writeJson(out, 400, errorJson("请求体不是合法 JSON"))
            return
        }
        val stream = obj.optBoolean("stream", false) ||
            req.headers["accept"]?.contains("text/event-stream") == true
        forwardChat(req.body, stream, out, headerAlreadySent = false)
    }

    /**
     * 把一段 OpenAI 请求原样转发（只替换 model），响应直接写到 [out]。
     *
     * [headerAlreadySent] 供免费通道降级复用：桥接在尝试本地 OpenCode 之前
     * 就已经写了 SSE 头，这时不能再写一次，但错误仍要以 SSE 事件的形式补上。
     *
     * @return true 表示响应已写出（成功或已写成错误）；false 仅用于调用方
     *         需要自行兜底的极端情况。
     */
    fun forwardChat(
        rawBody: ByteArray,
        stream: Boolean,
        out: OutputStream,
        headerAlreadySent: Boolean = false,
    ): Boolean {
        val cfg = config()
        if (!cfg.ready) {
            writeFailure(out, stream, headerAlreadySent, "号池还没配置：请先填上游地址，并至少添加一把启用的 Key。", 503)
            return true
        }
        val body = withModel(rawBody, cfg.defaultModel)
        val url = endpoint(cfg.baseUrl, "/chat/completions")

        val excluded = HashSet<String>()
        var lastError = "没有可用的 Key"
        var attempted = 0
        val maxAttempts = cfg.usableKeys.size.coerceAtLeast(1)

        while (attempted < maxAttempts) {
            val key = pool.acquireExcluding(KeyPoolConfig.PROVIDER, excluded) ?: break
            val secret = secretOf(key.id)
            if (secret.isNullOrBlank()) {
                excluded.add(key.id)
                lastError = "Key「${key.label}」的凭据读不到，请重新填写"
                continue
            }
            attempted++
            val startedAt = System.currentTimeMillis()

            val call = http.newCall(
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $secret")
                    .header("Content-Type", "application/json")
                    .header("Accept", if (stream) "text/event-stream" else "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build(),
            )

            val response = try {
                call.execute()
            } catch (e: Exception) {
                pool.onFailure(key.id, null, e.message ?: "网络异常")
                excluded.add(key.id)
                lastError = "连接上游失败：${e.message ?: e.javaClass.simpleName}"
                log("Key ${key.id} 网络失败：${e.message}")
                continue
            }

            response.use { resp ->
                val code = resp.code
                if (code !in 200..299) {
                    val text = runCatching { resp.body?.string().orEmpty() }.getOrElse { "" }
                    val action = pool.onFailure(key.id, code, text)
                    if (action is FailureAction.Rotate) {
                        excluded.add(key.id)
                        lastError = "${action.reason}：${text.take(160)}"
                        log("Key ${key.id} ${action.reason}，换下一把")
                        return@use
                    }
                    // 非凭证类错误：换 Key 也过不去，原样还给客户端
                    log("Key ${key.id} 失败（$code），不换 Key：${text.take(160)}")
                    writeRaw(out, code, text)
                    return true
                }

                if (stream) {
                    val ok = forwardStream(resp, out, key, startedAt, writeHeader = !headerAlreadySent)
                    if (ok) {
                        pool.onSuccess(key.id, System.currentTimeMillis() - startedAt)
                        return true
                    }
                    // 还没写响应头，可以安全换 Key
                    excluded.add(key.id)
                    lastError = "上游返回了错误事件"
                    return@use
                }

                val text = runCatching { resp.body?.string().orEmpty() }.getOrElse { "" }
                if (looksLikeError(text)) {
                    val action = pool.onFailure(key.id, 200, text)
                    if (action is FailureAction.Rotate) {
                        excluded.add(key.id)
                        lastError = "额度不足：${text.take(160)}"
                        return@use
                    }
                    writeRaw(out, 502, text)
                    return true
                }
                pool.onSuccess(key.id, System.currentTimeMillis() - startedAt)
                writeRaw(out, 200, text)
                return true
            }
        }

        val msg = if (attempted > 1 && excluded.size >= cfg.usableKeys.size) {
            "号池里的 ${cfg.usableKeys.size} 把 Key 都不可用（额度耗尽或失效）。" +
                "请在「设置 › API Key 池」里补充新 Key，或等冷却结束。最近原因：$lastError"
        } else {
            "号池没有可用的 Key：$lastError"
        }
        log("号池请求失败：$msg")
        writeFailure(out, stream, headerAlreadySent, msg, 502)
        return true
    }

    private fun writeFailure(
        out: OutputStream,
        stream: Boolean,
        headerAlreadySent: Boolean,
        message: String,
        code: Int,
    ) {
        if (stream) {
            if (!headerAlreadySent) writeSseHeader(out)
            out.write("data: ${errorJson(message)}\n\n".toByteArray(Charsets.UTF_8))
            out.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
            out.flush()
        } else {
            writeJson(out, code, errorJson(message))
        }
    }

    /**
     * 流式转发。
     *
     * 先读第一行再决定是否写头：上游经常用 200 + `data: {"error":...}` 表示
     * 额度不足（OpenAI 兼容层就是这么干的），这时请求还没「开始」，换 Key 是安全的。
     *
     * @return true 表示响应已完整写给了客户端；false 表示可以换 Key 重试。
     */
    private fun forwardStream(
        resp: okhttp3.Response,
        out: OutputStream,
        key: PooledKey,
        startedAt: Long,
        writeHeader: Boolean,
    ): Boolean {
        val source = resp.body?.source() ?: return false
        var firstLine = source.readUtf8Line() ?: return false
        // 跳过上游可能先发的空行/注释，但要保留下来
        val prefix = StringBuilder()
        while (firstLine.isBlank() || firstLine.startsWith(":")) {
            prefix.append(firstLine).append('\n')
            firstLine = source.readUtf8Line() ?: return false
        }
        if (looksLikeError(firstLine)) {
            pool.onFailure(key.id, 200, firstLine)
            log("Key ${key.id} 流首包即错误：${firstLine.take(160)}")
            return false
        }

        if (writeHeader) writeSseHeader(out)
        prefix.toString().takeIf { it.isNotEmpty() }?.let { out.write(it.toByteArray(Charsets.UTF_8)) }
        out.write((firstLine + "\n").toByteArray(Charsets.UTF_8))
        out.flush()

        var sawDone = firstLine.contains("[DONE]")
        val buf = ByteArray(8192)
        while (true) {
            val n = try {
                source.read(buf)
            } catch (e: Exception) {
                // 客户端断开时 out.write 会抛，这里多半是上游断流；已写过正文，
                // 只能正常收尾，不能换 Key。
                log("流式读取中断：${e.message}")
                break
            }
            if (n < 0) break
            out.write(buf, 0, n)
            out.flush()
            if (!sawDone && String(buf, 0, n, Charsets.UTF_8).contains("[DONE]")) sawDone = true
        }
        if (!sawDone) {
            out.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
            out.flush()
        }
        log("Key ${key.id} 流式完成，用时 ${System.currentTimeMillis() - startedAt}ms")
        return true
    }

    // ---- /models -------------------------------------------------------

    private fun proxyModels(out: OutputStream) {
        val cfg = config()
        if (!cfg.ready) {
            writeJson(out, 503, errorJson("号池还没配置"))
            return
        }
        val excluded = HashSet<String>()
        var lastError = ""
        var attempted = 0
        while (attempted < cfg.usableKeys.size) {
            val key = pool.acquireExcluding(KeyPoolConfig.PROVIDER, excluded) ?: break
            val secret = secretOf(key.id)
            if (secret.isNullOrBlank()) {
                excluded.add(key.id)
                continue
            }
            attempted++
            val request = Request.Builder()
                .url(endpoint(cfg.baseUrl, "/models"))
                .header("Authorization", "Bearer $secret")
                .get()
                .build()
            val text = try {
                http.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.code in 200..299 && !looksLikeError(body)) {
                        pool.onSuccess(key.id)
                        writeRaw(out, 200, body)
                        return
                    }
                    pool.onFailure(key.id, resp.code, body)
                    body
                }
            } catch (e: Exception) {
                pool.onFailure(key.id, null, e.message ?: "")
                e.message ?: "网络异常"
            }
            excluded.add(key.id)
            lastError = text.take(160)
        }
        // 清单拿不到不算致命：至少把默认模型报上去，聊天仍可指定模型
        if (cfg.defaultModel.isNotBlank()) {
            val data = JSONArray().put(
                JSONObject().put("id", cfg.defaultModel).put("object", "model").put("owned_by", "keypool"),
            )
            writeJson(out, 200, JSONObject().put("object", "list").put("data", data).toString())
            return
        }
        writeJson(out, 502, errorJson("拿不到模型清单：$lastError"))
    }

    // ---- 工具 ----------------------------------------------------------

    /** 兼容三种填法：`https://x`、`https://x/v1`、`https://x/v1/chat/completions`。 */
    internal fun endpoint(baseUrl: String, path: String): String {
        var b = baseUrl.trim().trimEnd('/')
        if (b.endsWith("/chat/completions")) b = b.removeSuffix("/chat/completions")
        if (!b.endsWith("/v1")) b = "$b/v1"
        return b + path
    }

    private fun withModel(body: ByteArray, model: String): ByteArray {
        if (model.isBlank()) return body
        return runCatching {
            val o = JSONObject(String(body, Charsets.UTF_8))
            o.put("model", model)
            o.toString().toByteArray(Charsets.UTF_8)
        }.getOrElse { body }
    }

    /** 200 里塞 error、或 SSE 的 error 事件。 */
    private fun looksLikeError(text: String): Boolean {
        val t = text.trimStart()
        if (!t.contains("\"error\"")) return false
        return !t.contains("\"choices\"")
    }

    private fun errorJson(message: String): String =
        JSONObject()
            .put("error", JSONObject().put("message", message).put("type", "keypool_unavailable"))
            .toString()

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

    private fun writeJson(out: OutputStream, code: Int, body: String) = writeRaw(out, code, body)

    private fun writeRaw(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $code ${reason(code)}\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        402 -> "Payment Required"
        403 -> "Forbidden"
        404 -> "Not Found"
        429 -> "Too Many Requests"
        503 -> "Service Unavailable"
        else -> "Error"
    }

    private fun writeSseHeader(out: OutputStream) {
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.flush()
    }
}
