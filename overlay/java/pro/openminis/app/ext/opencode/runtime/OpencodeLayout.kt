package pro.openminis.app.ext.opencode.runtime

import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * OpenCode 运行时的目录布局与进程环境（规格 §9.3 / §9.5）。
 *
 * 硬约束：
 *  - 一切可写内容落在 `filesDir/opencode/`，**不写**系统目录、不依赖 Termux 的 `$PREFIX`；
 *  - 通过 `HOME` 与四个 `XDG_*` 重定向，让 OpenCode 认为自己的家就在这里；
 *  - **绝不触碰** `~/.config/opencode`（规格 §4.6 明确要求）。
 */
class OpencodeLayout(filesDir: File) {

    val root: File = File(filesDir, "opencode")

    val config: File = File(root, "config")
    val data: File = File(root, "data")
    val state: File = File(root, "state")
    val cache: File = File(root, "cache")
    val tmp: File = File(root, "tmp")
    val workspace: File = File(root, "workspace")

    /** OpenCode 读取的配置路径是 `$XDG_CONFIG_HOME/opencode/opencode.jsonc`。 */
    val opencodeConfigDir: File = File(config, "opencode")
    val configFile: File = File(opencodeConfigDir, "opencode.jsonc")

    /** 人设/记忆指令文件目录（规格 §4.6 的 per-session 指令注入）。 */
    val instructionsDir: File = File(opencodeConfigDir, "instructions")

    /** 应用私有服务描述（端口 + Basic 密码 + PID），OpenCode 自己不读这个文件。 */
    val serviceFile: File = File(root, "service.json")

    /** 日志目录：初始化失败的**可读原因**落在这里，不静默吞错（规格 §9.5）。 */
    val logsDir: File = File(root, "logs")
    val serverLog: File = File(logsDir, "server.log")
    val initLog: File = File(logsDir, "init.log")

    /** 需要创建的目录全集，顺序即创建顺序。 */
    fun directories(): List<File> = listOf(
        root, config, data, state, cache, tmp, workspace,
        opencodeConfigDir, instructionsDir, logsDir,
    )

    /**
     * 子进程环境。刻意**不继承**父进程的 `XDG_*` / `HOME` /
     * `OPENCODE_CONFIG`，否则用户桌面的配置会渗进来，或反过来污染宿主机。
     */
    fun environment(base: Map<String, String> = System.getenv()): Map<String, String> {
        val env = LinkedHashMap(base)
        // 清掉可能让 OpenCode 找到别处配置的变量
        listOf(
            "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_STATE_HOME", "XDG_CACHE_HOME",
            "OPENCODE_CONFIG", "OPENCODE_CONFIG_CONTENT", "OPENCODE_DISABLE_AUTOUPDATE",
            "PREFIX", "TERMUX_VERSION",
        ).forEach(env::remove)

        env["HOME"] = root.absolutePath
        env["XDG_CONFIG_HOME"] = config.absolutePath
        env["XDG_DATA_HOME"] = data.absolutePath
        env["XDG_STATE_HOME"] = state.absolutePath
        env["XDG_CACHE_HOME"] = cache.absolutePath
        env["TMPDIR"] = tmp.absolutePath
        // 无 TTY：关闭交互式 TUI 与颜色，避免 ANSI 码污染端口解析
        env["NO_COLOR"] = "1"
        env["TERM"] = "dumb"
        // 关闭自动更新：native ELF 是只读的，自更新必然失败并拖慢启动
        env["OPENCODE_DISABLE_AUTOUPDATE"] = "1"
        return env
    }
}

/**
 * `service.json`：应用与本地 OpenCode 服务器之间的私有契约（规格 §9.5）。
 *
 * 只放在应用私有目录，包含随机 Basic 密码。密码**不进日志**（规格 §7 安全要求）。
 */
data class ServiceConfig(
    val port: Int,
    val hostname: String,
    val password: String,
    val pid: Long?,
    val startedAt: Long,
) {
    val baseUrl: String get() = "http://$hostname:$port"

    /** OpenCode 的 Basic 认证用户名固定为 `opencode`。 */
    val authorization: String
        get() = "Basic " + Base64.getEncoder()
            .encodeToString("opencode:$password".toByteArray(Charsets.UTF_8))

    /** 打日志用的脱敏形式。 */
    val redacted: String get() = "ServiceConfig(port=$port, host=$hostname, pid=$pid)"

    fun toJson(): String = buildString {
        append('{')
        append("\"port\":").append(port).append(',')
        append("\"hostname\":").append(quote(hostname)).append(',')
        append("\"password\":").append(quote(password)).append(',')
        append("\"pid\":").append(pid ?: 0L).append(',')
        append("\"startedAt\":").append(startedAt)
        append('}')
    }

    companion object {
        const val HOSTNAME = "127.0.0.1"

        /** 生成随机密码：32 字节 url-safe base64，无需转义即可放进 JSON 与 Header。 */
        fun generatePassword(random: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        fun create(port: Int, pid: Long?, now: Long, random: SecureRandom = SecureRandom()): ServiceConfig =
            ServiceConfig(port, HOSTNAME, generatePassword(random), pid, now)

        /**
         * 解析失败返回 null。**不抛异常**：一个损坏的 service.json 只是"需要重建"，
         * 不该让应用起不来（规格 §9.5 幂等/断点续做）。
         */
        fun parse(text: String): ServiceConfig? {
            val json = try {
                kotlinx.serialization.json.Json.parseToJsonElement(text)
            } catch (e: Exception) {
                return null
            }
            val o = json as? kotlinx.serialization.json.JsonObject ?: return null
            fun s(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
            fun l(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()

            val port = l("port")?.toInt() ?: return null
            if (port !in 1..65535) return null
            val host = s("hostname") ?: HOSTNAME
            val password = s("password")?.takeIf { it.isNotBlank() } ?: return null
            val pid = l("pid")?.takeIf { it > 0 }
            return ServiceConfig(port, host, password, pid, l("startedAt") ?: 0L)
        }

        private fun quote(s: String): String = buildString {
            append('"')
            s.forEach { c ->
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
            append('"')
        }
    }
}
