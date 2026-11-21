package pro.openminis.app.ext.opencode.api

import org.json.JSONObject

/**
 * 解析 OpenCode 的 `TokenUsage.Info`。
 *
 * 2.3.2 的形状是 `{input, output, reasoning, cache:{read, write}}`——注意
 * `reasoning` 与 `output` 是**并列**的，不包含在 output 里；OpenCode 自己的
 * 总量算法就是 `input + output + reasoning + cache.read + cache.write`。
 *
 * 本项目的 [TokenUsage] 只有四项，所以把 reasoning 并进 output：这样
 * [TokenUsage.total] 与 OpenCode 的总量一致，热力图不会少算思考 token。
 *
 * 同一个解析器同时用于两个来源：
 * - SSE 的 `session.usage.updated` 事件（实时，但可能晚于结束事件）；
 * - `GET /api/session/{id}` 返回的 `data.tokens`（兜底，结束时一定有）。
 */
object OpencodeUsage {

    fun parse(tokens: JSONObject?): TokenUsage? {
        if (tokens == null) return null
        val cache = tokens.optJSONObject("cache")
        val usage = TokenUsage(
            input = tokens.optLong("input").coerceAtLeast(0),
            output = (tokens.optLong("output") + tokens.optLong("reasoning")).coerceAtLeast(0),
            cacheRead = (cache?.optLong("read") ?: 0).coerceAtLeast(0),
            cacheWrite = (cache?.optLong("write") ?: 0).coerceAtLeast(0),
        )
        return usage
    }

    /** 从 `GET /api/session/{id}` 的完整响应里取用量；结构不对就返回 null。 */
    fun fromSessionResponse(body: String): TokenUsage? = runCatching {
        val data = JSONObject(body).optJSONObject("data") ?: return null
        parse(data.optJSONObject("tokens"))
    }.getOrNull()
}
