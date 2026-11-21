package pro.openminis.app.ext.opencode.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import pro.openminis.app.ext.opencode.model.FreeModel
import pro.openminis.app.ext.opencode.runtime.ServiceConfig
import pro.openminis.app.ext.opencode.sse.SseFrameParser
import java.io.IOException

/** 服务端返回非 2xx。带上路径与响应片段——只有状态码的报错在真机上千真万确没法查。 */
class ServerHttpException(
    val code: Int,
    val path: String,
    val bodyPreview: String,
) : IOException("$path -> HTTP $code: ${bodyPreview.take(300)}")

/**
 * OpenCode 2.x 服务端客户端。
 *
 * ## 为什么必须是 2.x 的形状
 *
 * 内置的 `opencode-bionic` 稳定线是 2.3.2。它的 HTTP 面与 1.x 有两处硬性差异，
 * 任意一处不对，表现都是「界面正常、就是没有模型」：
 *
 * 1. **路径**：全部带 `/api` 前缀（`/api/model`、`/api/session`、`/api/event`）。
 *    1.x 的 `/config/providers`、`/session`、`/prompt_async` 在 2.x 上是 404。
 * 2. **认证**：`serve` 启动时**自己生成**密码并打印到 stdout
 *    （`server password xxx`），客户端必须用这个密码做 Basic（用户名固定 `opencode`）。
 *    自己造密码的结果是每个请求 401——而 401 在旧代码里被吞成「无响应」。
 *
 * 模型列表是扁平的 `{data:[Model.Info]}`，不再是按 provider 分组的结构；
 * 对话走 `POST /api/session` + `POST /api/session/{id}/prompt`，正文通过
 * `GET /api/event` 的 SSE 增量推送。
 */
class OpencodeServerClient(
    private val http: OkHttpClient,
    private val svc: ServiceConfig,
    private val log: (String) -> Unit = {},
) {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ---- 读 ---------------------------------------------------------------

    /** 原始模型清单 JSON。解析交给 [pro.openminis.app.ext.opencode.model.ModelDiscoveryCache]，
     *  这样「怎么判断免费」能在 JVM 上单测，不必连真服务。 */
    fun modelsJson(): String {
        val req = Request.Builder()
            .url("${svc.baseUrl}/api/model")
            .header("Authorization", svc.authorization)
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ServerHttpException(resp.code, "/api/model", text)
            return text
        }
    }

    fun health(): Boolean = try {
        val req = Request.Builder()
            .url("${svc.baseUrl}/global/health")
            .header("Authorization", svc.authorization)
            .get()
            .build()
        http.newCall(req).execute().use { it.isSuccessful }
    } catch (e: Exception) {
        false
    }

    // ---- 会话 -------------------------------------------------------------

    /**
     * 建会话并指定模型。
     *
     * 2.x 的模型选择发生在建会话时（`Model.Ref{id, providerID}`），不是发送时；
     * 所以每轮「独立会话」的做法天然不会串模型。
     */
    fun createSession(model: FreeModel, title: String): String {
        val payload = JSONObject()
            .put("model", JSONObject().put("id", model.id).put("providerID", model.providerId))
            .put("title", title)
            .toString()
        val req = Request.Builder()
            .url("${svc.baseUrl}/api/session")
            .header("Authorization", svc.authorization)
            .post(payload.toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ServerHttpException(resp.code, "/api/session", text)
            val id = JSONObject(text).optJSONObject("data")?.optString("id").orEmpty()
            if (id.isBlank()) throw IllegalStateException("会话响应缺少 data.id：${text.take(200)}")
            return id
        }
    }

    /**
     * 发送本轮文本。
     *
     * 2.x 是「投递进收件箱」，返回 200 只代表已入队；真正的正文在 SSE 里。
     * 这里只关心是否成功入队，失败要带上服务端的原话。
     */
    fun prompt(sessionId: String, text: String) {
        val payload = JSONObject().put("text", text).toString()
        val req = Request.Builder()
            .url("${svc.baseUrl}/api/session/$sessionId/prompt")
            .header("Authorization", svc.authorization)
            .post(payload.toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw ServerHttpException(resp.code, "/api/session/$sessionId/prompt", resp.body?.string().orEmpty())
            }
        }
    }

    /** 尽力删除：删不掉只是服务端多留一条空会话，不影响本次回复。 */
    fun deleteSession(sessionId: String) {
        val req = Request.Builder()
            .url("${svc.baseUrl}/api/session/$sessionId")
            .header("Authorization", svc.authorization)
            .delete()
            .build()
        runCatching { http.newCall(req).execute().close() }
    }

    // ---- 事件流 -----------------------------------------------------------

    /**
     * `/api/event` 是**全局**事件流，用 `data.sessionID` 过滤。
     *
     * 先 `emit(Started)` 再读正文：桥接收到 Started 才发 prompt，
     * 否则首包（text.delta）会比订阅更早到，正文就丢了。
     *
     * 不做自动重连：一轮对话的生命周期内断流就是失败，重连会让
     * 「已输出一半 + 从头再来」变成重复文本。
     */
    fun events(sessionId: String): Flow<StreamEvent> = flow {
        val req = Request.Builder()
            .url("${svc.baseUrl}/api/event")
            .header("Authorization", svc.authorization)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()
        val call = http.newCall(req)

        // 关键：协程被取消时 `readUtf8Line` 不会自己醒——它是阻塞的 socket 读。
        // 必须在这里挂一个完成回调去 cancel 连接，否则一轮对话结束后读线程会一直挂到
        // readTimeout（180s），6 个线程的池子几轮对话就被占满，表现就是「点了没反应」。
        val job = currentCoroutineContext()[Job]
        val onDone = job?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw ServerHttpException(resp.code, "/api/event", resp.body?.string().orEmpty())
                }
                emit(StreamEvent.Started)
                val source = resp.body?.source() ?: throw IOException("/api/event 响应没有 body")
                val parser = SseFrameParser()
                // 工具事件的 name/入参分散在三个事件里（input.started / input.ended /
                // success|failed），success 只带 id 和输出。必须自己记住 id → 名字/入参，
                // 否则卡片只能显示一堆 call id。
                val tools = ToolTracker()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    currentCoroutineContext().ensureActive()
                    for (frame in parser.feed("$line\n")) {
                        for (ev in mapFrame(frame, sessionId, tools)) emit(ev)
                    }
                }
                for (frame in parser.finish()) {
                    for (ev in mapFrame(frame, sessionId, tools)) emit(ev)
                }
            }
            currentCoroutineContext().ensureActive()
        } finally {
            onDone?.dispose()
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private fun mapFrame(
        frame: pro.openminis.app.ext.opencode.sse.SseFrame,
        sessionId: String,
        tools: ToolTracker,
    ): List<StreamEvent> {
        if (frame.isHeartbeat || frame.data.isBlank()) return emptyList()
        val root = try {
            JSONObject(frame.data)
        } catch (e: Exception) {
            log("无法解析事件 JSON：${frame.data.take(120)}")
            return emptyList()
        }
        val type = root.optString("type")
        if (type.isEmpty()) return emptyList()
        val data = root.optJSONObject("data") ?: JSONObject()

        // 全局流：只处理本会话。没有 sessionID 的事件（server.connected 等）直接跳过。
        val sid = data.optString("sessionID")
        if (sid.isEmpty() || sid != sessionId) return emptyList()

        return when (type) {
            "session.text.delta" -> {
                val d = data.optString("delta")
                if (d.isEmpty()) emptyList() else listOf(StreamEvent.Text(d))
            }
            "session.reasoning.delta" -> {
                val d = data.optString("delta")
                if (d.isEmpty()) emptyList() else listOf(StreamEvent.Reasoning(d))
            }
            "session.execution.succeeded" -> listOf(StreamEvent.Finished("end_turn"))
            "session.execution.failed" -> {
                val err = data.optJSONObject("error")
                val msg = err?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: err?.optString("type")?.takeIf { it.isNotBlank() }
                    ?: "opencode 执行失败"
                listOf(StreamEvent.Failed(msg, retryable = true))
            }
            "session.execution.cancelled" -> listOf(StreamEvent.Finished("aborted"))
            "session.usage.updated" -> {
                val t = data.optJSONObject("tokens")
                if (t == null) emptyList() else listOf(
                    StreamEvent.UsageTotal(
                        TokenUsage(
                            input = t.optLong("input"),
                            output = t.optLong("output"),
                            cacheRead = t.optJSONObject("cache")?.optLong("read") ?: 0,
                            cacheWrite = t.optJSONObject("cache")?.optLong("write") ?: 0,
                        ),
                    ),
                )
            }
            // ---- 工具调用 ------------------------------------------------------
            // 免费模型会真的用 shell / read / write / webfetch 等工具干活，
            // 2.x 把这些过程单独发事件流。以前这里全部丢掉，用户只能看到
            // 「想了很久 → 一个答案」，完全不知道中间跑了什么命令。
            "session.tool.input.started" -> {
                val id = data.optString("id")
                val name = data.optString("name").ifBlank { "tool" }
                if (id.isBlank()) emptyList() else {
                    tools.name[id] = name
                    listOf(StreamEvent.ToolStart(id, name))
                }
            }
            "session.tool.input.ended" -> {
                val id = data.optString("id")
                val args = data.optString("text")
                if (id.isBlank()) emptyList() else {
                    tools.args[id] = args
                    listOf(StreamEvent.ToolInputDelta(id, args))
                }
            }
            "session.tool.called" -> {
                // input 对象比 input.ended 的 text 更完整（有些工具边 stream 边补字段）
                val id = data.optString("id")
                val input = data.optJSONObject("input")
                if (id.isBlank() || input == null) emptyList() else {
                    tools.args[id] = input.toString()
                    emptyList()
                }
            }
            "session.tool.success" -> {
                val id = data.optString("id")
                if (id.isBlank()) emptyList() else listOf(
                    StreamEvent.ToolSettled(
                        callId = id,
                        name = tools.name[id] ?: "tool",
                        argsJson = tools.args[id].orEmpty(),
                        outputPreview = toolOutput(data.optJSONArray("content")),
                        isError = false,
                    ),
                )
            }
            "session.tool.failed" -> {
                val id = data.optString("id")
                if (id.isBlank()) emptyList() else {
                    val err = data.optJSONObject("error")
                    val msg = err?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: err?.optString("type").orEmpty().ifBlank { "工具执行失败" }
                    listOf(
                        StreamEvent.ToolSettled(
                            callId = id,
                            name = tools.name[id] ?: "tool",
                            argsJson = tools.args[id].orEmpty(),
                            outputPreview = msg,
                            isError = true,
                        ),
                    )
                }
            }
            // progress 只带 shellID 之类的元数据，没有可展示内容
            else -> emptyList()
        }
    }

    /** 把 success 事件的 content 数组拼成纯文本（非 text 部分跳过）。 */
    private fun toolOutput(content: JSONArray?): String {
        if (content == null) return ""
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            if (part.optString("type") != "text") continue
            val t = part.optString("text")
            if (t.isBlank()) continue
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(t)
        }
        return sb.toString()
    }

    /**
     * 工具 id → 名字/入参。
     *
     * opencode 的 `session.tool.success` 只发 `{id, content}`，名字与入参在
     * 前面的 `input.started` / `input.ended` 里。没有这张表，卡片就只能显示
     * `chatcmpl-tool-a6a9eb09…` 这种 id，等于还是看不见。
     */
    private class ToolTracker {
        val name = HashMap<String, String>(4)
        val args = HashMap<String, String>(4)
    }
}
