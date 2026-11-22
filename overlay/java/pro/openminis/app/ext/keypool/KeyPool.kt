package pro.openminis.app.ext.keypool

import pro.openminis.app.ext.opencode.api.TokenUsage

/**
 * 池中的一把 Key。
 *
 * **不持有明文**：`secretRef` 只是指向 Keystore 条目的标识（规格 §7「Key 不以明文写日志；
 * 凭证通过 GenerateOptions.apiKey 注入」）。需要明文时由宿主按 ref 取出，
 * 用完即弃，不进入本模块任何数据结构。
 */
data class PooledKey(
    val id: String,
    val provider: String,
    val secretRef: String,
    /** 加权轮询的权重，默认 1。额度大的 Key 可以给更高权重。 */
    val weight: Int = 1,
    val enabled: Boolean = true,
    /** 人工备注，例如「主账号」「备用」。仅展示用。 */
    val label: String = "",
)

/** 单把 Key 的运行时状态。规格 §7「冷却状态等元数据可明文存」。 */
data class KeyState(
    val keyId: String,
    /** 冷却截止时刻（epoch millis）。0 表示不在冷却。 */
    val cooldownUntil: Long = 0L,
    val lastError: String = "",
    val consecutiveFailures: Int = 0,
    val successCount: Long = 0L,
    val failureCount: Long = 0L,
    /** 累计用量，与热力图共享同一份流水（规格 §7「记录」）。 */
    val usage: TokenUsage = TokenUsage.ZERO,
    /** 累计延迟样本数，用于算平均延迟。 */
    val latencySamples: Long = 0L,
    val latencySumMs: Long = 0L,
) {
    val avgLatencyMs: Long
        get() = if (latencySamples > 0) latencySumMs / latencySamples else 0L
}

/** [KeyPool.onFailure] 的决策：是否换 Key、冷却多久。 */
sealed interface FailureAction {
    /** 该 Key 进入冷却，本次调用应换用下一把。[seconds] 为冷却秒数。 */
    data class Rotate(val seconds: Long, val reason: String) : FailureAction

    /** 不换 Key：网络抖动、参数错误、内容审核等，换 Key 无意义且会浪费额度。 */
    data class Keep(val reason: String) : FailureAction
}

/**
 * 多 Key 池（规格 §7）。
 *
 * 调度：**加权轮询 + 会话粘性**。同一 session 在会话生命周期内优先复用同一把 Key，
 * 这样上游的 prompt cache 能命中（换 Key 等于换账号，缓存全失效）。
 * 会话结束由宿主调 [releaseSession] 归还。
 *
 * 健康与冷却**差异化**：429 短冷却（限流是临时的）、401 中冷却（凭证可能被撤）、
 * 402 长冷却（欠费需要人处理）。其他错误一律不换 Key。
 *
 * 线程模型：所有方法都要求外部串行调用（Agent 侧本就在单条编排协程里），
 * 因此不加锁，避免把同步开销带进每次请求。
 */
class KeyPool(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val keys = LinkedHashMap<String, PooledKey>()
    private val states = HashMap<String, KeyState>()

    /** 平滑加权轮询的当前权重（nginx 算法）。 */
    private val currentWeights = HashMap<String, Int>()

    /** sessionId → keyId 的粘性绑定。 */
    private val sticky = HashMap<String, String>()

    fun addKey(key: PooledKey) {
        require(key.id.isNotBlank()) { "key.id 不能为空" }
        keys[key.id] = key
        states.getOrPut(key.id) { KeyState(key.id) }
        currentWeights.putIfAbsent(key.id, 0)
    }

    fun removeKey(keyId: String) {
        keys.remove(keyId)
        states.remove(keyId)
        currentWeights.remove(keyId)
        sticky.entries.removeAll { it.value == keyId }
    }

    fun keysOf(provider: String): List<PooledKey> = keys.values.filter { it.provider == provider }

    fun key(keyId: String): PooledKey? = keys[keyId]

    fun state(keyId: String): KeyState = states[keyId] ?: KeyState(keyId)

    fun allStates(): List<KeyState> = keys.keys.map { state(it) }

    /** 当前是否可用（启用且不在冷却）。 */
    fun isAvailable(keyId: String, now: Long = clock()): Boolean {
        val k = keys[keyId] ?: return false
        if (!k.enabled) return false
        return state(keyId).cooldownUntil <= now
    }

    /**
     * 取一把可用的 Key。
     *
     * 顺序：会话粘性 → 加权轮询。粘性命中但该 Key 正在冷却时**不降级复用**，
     * 而是重新选一把并改写粘性——否则一个被限流的 Key 会拖住整个会话。
     */
    fun acquire(provider: String, sessionId: String, now: Long = clock()): PooledKey? {
        val candidates = keys.values.filter { it.provider == provider && isAvailable(it.id, now) }
        if (candidates.isEmpty()) return null

        // 1) 粘性优先
        val boundId = sessionId.takeIf { it.isNotEmpty() }?.let { sticky[it] }
        if (boundId != null) {
            val bound = candidates.firstOrNull { it.id == boundId }
            if (bound != null) return bound
        }

        // 2) 平滑加权轮询
        val picked = smoothWeightedPick(candidates) ?: return null
        if (sessionId.isNotEmpty()) sticky[sessionId] = picked.id
        return picked
    }

    /**
     * 取一把可用且不在 [exclude] 里的 Key。
     *
     * 单次请求内部换 Key 用它：同一请求重试时不能让 [acquire] 的粘性
     * 把刚刚失败的那把又还回来。
     */
    fun acquireExcluding(
        provider: String,
        exclude: Set<String>,
        sessionId: String = "",
        now: Long = clock(),
    ): PooledKey? {
        val candidates = keys.values.filter {
            it.provider == provider && it.id !in exclude && isAvailable(it.id, now)
        }
        if (candidates.isEmpty()) return null
        val picked = smoothWeightedPick(candidates) ?: return null
        if (sessionId.isNotEmpty()) sticky[sessionId] = picked.id
        return picked
    }

    fun availableCount(provider: String, now: Long = clock()): Int =
        keys.values.count { it.provider == provider && isAvailable(it.id, now) }

    /**
     * 平滑加权轮询：每轮把 totalWeight 加到各自当前权重上，选最大的，然后减去 totalWeight。
     * 比「按权重展开成数组再取模」更均匀，且不随权重增大而膨胀内存。
     */
    private fun smoothWeightedPick(candidates: List<PooledKey>): PooledKey? {
        if (candidates.isEmpty()) return null
        val total = candidates.sumOf { it.weight.coerceAtLeast(1) }
        if (total <= 0) return candidates.first()

        var best: PooledKey? = null
        var bestWeight = Int.MIN_VALUE
        for (k in candidates) {
            val w = (currentWeights[k.id] ?: 0) + k.weight.coerceAtLeast(1)
            currentWeights[k.id] = w
            if (w > bestWeight) {
                bestWeight = w
                best = k
            }
        }
        best?.let { currentWeights[it.id] = (currentWeights[it.id] ?: 0) - total }
        return best
    }

    /** 记录一次成功调用。清除冷却与连续失败计数。 */
    fun onSuccess(
        keyId: String,
        latencyMs: Long = 0,
        usage: TokenUsage = TokenUsage.ZERO,
        now: Long = clock(),
    ) {
        val s = state(keyId)
        states[keyId] = s.copy(
            cooldownUntil = 0L,
            lastError = "",
            consecutiveFailures = 0,
            successCount = s.successCount + 1,
            usage = s.usage + usage,
            latencySamples = if (latencyMs > 0) s.latencySamples + 1 else s.latencySamples,
            latencySumMs = if (latencyMs > 0) s.latencySumMs + latencyMs else s.latencySumMs,
        )
    }

    /**
     * 记录一次失败并给出是否换 Key 的决策。
     *
     * 差异化冷却（规格 §7）：
     * - 429 限流 → 60s（通常几分钟内就恢复）
     * - 401 凭证 → 5min（可能被撤销，留出人工介入窗口）
     * - 402 欠费 → 30min（需要人充值，短冷却只会反复撞墙）
     * - 其他（网络 / 参数 / 审核）→ 不换 Key
     */
    fun onFailure(keyId: String, httpStatus: Int?, message: String = "", now: Long = clock()): FailureAction {
        val s = state(keyId)
        states[keyId] = s.copy(
            consecutiveFailures = s.consecutiveFailures + 1,
            failureCount = s.failureCount + 1,
            lastError = message.take(200),
        )

        val cooldownSeconds = when {
             httpStatus == 429 -> 60L
            httpStatus == 401 -> 300L
            httpStatus == 402 -> 1800L
            // 额度耗尽的文案五花八门：有的中转站回 403，有的回 400，还有的
            // 200 里塞 error 让调用方自己判断。只看状态码会漏掉一大半，
            // 这类错误换 Key 是唯一有效的处理。
            isQuotaExhausted(httpStatus, message) -> 1800L
            else -> null
        }

        if (cooldownSeconds == null) {
            return FailureAction.Keep(
                reason = when (httpStatus) {
                    null -> "网络异常，换 Key 无意义"
                    400 -> "参数错误，换 Key 同样会失败"
                    403 -> "内容或权限被拒，换 Key 无法绕过"
                    else -> "非凭证类错误（${httpStatus}），保持当前 Key"
                },
            )
        }

        val st = state(keyId)
        states[keyId] = st.copy(cooldownUntil = now + cooldownSeconds * 1000L)
        // 冷却中的 Key 不应继续被粘性选中
        sticky.entries.removeAll { it.value == keyId }
        return FailureAction.Rotate(
            seconds = cooldownSeconds,
            reason = when (httpStatus) {
                429 -> "限流（429），冷却 60 秒"
                401 -> "凭证失效（401），冷却 5 分钟"
                402 -> "额度耗尽（402），冷却 30 分钟"
                else -> "HTTP $httpStatus"
            },
        )
    }

    /**
     * 是否是「这把 Key 的额度没了」。
     *
     * 不看状态码只看文案会误伤内容审核（也会带 limit 字样），所以要求
     * 4xx 且命中额度类词汇。反过来只看 402 又会漏掉 OpenCode Zen 这类
     * 403 `permission_denied` + `free usage limit` 的写法。
     */
    private fun isQuotaExhausted(httpStatus: Int?, message: String): Boolean {
        if (httpStatus == null) return false
        // 200 也要认：OpenAI 兼容层常用「200 + error 体」表示额度不足
        if (httpStatus != 200 && httpStatus !in 400..499) return false
        if (httpStatus == 401 || httpStatus == 429) return false
        val m = message.lowercase()
        if (m.isBlank()) return false
        val quotaWords = listOf(
            "quota", "free usage", "usage limit", "rate limit", "credit",
            "balance", "insufficient", "exceeded", "exhausted", "额度",
            "余额", "配额", "已用完", "用尽", "欠费",
        )
        return quotaWords.any { m.contains(it) }
    }

    /** 冷却到期、等待探活的 Key。调用方拿去做一次轻量请求确认是否恢复。 */
    fun probeDue(now: Long = clock()): List<PooledKey> =
        keys.values.filter { k ->
            val s = state(k.id)
            k.enabled && s.cooldownUntil in 1..now
        }

    /** 探活成功，提前解除冷却。 */
    fun probeSucceeded(keyId: String) {
        val s = state(keyId)
        states[keyId] = s.copy(cooldownUntil = 0L, consecutiveFailures = 0, lastError = "")
    }

    /** 会话结束，归还粘性绑定。 */
    fun releaseSession(sessionId: String) {
        if (sessionId.isNotEmpty()) sticky.remove(sessionId)
    }

    /** 仅测试与诊断用：查看当前粘性绑定。 */
    fun stickyBinding(sessionId: String): String? = sticky[sessionId]

    fun clear() {
        keys.clear()
        states.clear()
        currentWeights.clear()
        sticky.clear()
    }
}
