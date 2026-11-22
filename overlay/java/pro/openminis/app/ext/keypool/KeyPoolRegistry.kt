package pro.openminis.app.ext.keypool

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import pro.openminis.app.data.model.LLMModel
import pro.openminis.app.data.model.ModelEntry
import pro.openminis.app.data.model.ModelGroup
import pro.openminis.app.data.model.ProviderCredential
import pro.openminis.app.data.model.ProviderInstance
import pro.openminis.app.data.model.ProviderType
import pro.openminis.app.data.repository.ProviderRepository
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 号池的运行状态，UI 直接渲染。 */
sealed interface KeyPoolStatus {
    data object Off : KeyPoolStatus
    data class Starting(val step: String) : KeyPoolStatus
    data class Running(
        val baseUrl: String,
        val model: String,
        val keyCount: Int,
        val available: Int,
    ) : KeyPoolStatus

    data class Error(val message: String) : KeyPoolStatus
}

/**
 * 号池门面：唯一对 UI 与免费通道暴露的入口。
 *
 * 生命周期与免费通道不同——号池不需要常驻进程，只在「配置完整」时启动一个
 * 本地回环 HTTP 服务。**网关必须常驻**：它注册成了 provider，用户发消息时
 * 才打过来，不能等 UI 打开才启动。所以 [init] 在应用启动路径上被调用一次，
 * 之后由 [reload] 处理配置变更。
 */
object KeyPoolRegistry {

    private const val INSTANCE_ID = "keypool-local"
    private const val LABEL = "API Key 号池"
    private const val GROUP_NAME = "API Key 号池"
    private const val MAX_ENTRIES = 30

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var app: Context? = null

    private val store: KeyPoolStore?
        get() = app?.let { KeyPoolStore(it) }

    private val pool = KeyPool()

    /** 转发专用：长读超时，模型思考时间可能远超普通请求。 */
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gateway: KeyPoolGateway by lazy {
        KeyPoolGateway(
            config = { store?.load() ?: KeyPoolConfig() },
            secretOf = { id -> store?.secret(id) },
            pool = pool,
            http = http,
            log = { writeLog(it) },
        )
    }

    private val _status = MutableStateFlow<KeyPoolStatus>(KeyPoolStatus.Off)
    val status: StateFlow<KeyPoolStatus> = _status.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        // 让上游的数据库/配置先完成首轮加载：ProviderRepository 在 onCreate
        // 最早阶段构造会读到空配置，注册出来的 provider 就少一组模型。
        scope.launch {
            delay(600)
            reload()
        }
    }

    /** 配置变更后重建池子并（重新）注册 provider。可重复调用。 */
    fun reload() {
        val ctx = app ?: return
        scope.launch {
            val cfg = KeyPoolStore(ctx).load()
            syncPool(cfg)
            if (!KeyPoolStore(ctx).enabled || !cfg.ready) {
                gateway.stop()
                runCatching { unregisterProvider() }
                _status.value = KeyPoolStatus.Off
                return@launch
            }
            _status.value = KeyPoolStatus.Starting("启动本地网关")
            val port = runCatching { gateway.start() }.getOrElse { e ->
                _status.value = KeyPoolStatus.Error("网关启动失败：${e.message}")
                return@launch
            }
            _status.value = KeyPoolStatus.Starting("注册到模型列表")
            val model = runCatching { registerProvider(cfg) }.getOrElse { e ->
                val msg = "注册 provider 失败：${e.message}"
                writeLog(msg)
                _status.value = KeyPoolStatus.Error(msg)
                return@launch
            }
            val available = pool.availableCount(KeyPoolConfig.PROVIDER)
            _status.value = KeyPoolStatus.Running(
                baseUrl = gateway.baseUrl,
                model = model,
                keyCount = cfg.usableKeys.size,
                available = available,
            )
            writeLog("号池就绪：port=$port model=$model keys=${cfg.usableKeys.size}")
        }
    }

    /** 保存配置（含新增/修改的明文 Key），随后自动重建。 */
    fun save(config: KeyPoolConfig, secrets: Map<String, String> = emptyMap()) {
        val s = store ?: return
        s.save(config)
        secrets.forEach { (id, key) -> if (key.isNotBlank()) s.putSecret(id, key) }
        reload()
    }

    fun removeKey(keyId: String) {
        store?.removeKey(keyId)
        reload()
    }

    fun setEnabled(enabled: Boolean) {
        val s = store ?: return
        s.enabled = enabled
        if (!enabled) {
            gateway.stop()
            _status.value = KeyPoolStatus.Off
        } else {
            reload()
        }
    }

    fun isEnabled(): Boolean = store?.enabled ?: false

    fun isReady(): Boolean = _status.value is KeyPoolStatus.Running

    fun availableCount(): Int = pool.availableCount(KeyPoolConfig.PROVIDER)

    fun keyState(keyId: String): KeyState = pool.state(keyId)

    fun masked(keyId: String): String = store?.maskedTail(keyId).orEmpty()

    /**
     * 免费通道的降级出口：免费额度用尽时，把同一个 OpenAI 请求交给号池。
     *
     * @return true 表示已经写出了完整响应。
     */
    fun forwardChat(
        body: ByteArray,
        stream: Boolean,
        out: OutputStream,
        headerAlreadySent: Boolean,
    ): Boolean {
        if (!isReady()) return false
        return runCatching { gateway.forwardChat(body, stream, out, headerAlreadySent) }
            .getOrElse { e ->
                writeLog("降级转发失败：${e.message}")
                false
            }
    }

    /**
     * 用指定 Key 发一次最小请求，验证「地址 + Key + 模型」三者是否可用。
     *
     * 走 `/chat/completions` 而不是 `/models`：很多中转站的 `/models`
     * 不校验额度，测出来「可用」，真发消息才发现余额为零。
     */
    suspend fun testConnection(cfg: KeyPoolConfig, keyId: String, secret: String): String =
        withContext(Dispatchers.IO) {
            if (cfg.baseUrl.isBlank()) return@withContext "先填上游地址"
            if (secret.isBlank()) return@withContext "先填 Key"
            val model = cfg.defaultModel.ifBlank {
                // 没填默认模型时，退一步只测清单
                return@withContext testModels(cfg.baseUrl, secret)
            }
            val body = JSONObject()
                .put("model", model)
                .put("stream", false)
                .put("max_tokens", 8)
                .put("messages", org.json.JSONArray().put(
                    JSONObject().put("role", "user").put("content", "ping"),
                ))
                .toString()
            val request = Request.Builder()
                .url(gateway.endpoint(cfg.baseUrl, "/chat/completions"))
                .header("Authorization", "Bearer $secret")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            runCatching {
                http.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (resp.code in 200..299 && !text.contains("\"error\"")) {
                        "连接正常（HTTP ${resp.code}）"
                    } else {
                        "失败 HTTP ${resp.code}：${text.take(200)}"
                    }
                }
            }.getOrElse { "网络异常：${it.message}" }
        }

    private fun testModels(baseUrl: String, secret: String): String =
        runCatching {
            val req = Request.Builder()
                .url(gateway.endpoint(baseUrl, "/models"))
                .header("Authorization", "Bearer $secret")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.code in 200..299) "连接正常，模型清单 ${text.length} 字节" else "失败 HTTP ${resp.code}"
            }
        }.getOrElse { "网络异常：${it.message}" }

    // ---- 内部 ----------------------------------------------------------

    private fun syncPool(cfg: KeyPoolConfig) {
        val alive = cfg.keys.map { it.id }.toSet()
        pool.allStates().map { it.keyId }.filterNot { it in alive }.forEach { pool.removeKey(it) }
        cfg.keys.forEach { pool.addKey(it) }
    }

    /**
     * 把网关注册成普通 OpenAI provider，并登记模型。
     *
     * 返回最终选定的模型 id（写进状态里给 UI 显示）。
     */
    private suspend fun registerProvider(cfg: KeyPoolConfig): String {
        val ctx = app ?: error("未初始化")
        val s = KeyPoolStore(ctx)
        val base = "http://127.0.0.1:${gateway.listeningPort}"
        val repo = ProviderRepository(ctx)
        runCatching { repo.awaitConfigLoaded() }

        val id = s.providerInstanceId ?: INSTANCE_ID
        val existing = repo.instances.firstOrNull { it.id == id }
        if (existing == null) {
            repo.addInstance(
                ProviderInstance(
                    id = id,
                    label = LABEL,
                    providerType = ProviderType.openAI,
                    credentialType = ProviderCredential.apiKey,
                    customBaseURL = base,
                    appendV1Suffix = true,
                ),
            )
            s.providerInstanceId = id
        } else if (existing.customBaseURL != base) {
            repo.updateInstance(existing.copy(customBaseURL = base))
        }
        // 本地网关不校验 Key，但上游 provider 有些路径要求非空
        if (repo.loadApiKey(id).isNullOrBlank()) repo.saveApiKey(id, "local")

        val discovered = fetchModelIds()
        val models = LinkedHashSet<String>()
        if (cfg.defaultModel.isNotBlank()) models.add(cfg.defaultModel)
        discovered.take(MAX_ENTRIES).forEach { models.add(it) }
        if (models.isEmpty()) {
            throw IllegalStateException("拿不到上游模型清单，请在设置里填写「默认模型」")
        }

        val have = repo.entriesFor(id).map { it.baseModel.id }.toHashSet()
        models.filterNot { it in have }.forEach { modelId ->
            repo.addEntry(
                ModelEntry(
                    providerInstanceId = id,
                    baseModel = LLMModel(id = modelId, displayName = modelId, provider = "keypool"),
                    isCustom = true,
                ),
            )
        }
        ensureGroup(repo, id)
        s.lastError = null
        return cfg.defaultModel.ifBlank { discovered.firstOrNull().orEmpty() }
    }

    private fun unregisterProvider() {
        val ctx = app ?: return
        val s = KeyPoolStore(ctx)
        val repo = ProviderRepository(ctx)
        runBlocking { runCatching { repo.awaitConfigLoaded() } }
        val id = s.providerInstanceId ?: INSTANCE_ID
        runCatching { repo.entriesFor(id).forEach { repo.removeEntry(it.id) } }
    }

    private fun fetchModelIds(): List<String> {
        if (!isReady() && gateway.listeningPort <= 0) return emptyList()
        val req = Request.Builder().url("http://127.0.0.1:${gateway.listeningPort}/v1/models").get().build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.code !in 200..299) return emptyList()
                val arr = JSONObject(text).optJSONArray("data") ?: return emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
                }
            }
        }.getOrElse { emptyList() }
    }

    /** 放进一个模型组，并在用户还没有任何默认组时设为默认。 */
    private fun ensureGroup(repo: ProviderRepository, instanceId: String) {
        val entryIds = repo.entriesFor(instanceId).map { it.id }
        if (entryIds.isEmpty()) return
        val existing = repo.config.value.modelGroups.firstOrNull { it.name == GROUP_NAME }
        val groupId = if (existing == null) {
            repo.addGroup(ModelGroup(name = GROUP_NAME, memberEntryIds = entryIds.toMutableList()))
            repo.config.value.modelGroups.firstOrNull { it.name == GROUP_NAME }?.id
        } else {
            if (!existing.memberEntryIds.containsAll(entryIds)) {
                repo.updateGroup(
                    existing.copy(memberEntryIds = (existing.memberEntryIds + entryIds).distinct().toMutableList()),
                )
            }
            existing.id
        }
        // 没有默认组时设为默认：否则模型选择器里仍然看不到号池
        if (groupId != null && repo.defaultPrimaryGroupId == null) {
            repo.defaultPrimaryGroupId = groupId
        }
    }

    private fun writeLog(message: String) {
        val ctx = app ?: return
        runCatching {
            val now = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            File(ctx.filesDir, "keypool.log").appendText("[$now] $message\n")
        }
    }
}
