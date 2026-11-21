package pro.openminis.app.ext.opencode.sse

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** 上游返回了非 2xx。[retryable] 决定是否走重连。 */
class SseHttpException(
    val code: Int,
    val bodyPreview: String,
    val retryable: Boolean,
) : IOException("SSE HTTP $code: $bodyPreview")

/**
 * 消费 OpenCode 的会话级事件流（规格 §9.7）。
 *
 * 为什么不用 okhttp-sse 的 `EventSources`：它的重连是内部行为，不暴露
 * 「上次 partID / 字符偏移」，也无法在断线时把去重表接上（规格要求
 * 「从上次 partID 与字符偏移继续」）。这里用普通 Call 逐行读，自己控制重连，
 * 才能保证重放快照是幂等的。
 *
 * 背压：`emit` 挂起时循环停在这里，不再从 socket 读——依赖 TCP 窗口自然限速，
 * 不做无界缓冲（规格 §9.7「消费慢时暂停 SSE 读取而非无限缓冲」）。
 *
 * 取消：协程取消时 finally 里 `call.cancel()` 关掉 socket，阻塞中的
 * `readUtf8Line` 立刻返回，不会泄漏线程。
 */
class OpencodeSseClient(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val authorization: String,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    /** 测试可替换为不退避的 0 延迟。 */
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {

    /** 事件流。调用方应在 `session.idle` 或用户停止时取消收集。 */
    fun frames(sessionId: String): Flow<SseFrame> {
        val url = buildUrl(sessionId)
        return flow {
            var failures = 0
            var call: Call? = null
            try {
                while (true) {
                    val request = Request.Builder()
                        .url(url)
                        .header("Authorization", authorization)
                        .header("Accept", "text/event-stream")
                        .header("Cache-Control", "no-cache")
                        .build()

                    val parser = SseFrameParser()
                    call = http.newCall(request)
                    try {
                        call.execute().use { response ->
                            if (!response.isSuccessful) {
                                val preview = response.body?.string()?.take(300).orEmpty()
                                throw SseHttpException(
                                    code = response.code,
                                    bodyPreview = preview,
                                    // 401/403/404 重试只会得到同样结果
                                    retryable = response.code !in NON_RETRYABLE,
                                )
                            }
                            val source = response.body?.source()
                                ?: throw IOException("SSE response has no body")

                            while (true) {
                                val line = source.readUtf8Line() ?: break
                                // 逐行喂给解析器：与单元测试共用同一套分帧逻辑
                                parser.feed("$line\n").forEach { emit(it) }
                            }
                            parser.finish().forEach { emit(it) }
                        }
                        // 正常 EOF：SSE 本不该结束，视为断线，按策略重连
                        currentCoroutineContext().ensureActive()
                        failures += 1
                        if (!policy.shouldRetry(failures)) return@flow
                        sleeper(policy.delayFor(failures))
                    } catch (e: SseHttpException) {
                        // 取消时 socket 被关，异常类型仍是 IOException 派生物；
                        // 不先判断 isActive 就会在已取消的协程里继续重连（死循环）
                        currentCoroutineContext().ensureActive()
                        if (!e.retryable) throw e
                        failures += 1
                        if (!policy.shouldRetry(failures)) throw e
                        sleeper(policy.delayFor(failures))
                    } catch (e: IOException) {
                        currentCoroutineContext().ensureActive()
                        failures += 1
                        if (!policy.shouldRetry(failures)) throw e
                        sleeper(policy.delayFor(failures))
                    }
                }
            } finally {
                call?.cancel()
            }
        }.flowOn(Dispatchers.IO)
    }

    private fun buildUrl(sessionId: String) =
        "$baseUrl/event?sessionID=$sessionId".toHttpUrl()

    companion object {
        private val NON_RETRYABLE = setOf(400, 401, 403, 404, 422)
    }
}
