package pro.openminis.app.ext.opencode.account

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import pro.openminis.app.ext.opencode.runtime.ServiceConfig
import java.util.concurrent.TimeUnit

/** 一条已保存的 OpenCode 账号凭据。 */
data class OpencodeAccount(
    val id: String,
    val label: String,
    val active: Boolean,
    /** 只留尾号，界面与日志都不出现完整密钥。 */
    val keyTail: String?,
    val method: String,
)

/**
 * 一次设备码登录。
 *
 * [url] 里已经带了 user_code，直接打开即可；[userCode] 单独给「在另一台设备上打开」
 * 的场景，用户可以手抄。
 */
data class DeviceLogin(
    val attemptId: String,
    val url: String,
    val userCode: String,
    val instructions: String,
    val expiresAt: Long,
)

class OpencodeAccountException(message: String) : Exception(message)

/**
 * OpenCode 账号接入。
 *
 * 关键取舍：**不自己写 auth.json，也不改 opencode 的数据库**。
 * 2.3.2 把凭据存进 `opencode.db`，`auth.json` 已不再被读取（实测手写后
 * `auth list` 仍为空）；而服务端本来就提供了凭据接口，走它才有版本兼容性：
 *
 *  - `POST /api/integration/opencode/connect/oauth` 设备码登录（推荐，无需抄 Key）
 *  - `POST /api/integration/opencode/connect/key`   用 API Key 登录
 *  - `GET/DELETE /api/credential`                  列出 / 退出账号
 *  - `POST /api/credential/{id}/activate`          多账号切换
 *
 * 所有方法都是同步阻塞的，调用方负责放到 IO 线程。
 */
class OpencodeAccountService(
    private val service: () -> ServiceConfig?,
    private val http: OkHttpClient = defaultClient(),
    private val zenBaseUrl: String = "https://opencode.ai/zen/v1",
) {

    /** 免费通道没启动时，本地服务地址不存在，任何账号操作都无从谈起。 */
    fun requireService(): ServiceConfig =
        service() ?: throw OpencodeAccountException("免费通道还没启动，先在设置页点「启用通道」。")

    fun list(): List<OpencodeAccount> {
        val text = call("/api/credential")
        val arr = runCatching { JSONObject(text).optJSONArray("data") }.getOrNull() ?: JSONArray()
        val out = ArrayList<OpencodeAccount>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("integrationID") != INTEGRATION) continue
            val value = o.optJSONObject("value")
            val key = value?.optString("key").orEmpty()
            out += OpencodeAccount(
                id = o.optString("id"),
                label = o.optString("label").ifBlank { "OpenCode 账号" },
                active = o.optBoolean("active", false),
                keyTail = key.takeIf { it.length >= 4 }?.takeLast(4),
                method = value?.optString("type").orEmpty().ifBlank { "key" },
            )
        }
        // 当前生效的排在最前面，用户一眼能看到「现在用的是哪个」
        return out.sortedByDescending { it.active }
    }

    /** 发起设备码登录：返回验证码与授权页地址，随后由 UI 轮询 [poll]。 */
    fun startDeviceLogin(): DeviceLogin {
        val text = call(
            path = "/api/integration/$INTEGRATION/connect/oauth",
            method = "POST",
            body = JSONObject().put("methodID", "device").toString(),
        )
        val data = JSONObject(text).optJSONObject("data")
            ?: throw OpencodeAccountException("运行时没有返回登录链接，请重启服务后重试。")
        val url = data.optString("url")
        if (url.isBlank()) throw OpencodeAccountException("运行时没有返回登录链接，请重启服务后重试。")
        val code = Regex("[A-Z0-9]{4}-[A-Z0-9]{4}")
            .find(url)?.value
            ?: Regex("[A-Z0-9]{4}-[A-Z0-9]{4}").find(data.optString("instructions"))?.value
            ?: ""
        return DeviceLogin(
            attemptId = data.optString("attemptID"),
            url = url,
            userCode = code,
            instructions = data.optString("instructions"),
            expiresAt = data.optJSONObject("time")?.optLong("expires") ?: 0L,
        )
    }

    /**
     * 轮询一次登录结果。
     *
     * 返回 `pending` / `complete` / `expired` / `failed`。真实取值由运行时决定，
     * 这里只做归一化：非 pending 一律当终态处理，UI 再去 [list] 确认到底存没存上。
     */
    fun poll(attemptId: String): String {
        val text = call("/api/integration/$INTEGRATION/connect/oauth/$attemptId")
        val status = JSONObject(text).optJSONObject("data")?.optString("status").orEmpty()
        return status.ifBlank { "pending" }
    }

    fun cancel(attemptId: String) {
        runCatching { call("/api/integration/$INTEGRATION/connect/oauth/$attemptId", method = "DELETE") }
    }

    /**
     * 用 API Key 登录。
     *
     * 先拿 Key 打一次 zen 的 `/models`：匿名访问也是 200，所以 401 一定是
     * 「Key 无效」而不是「没权限」；网络不通时**仍然保存**，只把原因作为
     * 返回值交给 UI——离线状态下拦着不让存，等于把用户锁在门外。
     */
    fun loginWithKey(key: String, label: String?): String? {
        val trimmed = key.trim()
        if (trimmed.length < 8) throw OpencodeAccountException("Key 太短，检查一下是否复制完整。")
        val warning = verifyKey(trimmed)
        val body = JSONObject()
            .put("key", trimmed)
            .put("label", label?.trim()?.ifBlank { null } ?: defaultLabel(trimmed))
            .toString()
        call("/api/integration/$INTEGRATION/connect/key", method = "POST", body = body)
        return warning
    }

    fun logout(credentialId: String) {
        call("/api/credential/$credentialId", method = "DELETE")
    }

    fun activate(credentialId: String) {
        call("/api/credential/$credentialId/activate", method = "POST", body = "{}")
    }

    /** 返回 null 表示校验通过；返回字符串是「已保存但没能校验」的原因。 */
    private fun verifyKey(key: String): String? {
        val req = Request.Builder()
            .url("$zenBaseUrl/models")
            .header("Authorization", "Bearer $key")
            .get()
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> null
                    resp.code == 401 || resp.code == 403 ->
                        throw OpencodeAccountException("这个 Key 无效或已被撤销，请到 opencode.ai/auth 重新生成。")
                    else -> "已保存，但校验返回 HTTP ${resp.code}，可能还没生效。"
                }
            }
        } catch (e: OpencodeAccountException) {
            throw e
        } catch (e: Exception) {
            "已保存，但当前网络无法校验（${e.message ?: e.javaClass.simpleName}）。"
        }
    }

    private fun defaultLabel(key: String): String =
        "账号 · ${key.takeLast(4)}"

    private fun call(path: String, method: String = "GET", body: String? = null): String {
        val svc = requireService()
        val builder = Request.Builder().url(svc.baseUrl + path)
        if (svc.password.isNotEmpty()) builder.header("Authorization", svc.authorization)
        when {
            body != null -> builder.method(method, body.toRequestBody(JSON))
            method == "GET" -> builder.get()
            else -> builder.method(method, ByteArray(0).toRequestBody(null))
        }
        return try {
            http.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw OpencodeAccountException(readableError(resp.code, text))
                text
            }
        } catch (e: OpencodeAccountException) {
            throw e
        } catch (e: Exception) {
            throw OpencodeAccountException(
                "连不上本地运行时（${e.message ?: e.javaClass.simpleName}），重启服务后再试。",
            )
        }
    }

    private fun readableError(code: Int, text: String): String {
        val detail = runCatching {
            val o = JSONObject(text)
            o.optString("message").ifBlank {
                o.optJSONObject("error")?.optString("message").orEmpty()
            }
        }.getOrDefault("")
        return when {
            code == 401 -> "本地服务认证失败，重启服务后再试。"
            code == 404 -> detail.ifBlank { "运行时版本不支持账号接口，请更新运行时。" }
            detail.isNotBlank() -> detail
            else -> "运行时返回 HTTP $code"
        }
    }

    private companion object {
        const val INTEGRATION = "opencode"
        val JSON = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
