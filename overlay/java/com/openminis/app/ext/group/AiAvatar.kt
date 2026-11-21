package com.openminis.app.ext.group

/**
 * 成员头像的默认取值：按「模型属于哪家」给出一个字母标识 + 品牌色。
 *
 * 为什么不是真实 logo：各家 logo 是注册商标，APK 里打包分发需要授权，
 * 而且离线环境下也不该为了一个头像去拉网络图片。这里用「厂商首字母 + 官方主色」
 * 的圆形徽标——一眼能区分是哪家的模型，也不涉及商标素材。
 */
data class AvatarSpec(val label: String, val color: Int)

object AiAvatars {

    /** 头像可选项（也是「换个头像」弹窗里的调色板）。 */
    val presets: List<AvatarSpec> = listOf(
        AvatarSpec("C", 0xFFD97757.toInt()),   // Claude
        AvatarSpec("GPT", 0xFF10A37F.toInt()), // OpenAI
        AvatarSpec("G", 0xFF4285F4.toInt()),   // Gemini
        AvatarSpec("DS", 0xFF4D6BFE.toInt()),  // DeepSeek
        AvatarSpec("Q", 0xFF615CED.toInt()),   // Qwen
        AvatarSpec("K", 0xFF1F1F1F.toInt()),   // Kimi
        AvatarSpec("x", 0xFF111111.toInt()),   // Grok
        AvatarSpec("L", 0xFF0866FF.toInt()),   // Llama
        AvatarSpec("M", 0xFFFF7000.toInt()),   // Mistral
        AvatarSpec("GLM", 0xFF3859FF.toInt()), // 智谱
        AvatarSpec("OC", 0xFF2F6F4E.toInt()),  // 内置 OpenCode
        AvatarSpec("AI", 0xFF8E8E93.toInt()),  // 兜底
    )

    /**
     * 按模型 id / provider id 推断头像。模型名里带厂商关键词就算命中，
     * 这样「claude-sonnet-4.5」「gpt-5」「deepseek-v3」都能落到对应徽标。
     */
    fun forModel(modelId: String, providerId: String = ""): AvatarSpec {
        val key = (modelId + " " + providerId).lowercase()
        return when {
            key.contains("claude") || key.contains("anthropic") -> presets[0]
            key.contains("gpt") || key.contains("openai") || key.contains("o1") ||
                key.contains("o3") || key.contains("o4") -> presets[1]
            key.contains("gemini") || key.contains("google") -> presets[2]
            key.contains("deepseek") -> presets[3]
            key.contains("qwen") || key.contains("通义") -> presets[4]
            key.contains("kimi") || key.contains("moonshot") -> presets[5]
            key.contains("grok") || key.contains("xai") -> presets[6]
            key.contains("llama") || key.contains("meta") -> presets[7]
            key.contains("mistral") -> presets[8]
            key.contains("glm") || key.contains("zhipu") || key.contains("chatglm") -> presets[9]
            key.contains("opencode") || key.contains("pickle") || key.contains("free") -> presets[10]
            else -> presets[11]
        }
    }

    /** 成员自定义过头像就用自定义的，否则按模型推断。 */
    fun resolve(member: GroupMemberDef, providerId: String = ""): AvatarSpec {
        if (member.avatarLabel.isNotBlank() && member.avatarColor != 0) {
            return AvatarSpec(member.avatarLabel, member.avatarColor)
        }
        val base = forModel(member.modelId, providerId)
        return if (member.avatarLabel.isNotBlank()) base.copy(label = member.avatarLabel)
        else if (member.avatarColor != 0) base.copy(color = member.avatarColor)
        else base
    }

    /** 两三个字母缩写，避免长名字撑破圆形。 */
    fun initialOf(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "AI"
        val first = trimmed.first()
        return if (first.code in 0x4E00..0x9FFF) first.toString()
        else trimmed.take(2).uppercase()
    }
}
