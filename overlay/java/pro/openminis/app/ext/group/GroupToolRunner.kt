package pro.openminis.app.ext.group

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 「自己存的 API」成员的本地工具执行器。
 *
 * ## 为什么免费通道不需要它
 *
 * 免费通道（OpenCode）在设备上自己执行工具，桥接只负责把过程渲染出来；而
 * OpenAI 兼容 API 只会返回 `tool_calls`，**没有任何执行能力**——谁来跑、跑在
 * 哪个目录、输出怎么回填，全得由客户端做。这个类就是那个客户端。
 *
 * ## 工作区边界
 *
 * 所有路径都被钉死在 [workspace] 里（免费的 OpenCode 也用同一个目录）。模型
 * 给出的 `../` 或绝对路径一旦越界就直接报错，而不是「尽力而为地执行」：
 * 群聊里的成员是用户配的第三方模型，不该因为它要求就让它读应用私有数据。
 */
class GroupToolRunner(
    private val workspace: File,
    private val http: OkHttpClient,
) {

    data class Result(val output: String, val isError: Boolean = false)

    /** OpenAI 的函数名 → [GroupTools] 的工具 id。 */
    private fun actionOf(name: String): String? = when (name) {
        "read_file" -> GroupTools.READ
        "write_file" -> GroupTools.EDIT
        "list_files" -> GroupTools.GLOB
        "search_files" -> GroupTools.GREP
        "run_shell" -> GroupTools.SHELL
        "fetch_url" -> GroupTools.WEBFETCH
        else -> null
    }

    /**
     * 执行一次工具调用。
     *
     * @param allowed 成员的允许集合：模型就算硬编出一个没授权的函数名也会被拒，
     *   不能只依赖「请求里没声明」——模型完全可能凭记忆调用。
     */
    suspend fun execute(name: String, argsJson: String, allowed: Set<String>): Result {
        val action = actionOf(name)
            ?: return Result("未知工具：$name", isError = true)
        if (action !in allowed) {
            return Result("工具「${GroupTools.labelOf(action)}」未授权给这个成员", isError = true)
        }
        val args = runCatching { JSONObject(argsJson.ifBlank { "{}" }) }.getOrElse {
            return Result("工具参数不是合法 JSON：${argsJson.take(200)}", isError = true)
        }

        return try {
            when (name) {
                "read_file" -> readFile(args)
                "write_file" -> writeFile(args)
                "list_files" -> listFiles(args)
                "search_files" -> searchFiles(args)
                "run_shell" -> runShell(args)
                "fetch_url" -> fetchUrl(args)
                else -> Result("未知工具：$name", isError = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result(e.message ?: e.javaClass.simpleName, isError = true)
        }
    }

    // ---- 文件 -------------------------------------------------------------

    private fun readFile(args: JSONObject): Result {
        val file = resolve(args.optString("path"))
        if (!file.exists()) return Result("文件不存在：${rel(file)}", isError = true)
        if (file.isDirectory) return Result("这是一个目录，不是文件：${rel(file)}", isError = true)
        if (file.length() > MAX_READ_BYTES) {
            return Result("文件太大（${file.length()} 字节），超过 ${MAX_READ_BYTES} 上限", isError = true)
        }
        val text = file.readText()
        val lines = text.lines()
        val offset = args.optInt("offset", 1).coerceAtLeast(1)
        val limit = args.optInt("limit", 400).coerceIn(1, 2000)
        if (offset > lines.size) return Result("offset=$offset 超出文件行数 ${lines.size}", isError = true)
        val slice = lines.drop(offset - 1).take(limit)
        return Result(slice.joinToString("\n"))
    }

    private fun writeFile(args: JSONObject): Result {
        val file = resolve(args.optString("path"))
        val content = args.optString("content")
        if (content.length > MAX_WRITE_CHARS) {
            return Result("内容太长（${content.length} 字），超过 $MAX_WRITE_CHARS 上限", isError = true)
        }
        file.parentFile?.mkdirs()
        file.writeText(content)
        return Result("已写入 ${rel(file)}（${content.length} 字）")
    }

    private fun listFiles(args: JSONObject): Result {
        val pattern = args.optString("pattern").ifBlank { "*" }
        val regex = globToRegex(pattern)
        val root = workspace.canonicalFile
        val hits = ArrayList<String>()
        // 用 for + break 而不是 forEach + return@forEach：后者只跳过当前元素，
        // 工作区里真有几千个文件时会白遍历一遍。
        for (f in root.walkTopDown().onEnter { it.name != ".git" && it.name != "node_modules" }) {
            if (f != root && regex.matches(rel(f))) {
                hits.add(rel(f) + if (f.isDirectory) "/" else "")
            }
            if (hits.size >= MAX_LIST) break
        }
        if (hits.isEmpty()) return Result("没有匹配「$pattern」的文件")
        return Result(hits.take(MAX_LIST).joinToString("\n"))
    }

    private fun searchFiles(args: JSONObject): Result {
        val pattern = args.optString("pattern")
        if (pattern.isBlank()) return Result("缺少 pattern", isError = true)
        val regex = runCatching { Regex(pattern) }.getOrElse { Regex(Regex.escape(pattern)) }
        val start = if (args.optString("path").isBlank()) workspace else resolve(args.optString("path"))
        if (!start.exists()) return Result("路径不存在：${rel(start)}", isError = true)
        val hits = ArrayList<String>()
        val files = if (start.isFile) sequenceOf(start) else start.walkTopDown()
            .onEnter { it.name != ".git" && it.name != "node_modules" }
            .filter { it.isFile }
        for (f in files) {
            if (f.length() > MAX_SEARCH_BYTES) continue
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            if (text.contains('\u0000')) continue // 二进制
            text.lineSequence().forEachIndexed { idx, line ->
                if (regex.containsMatchIn(line)) {
                    hits.add("${rel(f)}:${idx + 1}: ${line.trim().take(200)}")
                }
            }
            if (hits.size >= MAX_SEARCH_HITS) break
        }
        if (hits.isEmpty()) return Result("没有找到「$pattern」")
        return Result(hits.take(MAX_SEARCH_HITS).joinToString("\n"))
    }

    // ---- 命令 -------------------------------------------------------------

    private suspend fun runShell(args: JSONObject): Result {
        val command = args.optString("command")
        if (command.isBlank()) return Result("缺少 command", isError = true)

        val shell = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
        val process = ProcessBuilder(shell, "-c", command)
            .directory(workspace)
            .redirectErrorStream(true)
            .start()

        val out = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(out) {
                            // 输出没有上限的话，一条 `yes` 就能把内存吃光
                            if (out.length < MAX_SHELL_CHARS) out.append(line).append('\n')
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        try {
            val deadline = System.currentTimeMillis() + SHELL_TIMEOUT_MS
            while (process.isAlive) {
                if (!currentCoroutineContext().isActive) {
                    process.destroyForcibly()
                    throw CancellationException("工具执行被取消")
                }
                if (System.currentTimeMillis() > deadline) {
                    process.destroyForcibly()
                    return Result("命令超时（${SHELL_TIMEOUT_MS / 1000} 秒）已终止", isError = true)
                }
                delay(40)
            }
            reader.join(1000)
        } finally {
            runCatching { process.destroyForcibly() }
        }

        val exit = runCatching { process.exitValue() }.getOrDefault(-1)
        val text = synchronized(out) { out.toString() }.trimEnd()
        val body = if (text.isBlank()) "（没有输出）" else text
        return if (exit == 0) Result(body) else Result("退出码 $exit\n$body", isError = true)
    }

    // ---- 网络 -------------------------------------------------------------

    private suspend fun fetchUrl(args: JSONObject): Result = withContext(Dispatchers.IO) {
        val raw = args.optString("url")
        val url = runCatching { raw.toHttpUrlOrNull() }.getOrNull()
            ?: return@withContext Result("不是合法网址：$raw", isError = true)
        if (url.scheme != "http" && url.scheme != "https") {
            return@withContext Result("只支持 http/https", isError = true)
        }
        val req = Request.Builder().url(url).header("User-Agent", "MinisPro/1.0").build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result("HTTP ${resp.code}", isError = true)
                }
                val body = resp.body?.string().orEmpty()
                val text = if (body.contains("<html", ignoreCase = true)) htmlToText(body) else body
                val clipped = text.take(MAX_FETCH_CHARS)
                Result(clipped.ifBlank { "（页面没有可读正文）" })
            }
        } catch (e: Exception) {
            Result("抓取失败：${e.message}", isError = true)
        }
    }

    /** 极简 HTML 转文本：去掉脚本样式与标签，够模型读正文即可。 */
    private fun htmlToText(html: String): String = html
        .replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(p|div|li|h[1-6]|tr)>"), "\n")
        .replace(Regex("(?s)<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace(Regex("[ \\t]{2,}"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    // ---- 路径 -------------------------------------------------------------

    private fun resolve(path: String): File {
        if (path.isBlank()) throw IllegalArgumentException("缺少 path")
        val root = workspace.canonicalFile
        val raw = File(path)
        val target = if (raw.isAbsolute) raw else File(root, path)
        val canon = target.canonicalFile
        val rootPath = root.path
        if (canon != root && !canon.path.startsWith("$rootPath${File.separator}")) {
            throw IllegalArgumentException("路径超出工作区：$path")
        }
        return canon
    }

    private fun rel(file: File): String = runCatching {
        file.canonicalFile.toRelativeString(workspace.canonicalFile)
    }.getOrDefault(file.name).ifBlank { "." }

    companion object {
        private const val MAX_READ_BYTES = 1_000_000L
        private const val MAX_WRITE_CHARS = 200_000
        private const val MAX_SEARCH_BYTES = 1_000_000L
        private const val MAX_SEARCH_HITS = 100
        private const val MAX_LIST = 300
        private const val MAX_SHELL_CHARS = 8000
        private const val MAX_FETCH_CHARS = 6000
        private const val SHELL_TIMEOUT_MS = 30_000L

        /** 简易 glob：`**` 跨目录，`*` 不跨目录，`?` 单字符。 */
        fun globToRegex(glob: String): Regex {
            val sb = StringBuilder("^")
            var i = 0
            while (i < glob.length) {
                val c = glob[i]
                when {
                    // `**/` 要能匹配零层目录：`**/*.md` 也应命中根下的 a.md
                    c == '*' && i + 2 < glob.length && glob[i + 1] == '*' && glob[i + 2] == '/' -> {
                        sb.append("(?:.*/)?")
                        i += 2
                    }
                    c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                        sb.append(".*")
                        i++
                    }
                    c == '*' -> sb.append("[^/]*")
                    c == '?' -> sb.append("[^/]")
                    c in ".+()[]{}\\^$|" -> sb.append('\\').append(c)
                    else -> sb.append(c)
                }
                i++
            }
            sb.append("$")
            return Regex(sb.toString(), RegexOption.IGNORE_CASE)
        }
    }
}
