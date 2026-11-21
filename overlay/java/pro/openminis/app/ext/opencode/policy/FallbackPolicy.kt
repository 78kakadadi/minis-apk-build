package pro.openminis.app.ext.opencode.policy

/**
 * 通道失败分类。刻意把「该不该换通道」和「该不该等」分开，
 * 因为这两件事在规格里是两套规则：
 *  - 冷却时长看 HTTP 语义（§7：429→60s，401→5min，402→30min）；
 *  - 是否换 Key / 换模型看错误性质（§7「网络/参数/审核类错误不换 Key」）。
 */
sealed interface ChannelFailure {
    /** 模型级失败：模型下线、被限流到不可用。只影响绑定该模型的成员（§9.10）。 */
    data class ModelUnavailable(val modelId: String, val reason: String = "") : ChannelFailure

    /** 服务器级失败：健康检查不过。所有免费成员同时受影响（§9.10）。 */
    data class ServerUnhealthy(val detail: String = "") : ChannelFailure

    /** HTTP 状态语义。 */
    data class HttpStatus(val code: Int, val retryAfterSeconds: Long? = null) : ChannelFailure

    /** 浏览器自动化通道崩溃（web-anon，§9.11）。 */
    data object BrowserCrashed : ChannelFailure

    /** 协议/参数/审核类：这类错误重试只会重复失败，必须原样上抛。 */
    data class NonRetryable(val detail: String) : ChannelFailure
}

sealed interface FallbackDecision {
    /** 还有备选免费模型，切换后立即重试一次（规格 §9.10 回退链第 ① 步）。 */
    data class RetryWithModel(val modelId: String) : FallbackDecision

    /** 进入冷却，到期探活（不立刻重试）。 */
    data class Cooldown(val seconds: Long, val reason: String) : FallbackDecision

    /** 回退到该成员绑定的付费 Key 组（第 ② 步，需群聊/成员允许）。 */
    data object SwitchToPaid : FallbackDecision

    /** 本轮标记 failed，气泡内显示原因（第 ③ 步）。 */
    data class Fail(val reason: String) : FallbackDecision

    /** 不换 Key、不重试、不冷却，直接把错误交给上层（参数/审核类）。 */
    data class Propagate(val reason: String) : FallbackDecision
}

/**
 * 免费通道的失败回退策略（规格 §7 / §9.9 / §9.10 / §9.11）。
 *
 * 三条不能违反的原则：
 *  1. **失败不换 Key、不重试直连**。免费通道没有 Key 可换，任何"重试直连 Zen"的
 *     实现都会撞 403，且违反 §9.1 的硬约束。
 *  2. **回退对每个成员独立执行**。一个免费成员失败不连坐其他成员（§9.10）。
 *  3. **定向发言默认不静默转交**（§5.3）：那是群聊编排层的策略，不在这里做。
 */
class FallbackPolicy(
    /** 群聊是否允许"免费失败后临时切到付费 Key 组"。默认允许，可被群聊配置关闭。 */
    private val allowPaidTakeover: Boolean = true,
) {

    /** 冷却秒数。返回 0 表示不冷却（错误性质决定，而非状态码）。 */
    fun cooldownSeconds(failure: ChannelFailure): Long = when (failure) {
        is ChannelFailure.HttpStatus -> when (failure.code) {
            429 -> failure.retryAfterSeconds?.coerceIn(1, 300) ?: 60
            401 -> 300
            402 -> 1800
            403 -> 60      // 免费池 403：通常是工具名不合规或通道被改，冷却后重探
            408, 504 -> 60
            in 500..599 -> 60
            else -> 0      // 400/422 等参数错误：重试无意义
        }
        is ChannelFailure.ServerUnhealthy -> 60
        is ChannelFailure.BrowserCrashed -> 120
        is ChannelFailure.ModelUnavailable -> 0   // 模型级不整体冷却，只剔除模型
        is ChannelFailure.NonRetryable -> 0
    }

    /**
     * 决定下一步。
     *
     * [alternativeFreeModels] 是该成员可用的其他免费模型（已排除失效项）。
     * 只取第一个：切换一次就够，连续切换会把免费配额耗在重试上。
     */
    fun decide(
        failure: ChannelFailure,
        alternativeFreeModels: List<String> = emptyList(),
    ): FallbackDecision = when (failure) {
        is ChannelFailure.NonRetryable ->
            FallbackDecision.Propagate(failure.detail)

        is ChannelFailure.ModelUnavailable -> {
            val alt = alternativeFreeModels.firstOrNull { it != failure.modelId }
            when {
                alt != null -> FallbackDecision.RetryWithModel(alt)
                allowPaidTakeover -> FallbackDecision.SwitchToPaid
                else -> FallbackDecision.Fail(
                    "免费模型 ${failure.modelId} 不可用，且没有可切换的备选模型。",
                )
            }
        }

        is ChannelFailure.ServerUnhealthy -> {
            // 服务器级失败：所有免费成员共用一台服务器，先冷却再探活；
            // 同时把「可以切付费」的出口留给上层（成员各自决定）
            FallbackDecision.Cooldown(
                cooldownSeconds(failure),
                "免费通道暂不可用（本地 OpenCode 服务未就绪），已冷却 60 秒后重探。",
            )
        }

        is ChannelFailure.BrowserCrashed ->
            FallbackDecision.Cooldown(120, "网页端通道浏览器崩溃，120 秒后尝试重启。")

        is ChannelFailure.HttpStatus -> {
            val cooldown = cooldownSeconds(failure)
            when {
                failure.code == 429 -> FallbackDecision.Cooldown(
                    cooldown,
                    "免费池限流（429），${cooldown} 秒后重试。",
                )
                cooldown > 0 -> FallbackDecision.Cooldown(cooldown, "上游返回 ${failure.code}，冷却 ${cooldown} 秒。")
                alternativeFreeModels.isNotEmpty() -> FallbackDecision.RetryWithModel(alternativeFreeModels.first())
                allowPaidTakeover -> FallbackDecision.SwitchToPaid
                else -> FallbackDecision.Fail("上游返回 ${failure.code}，且没有可用备选。")
            }
        }
    }

    /** web-anon 的协议失效：标记 degraded，不自动重试（规格 §9.11）。 */
    fun onProtocolInvalid(detail: String): FallbackDecision =
        FallbackDecision.Fail("网页端通道协议已失效（$detail），等待插件更新，不做自动重试。")
}
