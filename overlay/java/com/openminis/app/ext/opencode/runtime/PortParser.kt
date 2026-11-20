package com.openminis.app.ext.opencode.runtime

/**
 * 从 `opencode serve` 的 stdout 里解析实际监听端口（规格 §9.5「从 stdout 解析实际端口」）。
 *
 * 为什么要解析而不是固定端口：以 `--port 0` 启动时由内核分配，避免与用户设备上
 * 已占用的端口冲突（Termux 里 4096 常被占）。固定端口会在部分机型上直接
 * `EADDRINUSE`，而这个错误对用户毫无意义。
 *
 * 实际输出没有稳定契约（不同版本、TUI/headless 模式措辞不一），所以按
 * 「最具体到最宽泛」三级回落，并且**必须**过滤掉 ANSI 颜色码——OpenCode 的输出带
 * 绿色转义序列，`\u001B[32mhttp://...` 会让朴素正则的 `[:\\s]` 断言失配。
 */
object PortParser {

    private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    /** `http://127.0.0.1:4096` / `http://0.0.0.0:4096` / `https://localhost:8443` */
    private val URL_PORT = Regex("""https?://[^\s/:]+:(\d{2,5})""")

    /** 兜底：任意 `host:port` 形式，但要求前面有空白或行首，避免匹配到版本号 `1.15.0` */
    private val BARE_PORT = Regex("""(?:^|\s)(?:[0-9a-zA-Z._-]+):(\d{2,5})(?:\s|$|[/,;])""")

    /** `port 4096` / `port: 4096` / `--port=4096` */
    private val PORT_WORD = Regex("""port\s*[:=]?\s*(\d{2,5})""", RegexOption.IGNORE_CASE)

    /**
     * 解析一行输出中的端口。无匹配或端口越界返回 null。
     * 单行可能同时包含多个数字（版本号、耗时），所以顺序很重要。
     */
    fun parsePort(line: String): Int? {
        val clean = stripAnsi(line)
        for (regex in listOf(URL_PORT, BARE_PORT, PORT_WORD)) {
            val m = regex.find(clean) ?: continue
            val port = m.groupValues[1].toIntOrNull() ?: continue
            if (port in 1..65535) return port
        }
        return null
    }

    /** 在一个输出块里找第一个可解析端口（stdout 可能分多次到达）。 */
    fun firstPortIn(text: String): Int? =
        text.lineSequence().asSequence().mapNotNull { parsePort(it) }.firstOrNull()

    fun stripAnsi(text: String): String = ANSI.replace(text, "")

    /** 健康检查通过前的就绪标志词，用于日志判定（非必须，仅用于可读错误）。 */
    fun looksReady(line: String): Boolean {
        val t = stripAnsi(line).lowercase()
        return "listening" in t || "server started" in t || "http://" in t
    }
}
