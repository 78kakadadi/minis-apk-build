package pro.openminis.app.ext.opencode.bridge

import pro.openminis.app.ext.opencode.api.OpencodeServerClient
import pro.openminis.app.ext.opencode.api.StreamEvent
import pro.openminis.app.ext.opencode.model.FreeModel
import pro.openminis.app.ext.opencode.model.ModelDiscoveryCache
import pro.openminis.app.ext.group.GroupTools
import pro.openminis.app.ext.opencode.runtime.ServiceConfig
import pro.openminis.app.ext.usage.UsageCenter
import pro.openminis.app.ext.usage.UsageEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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
    /**
     * 请求的模型被上游拒（不在当前计划内）、回退到别的模型并成功时回调。
     *
     * 宿主用它把默认模型改成真正可用的那个，免得下一轮又从 403 开始试。
     */
    private val onModelConfirmed: (String) -> Unit = {},
) {

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var port: Int = 0

    private val pool = Executors.newFixedThreadPool(6) { r ->
        Thread(r, "opencode-bridge").apply { isDaemon = true }
    }

    private val seq = AtomicInteger(0)

    /**
     * 模型冷却表：id -> 判为「不在当前 token 计划内」的时刻。
     *
     * 上游的 plan 限制是按账号走的，短时间内不会变，半小时内不必反复撞同一个
     * 403。冷却只影响桥接选模型的顺序，不改清单本身——用户仍可手动选它。
     */
    private val cooledModels = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 查用量专用的短超时客户端。
     *
     * 回复已经流完、只差 [DONE] 时去查会话；如果沿用 180 秒 readTimeout，
     * 服务端一次卡顿就会让用户盯着「已经答完但还在转圈」。800 毫秒足够本地读一次，
     * 读不到就按估算走——宁可数字粗一点，也不能把回复卡住。
     */
    private val usageHttp: OkHttpClient = http.newBuilder()
        .callTimeout(800, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

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

        // 群聊成员的工具白名单。字段存在即「有限制」——空数组表示禁用全部工具。
        // 普通对话不带这个字段，行为完全不变。
        val toolRule: Set<String>? = body.optJSONArray("x_minis_tools")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { it.isNotBlank() }
            }.toSet()
        }

        var transcript = renderTranscript(messages)
        if (toolRule != null) transcript = toolScopePreamble(toolRule) + transcript
        if (transcript.isBlank()) {
            writeJson(out, 400, """{"error":{"message":"messages 内容为空"}}""")
            return
        }

        // 2.x 的模型是在建会话时选定的，所以先把 id 映射回 {id, providerID}。
        //
        // 「免费」不等于「当前 token 计划可用」：上游对计划外的模型直接回
        // 403 permission_denied（model is not available in the current token plan）。
        // 以前这里只发一次，失败就把 403 原样丢给用户——表现就是「免费通道用不了」。
        // 现在按清单顺序换模型重试，坏模型冷却半小时，用户侧无感。
        val id = "chatcmpl-${seq.incrementAndGet()}"
        val created = System.currentTimeMillis() / 1000
        if (stream) writeSseHeader(out)

        var failure: String? = null
        var text = StringBuilder()
        var usageSeen: pro.openminis.app.ext.opencode.api.TokenUsage? = null
        var succeeded = false
        var usedModel = model
        var tried = 0
        var allUnavailable = true
        for (candidate in candidatesFor(model)) {
            tried++
            val attempt = runAttempt(candidate, transcript, toolRule, id, created, stream, out)
            if (attempt.ok) {
                text = attempt.text
                usageSeen = attempt.usage
                usedModel = candidate.id
                succeeded = true
                // 前面attempt 的失败不能带进最终响应：已经回退成功了
                failure = null
                break
            }
            failure = attempt.error ?: "上游没有返回内容"
            // 已经输出过正文就不再换模型：重来会让用户看到两段回答拼在一起
            if (attempt.emitted || !isModelUnavailable(attempt.error)) {
                allUnavailable = false
                break
            }
            cooledModels[candidate.id] = System.currentTimeMillis()
            log("模型 ${candidate.id} 不在当前 token 计划内，换下一个：${attempt.error?.take(160)}")
        }
        // 逐个都撞了 plan 限制：这不是网络问题，别让用户对着一句 403 猜
        if (!succeeded && allUnavailable && tried > 1) {
            failure = "当前免费计划里没有可用的模型（已试 $tried 个）。" +
                "请在「设置 › 免费 Token 通道」里换一个模型，或稍后再试。上游原话：${failure?.take(160)}"
        }
        // 回退成功说明这个模型才是当前计划真正允许的，记下来，下次别再撞 403
        if (succeeded && usedModel != model) {
            runCatching { onModelConfirmed(usedModel) }
        }

        if (stream) {
            runCatching {
                // 先补一个只带 usage、choices 为空的收尾块：Minis 的 OpenAI 解析器
                // 会独立读取 usage 字段，据此把 token 写进消息、Token 用量页才算得到。
                usageSeen?.takeIf { !it.isEmpty }?.let { emitUsage(out, id, created, usedModel, it) }
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

        if (failure != null || !succeeded) {
            writeJson(
                out,
                502,
                JSONObject().put(
                    "error",
                    JSONObject().put("message", failure ?: "没有可用的免费模型").put("type", "upstream_error"),
                ).toString(),
            )
            return
        }

        val resp = JSONObject()
            .put("id", id)
            .put("object", "chat.completion")
            .put("created", created)
            .put("model", usedModel)
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("message", JSONObject().put("role", "assistant").put("content", text.toString()))
                        .put("finish_reason", "stop"),
                ),
            )
        usageSeen?.takeIf { !it.isEmpty }?.let { resp.put("usage", usageJson(it)) }
        writeJson(out, 200, resp.toString())
    }

    /** 单次尝试的结果。[emitted] 表示已经向客户端写过正文。 */
    private class Attempt(
        val ok: Boolean,
        val text: StringBuilder,
        val usage: pro.openminis.app.ext.opencode.api.TokenUsage?,
        val error: String?,
        val emitted: Boolean,
    )

    /**
     * 用指定模型完整跑一轮：建会话 → 订阅事件 → 发 prompt → 收正文。
     *
     * 抽出来是为了让外层能在「模型不在当前计划内」时换一个重来。
     * 这里不写 SSE 头、不写 [DONE]、不 decision 是否重试——那些都是外层的职责。
     */
    private fun runAttempt(
        target: FreeModel,
        transcript: String,
        toolRule: Set<String>?,
        id: String,
        created: Long,
        stream: Boolean,
        out: OutputStream,
    ): Attempt {
        val svc = service()
            ?: return Attempt(false, StringBuilder(), null, "免费通道尚未就绪（本地服务未启动）", false)
        val client = OpencodeServerClient(http, svc) { log(it) }
        val usageClient = OpencodeServerClient(usageHttp, svc) { log(it) }
        val actualModel = target.id

        // 403 通常就出在建会话这一步：服务端按 plan 校验模型，直接拒绝
        val sid = try {
            client.createSession(
                model = target,
                title = "minis-${System.currentTimeMillis()}",
                permissions = toolRule?.let { GroupTools.permissionRules(it) },
            )
        } catch (e: Exception) {
            return Attempt(false, StringBuilder(), null, e.message ?: e.javaClass.simpleName, false)
        }

        val text = StringBuilder()
        var emitted = false
        var finished = false
        var failure: String? = null
        var started = false
        // 最近一次收到本会话事件的时间。opencode 的事件流会持续发 `: heartbeat`
        // 保活帧，readTimeout 永远不会触发——如果某轮 answered 后结束事件丢失，
        // 没有 watchdog 的话这次请求就永远收不到 [DONE]，用户看到的就是
        // 「回答完了，对话却不结束」。90 秒全静默（连 delta/thinking 都没有）
        // 只可能是服务端挂了，强制收尾。
        var lastEventAt = System.currentTimeMillis()
        var usageSeen: pro.openminis.app.ext.opencode.api.TokenUsage? = null

        runBlocking {
            val collector = this
            val watchdog = launch(Dispatchers.Default) {
                while (isActive) {
                    delay(2000)
                    if (finished || failure != null) break
                    if (started && System.currentTimeMillis() - lastEventAt > IDLE_TIMEOUT_MS) {
                        // 强制收尾：正文可能已经流完了（客户端收到过 delta），只差
                        // 结束块。把 finished 置上让 finally 正常给上游发 [DONE]。
                        if (text.isNotBlank()) {
                            finished = true
                            if (stream) {
                                emitDelta(out, id, created, actualModel, null, "stop", null)
                                emitted = true
                            }
                        } else {
                            failure = "opencode $IDLE_TIMEOUT_MS/1000 秒没有任何事件，连接被重置"
                        }
                        log("watchdog 强制收尾（model=$actualModel）")
                        collector.cancel()
                        break
                    }
                }
            }
            try {
                client.events(sid).collect { ev ->
                    lastEventAt = System.currentTimeMillis()
                    when (ev) {
                        // 连接建立后才发 prompt：否则首包 text.delta 会比订阅更早到，正文就丢了
                        StreamEvent.Started -> {
                            started = true
                            try {
                                client.prompt(sid, transcript)
                            } catch (e: Exception) {
                                failure = e.message ?: e.javaClass.simpleName
                            }
                        }
                        is StreamEvent.Text -> {
                            text.append(ev.text)
                            if (stream) {
                                emitDelta(out, id, created, actualModel, ev.text, null, null)
                                emitted = true
                            }
                        }
                        is StreamEvent.Reasoning -> {
                            // OpenAI 客户端不认识这个字段，但自家的群聊引擎会读
                            if (stream) {
                                emitDelta(out, id, created, actualModel, null, null, ev.text)
                                emitted = true
                            }
                        }
                        is StreamEvent.ToolSettled -> {
                            // 工具是 opencode 在设备上真跑完的（shell/read/write…），
                            // 我们只把「跑了什么、结果是什么」当正文渲染出来给用户看。
                            // 绝不能翻成 tool_calls：那会让 Minis 的 agent loop 再执行
                            // 一遍同名工具，等于 rm 跑两次。
                            val block = renderToolBlock(ev)
                            if (block.isNotEmpty()) {
                                // 工具先于正文时，别让消息以两个空行开头
                                val piece = if (text.isEmpty()) block.trimStart() else block
                                text.append(piece)
                                if (stream) {
                                    emitDelta(out, id, created, actualModel, piece, null, null)
                                    emitted = true
                                }
                            }
                        }
                        is StreamEvent.Finished -> {
                            finished = true
                            if (stream) {
                                emitDelta(out, id, created, actualModel, null, ev.stopReason, null)
                                emitted = true
                            }
                        }
                        is StreamEvent.Failed -> failure = ev.message
                        is StreamEvent.UsageTotal -> usageSeen = ev.usage
                        else -> Unit
                    }
                    if (finished || failure != null) throw StopStream()
                }
                if (!finished && failure == null) failure = "事件流提前结束（未收到结束事件）"
            } catch (e: StopStream) {
                // 正常收尾
            } catch (e: kotlinx.coroutines.CancellationException) {
                // watchdog 主动收尾时 cancel 会走到这里，别把已完成的回复记成失败
                if (!finished && failure == null) failure = "生成被中断"
            } catch (e: Exception) {
                failure = failure ?: (e.message ?: e.javaClass.simpleName)
            } finally {
                watchdog.cancel()
                // 会话不能在这里删：用量事件常常晚于结束事件，删掉就再也查不到 tokens 了
            }
        }

        // 用量补齐：只查一次、走 800ms 短超时。这里以前是「3 次 × 1.5 秒 + sleep」，
        // 一旦服务端读会话慢，每条消息都要多等 5 秒，用户体感就是「卡死」。
        if (usageSeen == null) {
            val fetched = runCatching { usageClient.sessionUsage(sid) }.getOrNull()
            if (fetched != null && !fetched.isEmpty) {
                usageSeen = fetched
                log("用量从会话信息补齐：${fetched.total} tokens（model=$actualModel）")
            }
        }
        // 最后的兜底：个别通道什么都不报。给一个按字符估算的量级，但热力图标「未上报」。
        var estimatedUsage = false
        val knownUsage = usageSeen
        if ((knownUsage == null || knownUsage.isEmpty) && text.isNotBlank()) {
            val est = estimateUsage(transcript, text.toString())
            if (!est.isEmpty) {
                usageSeen = est
                estimatedUsage = true
                log("通道未上报用量，按字符估算 ${est.total} tokens（model=$actualModel）")
            }
        }
        // 删除也用短超时客户端：DELETE 若卡住，180 秒的 readTimeout 会占死桥接线程
        runCatching { usageClient.deleteSession(sid) }

        // 免费通道的真实用量进热力图。普通对话与群聊的免费成员都经过这里。
        runCatching {
            val u = usageSeen
            UsageCenter.record(
                UsageEvent(
                    provider = "opencode",
                    model = actualModel,
                    usage = u ?: pro.openminis.app.ext.opencode.api.TokenUsage.ZERO,
                    timestamp = System.currentTimeMillis(),
                    sessionId = sid,
                    tokenReported = u != null && !u.isEmpty && !estimatedUsage,
                ),
            )
        }

        return Attempt(failure == null, text, usageSeen, failure, emitted)
    }

    /**
     * 本次请求的候选模型：请求的那个排第一，其余按清单顺序；冷却中的跳过。
     *
     * 清单部分最多取 [LIST_CANDIDATES] 个，剩下的名额留给已知免费候选——
     * `/api/model` 里「看着免费」的模型可能整批都不在当前计划内，而真正能用的
     * 那个反倒不在清单里（或被判成收费）。全试一遍太慢，所以两边各留位置。
     */
    private fun candidatesFor(requested: String): List<FreeModel> {
        val now = System.currentTimeMillis()
        cooledModels.entries.removeAll { now - it.value > MODEL_COOLDOWN_MS }
        val list = models()
        val hit = list.firstOrNull { it.id == requested }
        val ordered = LinkedHashSet<FreeModel>()
        if (hit != null && !cooledModels.containsKey(hit.id)) ordered.add(hit)
        list.asSequence()
            .filterNot { it.id == hit?.id }
            .filterNot { cooledModels.containsKey(it.id) }
            .take(LIST_CANDIDATES)
            .forEach { ordered.add(it) }
        // 清单为空或全在冷却时，至少还能试一次请求的那个，别直接判「没有可用模型」
        if (ordered.isEmpty()) (hit ?: list.firstOrNull())?.let { ordered.add(it) }
        ModelDiscoveryCache.KNOWN_FREE_CANDIDATES.asSequence()
            .filterNot { id -> ordered.any { it.id == id } }
            .take(MAX_MODEL_ATTEMPTS - ordered.size)
            .forEach { ordered.add(FreeModel(it, it, "opencode")) }
        return ordered.take(MAX_MODEL_ATTEMPTS)
    }

    /** 上游把「模型不在当前计划内」的失败，值得换一个模型重试。 */
    private fun isModelUnavailable(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val s = message.lowercase()
        return s.contains("token plan") ||
            s.contains("permission_denied") ||
            s.contains("model is not available") ||
            s.contains("model not available") ||
            (s.contains("403") && s.contains("model"))
    }

    private class StopStream : RuntimeException()

    // ---- 工具调用展示 -----------------------------------------------------

    /**
     * 把一次工具调用渲染成可见的 markdown 块。
     *
     * ## 为什么走正文，而不是 OpenAI 的 `tool_calls`
     *
     * Minis 收到 `tool_calls` 会把工具塞进自己的 agent loop 排队执行。
     * 但免费通道的工具是 opencode 在设备上**已经跑完**的（shell/read/write…），
     * 再发一次 tool_calls 就是双重执行：`ls` 跑两遍、`edit` 改两遍、
     * 带副作用的命令直接出事。所以这里只做「展示」：把工具名、入参、
     * 输出当 markdown 追加到回复正文里，用户能看见，agent loop 不碰。
     */
    private fun renderToolBlock(ev: StreamEvent.ToolSettled): String {
        val name = ev.name.ifBlank { "tool" }
        val arg = summarizeArgs(ev.argsJson)
        val head = buildString {
            append("🛠 **").append(name).append("**")
            if (arg.isNotBlank()) append(" · `").append(arg).append("`")
            if (ev.isError) append(" · 失败")
        }
        val out = ev.outputPreview.orEmpty().trimEnd()
        if (out.isBlank()) return "\n\n$head\n\n"

        val lines = out.lines()
        val clipped = lines.take(MAX_TOOL_LINES).joinToString("\n").take(MAX_TOOL_CHARS)
        val truncated = clipped.length < out.length
        return buildString {
            append("\n\n").append(head).append("\n\n")
            append("```text\n").append(clipped).append("\n```")
            if (truncated) append("\n（输出较长，已截断）")
            append("\n\n")
        }
    }

    /** 从入参 JSON 里挑一个最能说明「在干什么」的字段，压成一行。 */
    private fun summarizeArgs(argsJson: String): String {
        if (argsJson.isBlank()) return ""
        val obj = runCatching { JSONObject(argsJson) }.getOrNull()
        val raw = if (obj != null) {
            listOf("command", "path", "filePath", "file_path", "pattern", "url", "query", "tool_title")
                .firstNotNullOfOrNull { k -> obj.optString(k).takeIf { it.isNotBlank() } }
                ?: obj.toString()
        } else argsJson
        return raw.replace('\n', ' ').replace('`', '\'').trim().take(MAX_ARG_CHARS)
    }

    /**
     * 把历史回复里的工具块剥掉。
     *
     * 这些块是给用户看的，不该当成「助手说过的话」再喂回模型——一轮 shell 输出
     * 几百行，塞进上下文除了浪费 token 没别的用。只处理助手消息。
     */
    private fun stripToolBlocks(text: String): String {
        if (!text.contains("🛠 ")) return text
        val lines = text.lines()
        val out = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (!line.trimStart().startsWith("🛠 ")) {
                out.append(line).append('\n')
                i++
                continue
            }
            // 进入工具块：跳过标题行，再看紧跟的是不是围栏代码块
            i++
            var fences = 0
            var consumedFence = false
            while (i < lines.size) {
                val t = lines[i].trimStart()
                if (t.startsWith("```")) {
                    consumedFence = true
                    fences++
                    i++
                    if (fences >= 2) break
                    continue
                }
                if (!consumedFence) {
                    // 标题与围栏之间有空行；无输出的工具块则直接跟正文。
                    if (t.isBlank()) { i++; continue }
                    // 遇到正文：这一行要保留，不能吞掉
                    break
                }
                i++
            }
        }
        return out.toString().trim().replace(Regex("\n{3,}"), "\n\n")
    }

    /** 通道完全不上报用量时的量级估算：中日韩字符按 1 token，其余按 4 字符 1 token。 */
    private fun estimateUsage(transcript: String, reply: String): pro.openminis.app.ext.opencode.api.TokenUsage {
        fun count(s: String): Long {
            var cjk = 0L
            var other = 0L
            for (ch in s) {
                val cp = ch.code
                val wide = cp in 0x2E80..0x9FFF || cp in 0xAC00..0xD7AF || cp in 0xF900..0xFAFF
                if (wide) cjk++ else other++
            }
            return cjk + other / 4
        }
        return pro.openminis.app.ext.opencode.api.TokenUsage(
            input = count(transcript),
            output = count(reply),
        )
    }

    /** OpenAI 形状的 usage。prompt_tokens 必须含缓存部分，Minis 会再减回去。 */
    private fun usageJson(usage: pro.openminis.app.ext.opencode.api.TokenUsage): JSONObject {
        val prompt = usage.input + usage.cacheRead + usage.cacheWrite
        val completion = usage.output
        return JSONObject()
            .put("prompt_tokens", prompt)
            .put("completion_tokens", completion)
            .put("total_tokens", prompt + completion)
            .put(
                "prompt_tokens_details",
                JSONObject()
                    .put("cached_tokens", usage.cacheRead)
                    .put("cache_write_tokens", usage.cacheWrite),
            )
    }

    /**
     * 流末尾的用量块：`choices` 为空，只带 `usage`。
     *
     * OpenAI 的 `include_usage` 就是这个形状；Minis 的解析器对 choices 为空
     * 的 chunk 只读 usage，不会当成空回复。少了这一块，Token 用量页就永远是 0。
     */
    private fun emitUsage(
        out: OutputStream,
        id: String,
        created: Long,
        model: String,
        usage: pro.openminis.app.ext.opencode.api.TokenUsage,
    ) {
        val chunk = JSONObject()
            .put("id", id)
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", model)
            .put("choices", JSONArray())
            .put("usage", usageJson(usage))
        out.write("data: $chunk\n\n".toByteArray(Charsets.UTF_8))
        out.flush()
    }

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
     * 在 transcript 前声明这一轮的工具白名单。
     *
     * 会话权限是「硬拒」：模型真调用被禁的工具会被挡下来。但它会反复试，把一轮
     * 对话耗在重试上。先把「这一轮有什么工具」说清楚，能消掉绝大多数无效尝试；
     * 权限规则再兜住那些仍然要试的。
     */
    private fun toolScopePreamble(allowed: Set<String>): String {
        if (allowed.isEmpty()) {
            return "【工具】本轮你没有可用工具：不要调用任何工具，直接凭已有知识回答。\n\n"
        }
        val names = GroupTools.SPECS
            .filter { it.id in allowed }
            .joinToString("、") { "${it.label}(${it.id})" }
        return "【工具】本轮只允许调用这些工具：$names。其他工具已禁用，不要尝试；" +
            "不需要工具时直接回答。\n\n"
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
                "assistant" -> turns.append("【助手】").append(stripToolBlocks(content)).append("\n")
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

    // ---- 上游调用 --------------------------------------------------------

    /**
     * 把 Minis 传来的模型 id 映射成 2.x 需要的 `{id, providerID}`。
     *
     * 找不到时退回清单第一个、再退回 big-pickle：宁可给个能用的模型，
     * 也不要因为 id 对不上直接 400——那在用户看来就是「通道坏了」。
     */
    private fun resolveModel(requested: String): FreeModel {
        val list = models()
        val hit = list.firstOrNull { it.id == requested }
        return hit ?: list.firstOrNull() ?: FreeModel("big-pickle", "Big Pickle", "opencode")
    }

    companion object {
        /** 供 UI 展示的固定路径说明。 */
        const val PATH = "/v1/chat/completions"

        /**
         * 事件流全静默的兜底上限。正常推理不会全程静默这么久（thinking/delta
         * 都会刷新计时）；超过只说明服务端挂了。heartbeat 保活帧不会刷新计时，
         * 所以 readTimeout 在这条路径上失效，只能靠它。
         */
        const val IDLE_TIMEOUT_MS = 90_000L

        /** 一次请求最多试几个模型；再多就是拿用户的时间赌清单里总有一个能用的。 */
        const val MAX_MODEL_ATTEMPTS = 6

        /** 候选里来自 /api/model 清单的最多几个（其余名额留给已知免费候选）。 */
        const val LIST_CANDIDATES = 3

        /** 被判「不在当前计划内」的模型冷却多久（30 分钟）。 */
        const val MODEL_COOLDOWN_MS = 30 * 60 * 1000L

        /** 工具输出最多展示的行数/字符数：`read` 一个文件就是几百行，全贴没法看。 */
        const val MAX_TOOL_LINES = 24
        const val MAX_TOOL_CHARS = 1_500

        /** 工具入参摘要的长度上限。 */
        const val MAX_ARG_CHARS = 110
    }
}
