package com.openminis.app.ext.opencode.policy

/**
 * 免费池要求的四个 MCP 工具名 → Minis 实际工具的映射（规格 §9.6）。
 *
 * ## 为什么这一层不可省
 *
 * 上游硬门禁要求 MCP 客户端**同时声明** `bash`、`glob`、`grep`、`read` 四个小写工具名。
 * 而 Minis 原生的工具名是 `shell_execute`、`file_read`、`file_write`、`file_edit`
 * （`tools/AgentTools.kt:119`、`tools/FileReadTool.kt:11`）——**四个都不匹配**。
 *
 * 直接把 Minis 的工具表原样挂上去，第一次请求就会 403，而且报错写着
 * "free tier can only be used from within OpenCode"，看起来像没装 OpenCode，
 * 实际只是名字不对。这是整条通道最容易踩、最难自查的坑。
 *
 * 因此这里做一层**别名**：对外声明四个合规名字，对内路由到 Minis 的实现。
 * `glob` / `grep` 没有对应的原生工具，通过沙箱里的 `shell_execute` 组合
 * `find` / `grep` 命令实现——沙箱本来就是 Alpine，这两个命令一定存在。
 */
enum class FreeTierTool(
    val mcpName: String,
    val minisTool: String,
    val description: String,
) {
    BASH(
        mcpName = "bash",
        minisTool = "shell_execute",
        description = "Execute a shell command in the Linux sandbox and return stdout/stderr.",
    ),
    GLOB(
        mcpName = "glob",
        minisTool = "shell_execute",
        description = "Find files by glob pattern (e.g. **/*.kt) under an optional directory.",
    ),
    GREP(
        mcpName = "grep",
        minisTool = "shell_execute",
        description = "Search file contents with a regular expression, optionally filtered by include pattern.",
    ),
    READ(
        mcpName = "read",
        minisTool = "file_read",
        description = "Read a text file from the sandbox, optionally a line range.",
    ),
    ;

    companion object {
        val ALL: List<FreeTierTool> = entries

        fun byMcpName(name: String): FreeTierTool? = entries.firstOrNull { it.mcpName == name }
    }
}

/**
 * 把 `glob` / `grep` 的入参翻译成沙箱命令。
 *
 * 单独抽出来是因为**它必须可测**：拼错的引号会导致命令注入或 `find: paths must
 * precede expression`，而这两种错误在真机上只表现为"工具返回空"，极难定位。
 */
object ShellCommandBuilder {

    /**
     * `glob(pattern, path?)` → `find <path> -name '<name>'`。
     * pattern 里带目录分隔符时必须用 `-path`，否则 `-name` 只匹配文件名部分。
     */
    fun glob(pattern: String, path: String? = null, limit: Int = 200): String {
        val root = sanitizePath(path ?: ".")
        val safePattern = shellQuote(pattern)
        val matcher = if (pattern.contains('/')) {
            "-path $safePattern"
        } else {
            "-name $safePattern"
        }
        // 2>/dev/null：权限拒绝的目录不该污染结果
        return "find $root $matcher -type f 2>/dev/null | head -n ${limit.coerceIn(1, 2000)}"
    }

    /**
     * `grep(pattern, path?, include?)` → `grep -rn`。
     * `include` 是文件名通配（如 `*.kt`），转成 `--include`。
     */
    fun grep(pattern: String, path: String? = null, include: String? = null, limit: Int = 200): String {
        val root = sanitizePath(path ?: ".")
        val sb = StringBuilder("grep -rn")
        if (!include.isNullOrBlank()) {
            sb.append(" --include=").append(shellQuote(include))
        }
        sb.append(' ').append(shellQuote(pattern))
        sb.append(' ').append(root)
        sb.append(" 2>/dev/null | head -n ").append(limit.coerceIn(1, 2000))
        return sb.toString()
    }

    /** `read(filePath, offset?, limit?)` → `sed -n 'start,endp'`（file_read 不支持行范围时用）。 */
    fun readLines(filePath: String, offset: Int?, limit: Int?): String? {
        if (offset == null && limit == null) return null
        val start = (offset ?: 1).coerceAtLeast(1)
        val count = (limit ?: 200).coerceIn(1, 5000)
        return "sed -n '${start},${start + count - 1}p' ${shellQuote(filePath)} 2>/dev/null"
    }

    /**
     * 单引号包裹并把内部单引号转义为 `'\''`。
     *
     * 这是 POSIX shell 里唯一安全的通用引法：双引号会展开 `$`、反引号与 `\`，
     * 用户搜一个 `$HOME` 就变成变量替换了。
     */
    fun shellQuote(raw: String): String {
        if (raw.isEmpty()) return "''"
        return "'" + raw.replace("'", "'\\''") + "'"
    }

    /**
     * 路径白名单：只允许沙箱内相对路径与 `/root`、`/home`、`/tmp` 等常见根。
     * 拒绝含 `..` 的路径，避免越出会话工作区去翻别的会话（规格 §9.6 的隔离要求）。
     */
    fun sanitizePath(path: String): String {
        val trimmed = path.trim()
        // 返回值一律是「可直接拼进命令的片段」，所以连 "." 也带引号，保持一致
        if (trimmed.isEmpty()) return "'.'"
        if (trimmed.split('/').any { it == ".." }) return "'.'"
        return shellQuote(trimmed)
    }
}
