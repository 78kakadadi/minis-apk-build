package pro.openminis.app.ext.group

import java.io.File

/**
 * 附件的「给模型看」的形态。
 *
 * 三种处理方式，按能不能真的被模型用上排序：
 *
 *  1. **文本文件**（.md/.txt/.json/.csv/代码…）：直接内联前 [MAX_INLINE_CHARS] 字。
 *     绝大多数「帮我看看这个文件」的需求到这里就解决了，模型不用调工具。
 *  2. **图片**：自己存的 API 走多模态（`image_url` + base64），模型真的能看；
 *     免费通道的桥接只吃文本，所以把图片复制进工作区，让模型用 read 工具打开。
 *  3. **其它二进制**：只告诉模型「有这么个文件、在哪里、多大」。它要真需要，
 *     可以用 read_file / run_shell 自己处理（比如 PDF 用命令行工具转文本）。
 *
 * 附件复制进工作区的意义不只是「免费通道能读」：模型在一轮里多次引用同一个文件
 * 时，路径是稳定的，不会每次都重新塞一份内容进上下文。
 */
object GroupAttachments {

    /** 内联文本上限。再长就该让模型自己 read_file 分段读了。 */
    const val MAX_INLINE_CHARS = 8000

    /** 超过这个大小的文件不尝试当文本读（防止把二进制当文本灌进上下文）。 */
    const val MAX_INLINE_BYTES = 512 * 1024L

    /** 一次请求最多附几张图。太多图会让上下文和费用一起爆掉。 */
    const val MAX_IMAGES = 4

    /** 工作区里存附件的相对目录。 */
    const val WORKSPACE_DIR = "attachments"

    private val TEXT_EXTS = setOf(
        "txt", "md", "markdown", "json", "jsonl", "csv", "tsv", "log", "yml", "yaml",
        "xml", "html", "htm", "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx",
        "c", "h", "cpp", "hpp", "rs", "go", "sh", "bash", "zsh", "sql", "toml",
        "ini", "conf", "gradle", "properties", "srt", "vtt",
    )

    fun isTextual(mime: String, name: String): Boolean {
        if (mime.startsWith("text/")) return true
        if (mime in setOf("application/json", "application/xml", "application/x-yaml")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in TEXT_EXTS
    }

    fun humanSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    }

    /**
     * 把附件复制到工作区，返回相对工作区的路径。
     *
     * 同一个附件重复复制时文件名稳定（`attachments/<id>_<name>`），模型在一轮里
     * 看到的是同一个路径；已存在且大小一致就不再写。
     */
    fun copyToWorkspace(workspace: File, a: GroupAttachment): String? = runCatching {
        val src = File(a.path)
        if (!src.isFile) return@runCatching null
        val dir = File(workspace, WORKSPACE_DIR).apply { mkdirs() }
        val safe = a.name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(60).ifBlank { "file" }
        val target = File(dir, "${a.id}_$safe")
        if (!target.exists() || target.length() != src.length()) {
            src.copyTo(target, overwrite = true)
        }
        "$WORKSPACE_DIR/${target.name}"
    }.getOrNull()

    /**
     * 一条附件在提示词里的文字描述。
     *
     * [workspacePath] 为空表示复制失败（磁盘满等），这时至少把原文件名告诉模型，
     * 让它可以请用户重发，而不是假装什么都没收到。
     */
    fun describe(a: GroupAttachment, workspacePath: String?): String {
        val size = humanSize(a.size)
        val head = if (a.isImage) "【图片 ${a.name}" else "【附件 ${a.name}"
        val meta = if (size.isBlank()) "】" else " · $size】"
        val where = when {
            workspacePath != null && a.isImage -> "已保存到工作区 $workspacePath，可用 read_file 打开查看。"
            workspacePath != null -> "已保存到工作区 $workspacePath。"
            else -> "（没能存入工作区，如果内容关键请让用户重发）"
        }
        return "$head$meta$where"
    }

    /** 文本文件内容；读不了或不像文本时返回 null。 */
    fun inlineText(a: GroupAttachment): String? {
        if (a.isImage) return null
        if (!isTextual(a.mime, a.name)) return null
        val f = File(a.path)
        if (!f.isFile || f.length() > MAX_INLINE_BYTES) return null
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        if (text.contains('\u0000')) return null
        val body = if (text.length > MAX_INLINE_CHARS) {
            text.take(MAX_INLINE_CHARS) + "\n…（内容过长，已截断；需要更多请用 read_file 读原文）"
        } else {
            text
        }
        return "【附件 ${a.name} 的内容】\n$body"
    }

    /**
     * 一条用户消息的完整文本：正文 + 每个附件的说明 / 内联内容。
     *
     * `imageAsText` 为 true 时（免费通道 / 不支持视觉的端点），图片也退化成
     * 「已存到工作区」的文字说明，由模型自己用工具去看。
     */
    fun messageText(
        text: String,
        attachments: List<GroupAttachment>,
        workspace: File,
        imageAsText: Boolean,
    ): String {
        if (attachments.isEmpty()) return text
        val parts = ArrayList<String>(attachments.size + 1)
        if (text.isNotBlank()) parts.add(text)
        attachments.forEach { a ->
            val rel = copyToWorkspace(workspace, a)
            if (a.isImage && !imageAsText) {
                // 图片走多模态，文字里只留一个指代，免得模型以为图片丢了
                parts.add("【用户发来一张图片：${a.name}】")
                return@forEach
            }
            val inline = if (a.isImage) null else inlineText(a)
            parts.add(inline ?: describe(a, rel))
        }
        return parts.joinToString("\n\n")
    }
}
