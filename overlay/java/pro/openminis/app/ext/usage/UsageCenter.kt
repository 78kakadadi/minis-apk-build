package pro.openminis.app.ext.usage

import pro.openminis.app.ext.opencode.api.TokenUsage
import org.json.JSONObject
import java.io.File

/**
 * Token 用量的真实采集入口（热力图 / Token 用量页的数据源）。
 *
 * 为什么不用 [TokenHeatmap] 自带的落盘：那个类刻意只做内存模型，落盘是宿主的
 * 责任——这里就是宿主。做法是 JSONL 追加（每行一个事件）：追加写几乎不耗 IO，
 * 崩溃最多丢最后一行，也不会像整文件重写那样在写一半时读到残缺 JSON。
 *
 * 采集点：
 * - 免费 token（普通对话 + 群聊免费成员）：[pro.openminis.app.ext.opencode.bridge.OpenAiBridgeServer]
 *   在收到 opencode 的 `session.usage.updated` 时记录；
 * - 群聊里绑定已存 API 的成员：直连外部 API，bridge 看不到，由群聊引擎记录。
 *
 * demo 数据（loadDemo）已从热力图页移除——页面上看到的每一格都是真实调用。
 */
object UsageCenter {

    @Volatile
    private var file: File? = null

    private val heatmap = TokenHeatmap()

    /** 冷启动加载历史。未初始化时 record 只进内存（测试用），不落盘。 */
    fun init(context: android.content.Context) = ensureInit(context)

    /** 幂等初始化：bridge / 群聊记账前、热力图页打开前都必须调用一次。 */
    fun ensureInit(context: android.content.Context) {
        if (file != null) return
        synchronized(this) {
            if (file != null) return
            val f = File(context.filesDir, "token-usage-events.jsonl")
            file = f
            if (f.exists()) {
                runCatching {
                    val events = f.readLines().mapNotNull { line ->
                        if (line.isBlank()) return@mapNotNull null
                        runCatching { eventFromJson(JSONObject(line)) }.getOrNull()
                    }
                    heatmap.recordAll(events)
                }
            }
        }
    }

    /** 一次真实调用的用量。bridge（免费）与群聊引擎（绑定 API）可能并发记账，这里串行化。 */
    fun record(event: UsageEvent) {
        synchronized(this) {
            heatmap.record(event)
            val f = file ?: return
            runCatching {
                f.parentFile?.mkdirs()
                f.appendText(eventToJson(event).toString() + "\n")
            }
        }
    }

    fun heatmap(): TokenHeatmap = heatmap

    /** 清空全部用量（热力图页的「清空」按钮）。 */
    fun clearAll() {
        synchronized(this) {
            heatmap.clear()
            runCatching { file?.delete() }
        }
    }

    // ---- JSONL 序列化 ------------------------------------------------------

    private fun eventToJson(e: UsageEvent): JSONObject = JSONObject()
        .put("provider", e.provider)
        .put("model", e.model)
        .put("input", e.usage.input)
        .put("output", e.usage.output)
        .put("cacheRead", e.usage.cacheRead)
        .put("cacheWrite", e.usage.cacheWrite)
        .put("timestamp", e.timestamp)
        .put("sessionId", e.sessionId)
        .put("memberId", e.memberId)
        .put("personaId", e.personaId)
        .put("tokenReported", e.tokenReported)

    private fun eventFromJson(o: JSONObject): UsageEvent = UsageEvent(
        provider = o.optString("provider"),
        model = o.optString("model"),
        usage = TokenUsage(
            input = o.optLong("input"),
            output = o.optLong("output"),
            cacheRead = o.optLong("cacheRead"),
            cacheWrite = o.optLong("cacheWrite"),
        ),
        timestamp = o.optLong("timestamp"),
        sessionId = o.optString("sessionId"),
        memberId = o.optString("memberId"),
        personaId = o.optString("personaId"),
        tokenReported = o.optBoolean("tokenReported", true),
    )
}
