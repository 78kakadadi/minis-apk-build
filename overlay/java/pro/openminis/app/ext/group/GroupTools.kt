package pro.openminis.app.ext.group

import org.json.JSONArray
import org.json.JSONObject

/**
 * 群聊成员可用的工具目录。
 *
 * ## 为什么要有一份「目录」
 *
 * 工具白名单要在三个地方同时成立，任何一处漏掉都会变成「看起来限制了，其实没有」：
 *
 *  1. **免费通道（OpenCode）**：会话级 `permissions` 规则，白名单外一律 deny；
 *  2. **自己存的 API**：请求体里的 `tools` 数组只带白名单里的工具；
 *  3. **界面**：成员卡片上勾选哪几个，就是运行时真正允许的那几个。
 *
 * 所以 id 只有这一份定义，UI / 引擎 / 桥接都从这里取，避免三处各写一套字符串。
 *
 * ## 为什么有 `freeOnly`
 *
 * 免费通道由 OpenCode 在设备上执行工具，它自带联网搜索；自己存的 API 走的是
 * 我们本机实现的工具执行器（[GroupToolRunner]），没有可靠的搜索后端，
 * 所以「联网搜索」只对免费成员开放，界面上也会标出来。
 */
object GroupTools {

    const val READ = "read"
    const val EDIT = "edit"
    const val GLOB = "glob"
    const val GREP = "grep"
    const val SHELL = "shell"
    const val WEBFETCH = "webfetch"
    const val WEBSEARCH = "websearch"

    data class Spec(
        val id: String,
        val label: String,
        val hint: String,
        /** 会改文件或执行命令，界面上用警示色标出。 */
        val danger: Boolean = false,
        /** 只有免费通道（OpenCode）能执行。 */
        val freeOnly: Boolean = false,
    )

    val SPECS: List<Spec> = listOf(
        Spec(READ, "读文件", "读取工作区里的文本文件"),
        Spec(EDIT, "写文件", "新建或修改文件", danger = true),
        Spec(GLOB, "找文件", "按通配符列出文件"),
        Spec(GREP, "搜内容", "在文件里搜索关键词"),
        Spec(SHELL, "执行命令", "在本机工作区运行 shell 命令", danger = true),
        Spec(WEBFETCH, "打开网页", "抓取一个网址的正文"),
        Spec(WEBSEARCH, "联网搜索", "搜索互联网（仅免费通道）", freeOnly = true),
    )

    val ALL_IDS: Set<String> = SPECS.map { it.id }.toSet()

    /**
     * 实际允许的工具集合。
     *
     * `tools == null` 表示「没设置过」，等价于全部——这样旧群成员升级后不会
     * 突然失去工具能力；显式清空（emptyList）才代表禁用全部。
     */
    fun allowed(member: GroupMemberDef): Set<String> = member.tools?.toSet() ?: ALL_IDS

    fun labelOf(id: String): String = SPECS.firstOrNull { it.id == id }?.label ?: id

    /** 这条成员配置是否「全部允许」（UI 上显示为「全部」而不是一串名字）。 */
    fun isAll(member: GroupMemberDef): Boolean = member.tools == null

    /** 人话描述，用在成员卡片的摘要行。 */
    fun summary(member: GroupMemberDef): String {
        val set = allowed(member)
        if (set.isEmpty()) return "未启用"
        if (set.size == ALL_IDS.size) return "全部"
        return SPECS.filter { it.id in set }.joinToString("、") { it.label }
    }

    /**
     * OpenCode 的会话级权限规则。
     *
     * ## 规则顺序很重要
     *
     * OpenCode 是「最后一条匹配的规则生效」。所以先 `*` 全禁，再逐项 allow；
     * 这样将来 OpenCode 新增工具也会默认被挡住，不会因为白名单没跟上而漏权。
     *
     * 末尾几条是**永远禁**的能力：群聊没有交互式审批界面，`ask` 会直接把这一轮
     * 卡死；`subagent` 能绕开父会话的白名单去调工具，也必须关掉。
     */
    fun permissionRules(allowed: Set<String>): JSONArray {
        val arr = JSONArray()
        arr.put(rule("*", "*", "deny"))
        allowed.forEach { id ->
            if (id in ALL_IDS) arr.put(rule(id, "*", "allow"))
        }
        listOf(
            "subagent", "skill", "question",
            "external_directory", "experimental_image_generate", "execute",
        ).forEach { arr.put(rule(it, "*", "deny")) }
        return arr
    }

    private fun rule(action: String, resource: String, effect: String): JSONObject =
        JSONObject().put("action", action).put("resource", resource).put("effect", effect)

    /**
     * 给「自己存的 API」用的 OpenAI tools 定义。
     *
     * 只包含本机真的能执行的工具。声明了却执行不了，比不声明更糟：模型会反复
     * 调一个永远失败的函数，把一轮对话耗在重试上。
     */
    fun openAiTools(allowed: Set<String>): JSONArray {
        val arr = JSONArray()
        fun spec(id: String): Spec? = SPECS.firstOrNull { it.id == id }
        fun add(id: String, name: String, description: String, properties: JSONObject, required: JSONArray) {
            if (id !in allowed) return
            arr.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", name)
                            .put("description", description)
                            .put("parameters", JSONObject()
                                .put("type", "object")
                                .put("properties", properties)
                                .put("required", required)),
                    ),
            )
        }

        add(
            READ, "read_file", spec(READ)?.hint.orEmpty(),
            JSONObject()
                .put("path", str("文件路径，相对工作区"))
                .put("offset", num("从第几行开始，1 起"))
                .put("limit", num("最多读多少行，默认 400")),
            JSONArray().put("path"),
        )
        add(
            EDIT, "write_file", spec(EDIT)?.hint.orEmpty(),
            JSONObject()
                .put("path", str("文件路径，相对工作区"))
                .put("content", str("完整文件内容（覆盖写入）")),
            JSONArray().put("path").put("content"),
        )
        add(
            GLOB, "list_files", spec(GLOB)?.hint.orEmpty(),
            JSONObject().put("pattern", str("通配符，如 **/*.md，默认 *")),
            JSONArray(),
        )
        add(
            GREP, "search_files", spec(GREP)?.hint.orEmpty(),
            JSONObject()
                .put("pattern", str("要搜索的文本或正则"))
                .put("path", str("从哪个目录开始，默认工作区根")),
            JSONArray().put("pattern"),
        )
        add(
            SHELL, "run_shell", spec(SHELL)?.hint.orEmpty(),
            JSONObject().put("command", str("要执行的 shell 命令")),
            JSONArray().put("command"),
        )
        add(
            WEBFETCH, "fetch_url", spec(WEBFETCH)?.hint.orEmpty(),
            JSONObject().put("url", str("要抓取的网址，必须 http/https")),
            JSONArray().put("url"),
        )
        return arr
    }

    private fun str(desc: String): JSONObject = JSONObject().put("type", "string").put("description", desc)

    private fun num(desc: String): JSONObject = JSONObject().put("type", "integer").put("description", desc)

    // ---- 展示 -------------------------------------------------------------

    /** 工具调用块的最大展示行数与字数，和历史里的截断保持一致。 */
    const val MAX_TOOL_LINES = 24
    const val MAX_TOOL_CHARS = 1500
    private const val MAX_ARG_CHARS = 120

    /**
     * 把一次工具调用渲染成 markdown 正文块。
     *
     * 群聊里工具是**本机真的执行完**的，只把「做了什么、结果是什么」写进回复正文，
     * 给用户看，同时也会进上下文（下一轮其他成员能看到上一轮的工具结果）。
     */
    fun renderToolBlock(name: String, args: JSONObject?, output: String, isError: Boolean): String {
        val head = buildString {
            append("🛠 **").append(name.ifBlank { "tool" }).append("**")
            val arg = summarizeArgs(args)
            if (arg.isNotBlank()) append(" · `").append(arg).append("`")
            if (isError) append(" · 失败")
        }
        val out = output.trimEnd()
        if (out.isBlank()) return "\n\n$head\n\n"

        val clipped = out.lines().take(MAX_TOOL_LINES).joinToString("\n").take(MAX_TOOL_CHARS)
        val truncated = clipped.length < out.length
        return buildString {
            append("\n\n").append(head).append("\n\n")
            append("```text\n").append(clipped).append("\n```")
            if (truncated) append("\n（输出较长，已截断）")
            append("\n\n")
        }
    }

    /** 从入参里挑一个最能说明「在干什么」的字段，压成一行。 */
    fun summarizeArgs(args: JSONObject?): String {
        if (args == null) return ""
        val raw = listOf("command", "path", "filePath", "file_path", "pattern", "url", "query")
            .firstNotNullOfOrNull { k -> args.optString(k).takeIf { it.isNotBlank() } }
            ?: args.toString()
        return raw.replace('\n', ' ').replace('`', '\'').trim().take(MAX_ARG_CHARS)
    }
}
