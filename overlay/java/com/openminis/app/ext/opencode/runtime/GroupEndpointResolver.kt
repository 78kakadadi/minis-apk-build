package com.openminis.app.ext.opencode.runtime

import android.content.Context
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ext.group.GroupMemberDef
import com.openminis.app.ext.group.MemberEndpoint
import com.openminis.app.ext.group.MemberEndpointResolver
import com.openminis.app.ext.opencode.model.FreeModel

/**
 * 把「成员绑定」解析成一次真实请求的端点。
 *
 * 两条来源：
 *  - 免费通道（[GroupMemberDef.SOURCE_FREE]）：地址是本机桥接，key 随便填；
 *  - 自己存的 API（[GroupMemberDef.SOURCE_PROVIDER]）：用 ProviderRepository 里
 *    的实例地址与加密存储的 key。
 *
 * 只支持 OpenAI 兼容类型（openAI / OpenRouter / xAI）。Anthropic、Gemini 的
 * 请求体结构完全不同，硬套会得到一个看不懂的 400，不如直接判为不可用，
 * 在 UI 上提示换一个来源。
 */
class GroupEndpointResolver(
    context: Context,
    private val freeBaseUrl: () -> String?,
    private val freeModels: () -> List<FreeModel>,
) : MemberEndpointResolver {

    private val app = context.applicationContext
    private val repo by lazy { ProviderRepository(app) }

    /** 供群设置里的模型选择器使用：可绑定的「已存 API」列表。 */
    fun providerChoices(): List<ProviderChoice> =
        repo.instances
            .filter { it.isEnabled && isOpenAiCompatible(it.providerType) }
            .map { instance ->
                ProviderChoice(
                    instanceId = instance.id,
                    label = instance.label,
                    models = repo.entriesFor(instance.id).map { it.baseModel.id },
                )
            }

    override fun resolve(member: GroupMemberDef): MemberEndpoint? {
        if (member.source == GroupMemberDef.SOURCE_PROVIDER) {
            val instance = repo.instances.firstOrNull { it.id == member.providerInstanceId } ?: return null
            if (!isOpenAiCompatible(instance.providerType)) return null
            val base = instance.effectiveBaseURL ?: return null
            val key = repo.loadApiKey(instance.id).orEmpty()
            val model = member.modelId.ifBlank { repo.entriesFor(instance.id).firstOrNull()?.baseModel?.id.orEmpty() }
            if (model.isBlank()) return null
            return MemberEndpoint(base.trimEnd('/'), key, model)
        }

        val base = freeBaseUrl() ?: return null
        val model = member.modelId.ifBlank { freeModels().firstOrNull()?.id.orEmpty() }
        if (model.isBlank()) return null
        return MemberEndpoint(base.trimEnd('/'), "local", model)
    }

    private fun isOpenAiCompatible(type: ProviderType): Boolean = when (type) {
        ProviderType.openAI, ProviderType.openRouter, ProviderType.xAI -> true
        else -> false
    }
}

data class ProviderChoice(
    val instanceId: String,
    val label: String,
    val models: List<String>,
)
