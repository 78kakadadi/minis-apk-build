package pro.openminis.app.ext.group

import java.io.File

/**
 * 一个可以被群成员调用的技能。
 *
 * 只有四样东西进引擎：id、名字、一句话描述、SKILL.md 正文。上游的 Skills
 * 本来存在 `skills.db` + `/var/minis/skills/<id>/SKILL.md`，这里做一份与 Android
 * 无关的投影，让 [GroupChatEngine] 能在 JVM 上被单测。
 */
data class GroupSkill(
    val id: String,
    val name: String,
    val description: String = "",
    val body: String = "",
)

/** 技能来源。UI 侧用上游 [SkillRepository] 适配，测试里直接给一个固定列表。 */
fun interface GroupSkillSource {
    fun skillsFor(member: GroupMemberDef): List<GroupSkill>
}

/**
 * 技能在群聊里的接入方式。
 *
 * ## 为什么不是「把 SKILL.md 全文塞进提示词」
 *
 * 一个群里有三个成员，每人再带十个技能，光技能正文就能把上下文顶爆，而且
 * 大部分技能这一轮根本用不上。所以采用和上游普通对话一样的做法：
 *
 *  1. 提示词里只放「名字 + 描述 + 路径」清单（最多 [MAX_IN_PROMPT] 个）；
 *  2. SKILL.md 写进成员共享的工作区 `skills/<id>/SKILL.md`；
 *  3. 模型真的要按某个技能做事时，自己用 `read_file` 去读正文。
 *
 * 这样免费通道（OpenCode）和自己存的 API 走的是同一条路：它们都能读工作区里的
 * 文件，不需要各自实现一套「技能工具」。
 */
object GroupSkills {

    /** 清单里最多列几个。超过就按「最近更新」之外的稳定顺序截断，避免提示词无界增长。 */
    const val MAX_IN_PROMPT = 20

    /** 单条描述上限。跟上游 SkillRepository 的 200 字保持一致。 */
    const val MAX_DESC_CHARS = 200

    /** 写进工作区的正文上限：再长的技能文件也没法在手机上一次读完。 */
    const val MAX_BODY_CHARS = 200_000

    /** 技能文件在工作区里的相对目录。 */
    const val DIR = "skills"

    /**
     * 这个成员实际能用哪些技能。
     *
     * `member.skills == null` 表示没设置过 = 已启用的技能全部可用（和普通对话一致）；
     * 显式空列表表示一个都不用。
     */
    fun select(available: List<GroupSkill>, member: GroupMemberDef): List<GroupSkill> {
        val allow = member.skills?.toSet() ?: return available
        return available.filter { it.id in allow }
    }

    /** 成员卡片上的一行摘要。 */
    fun summary(available: List<GroupSkill>, member: GroupMemberDef): String {
        val picked = select(available, member)
        return when {
            available.isEmpty() -> "没有已启用的技能"
            picked.isEmpty() -> "未启用"
            picked.size == available.size -> "全部（${picked.size}）"
            else -> picked.joinToString("、") { it.name }.take(40)
        }
    }

    /**
     * 提示词里的技能清单。没有可用技能时返回空串——调用方据此决定要不要加
     * 「用 read_file 读技能」那句话。
     */
    fun promptFragment(skills: List<GroupSkill>): String {
        if (skills.isEmpty()) return ""
        val shown = skills.take(MAX_IN_PROMPT)
        val xml = buildString {
            append("<available_skills>\n")
            shown.forEach { s ->
                val desc = if (s.description.length > MAX_DESC_CHARS) {
                    s.description.take(MAX_DESC_CHARS) + "…"
                } else {
                    s.description
                }
                append("  <skill>\n")
                append("    <name>").append(escapeXml(s.name)).append("</name>\n")
                append("    <description>").append(escapeXml(desc)).append("</description>\n")
                append("    <path>").append(pathOf(s.id)).append("</path>\n")
                append("  </skill>\n")
            }
            append("</available_skills>")
        }
        return buildString {
            append("\n\n你可以使用下面这些技能（可复用的操作说明）：\n")
            append(xml)
            append("\n")
            append("当某个技能和当前任务相关时，先用 read_file 读取它的 path 指向的 SKILL.md，")
            append("再严格按里面的步骤执行；不相关就别读，也不要提「我有什么技能」。")
            if (skills.size > shown.size) {
                append("\n（还有 ").append(skills.size - shown.size).append(" 个技能没列出，")
                append("可以用 list_files 在 ").append(DIR).append("/ 下查看。）")
            }
        }
    }

    fun pathOf(skillId: String): String = "$DIR/$skillId/SKILL.md"

    /**
     * 把选中的技能正文镜像到工作区，返回实际写到磁盘的技能 id 集合。
     *
     * 每次都写：SKILL.md 可能在上游被更新，而按内容比对再决定写不写反而要
     * 多读一遍文件。技能文件都是几十 KB，写盘代价可以忽略。写失败（磁盘满、
     * 权限）时静默跳过——技能不能用不该把整轮发言打断。
     */
    fun mirrorToWorkspace(workspace: File, skills: List<GroupSkill>): Set<String> {
        if (skills.isEmpty()) return emptySet()
        val ok = HashSet<String>(skills.size)
        skills.forEach { s ->
            runCatching {
                val dir = File(File(workspace, DIR), s.id)
                dir.mkdirs()
                val body = s.body.take(MAX_BODY_CHARS).ifBlank {
                    "# ${s.name}\n\n${s.description}\n"
                }
                val target = File(dir, "SKILL.md")
                if (!target.exists() || target.length() != body.toByteArray().size.toLong()) {
                    target.writeText(body)
                }
                ok.add(s.id)
            }
        }
        return ok
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
