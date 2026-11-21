package pro.openminis.app.ext.opencode.runtime

import android.content.Context
import pro.openminis.app.data.model.LLMModel
import pro.openminis.app.data.model.ModelEntry
import pro.openminis.app.data.model.ModelGroup
import pro.openminis.app.data.model.ProviderCredential
import pro.openminis.app.data.model.ProviderInstance
import pro.openminis.app.data.model.ProviderType
import pro.openminis.app.data.repository.ProviderRepository
import pro.openminis.app.ext.opencode.api.OpencodeServerClient
import pro.openminis.app.ext.opencode.api.ServerHttpException
import pro.openminis.app.ext.opencode.bridge.OpenAiBridgeServer
import pro.openminis.app.ext.opencode.model.FreeModel
import pro.openminis.app.ext.opencode.model.ModelDiscoveryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 免费通道的运行状态。UI 直接渲染，不做二次推断。 */
sealed interface FreeTokenStatus {
    data object Off : FreeTokenStatus
    data class Working(val step: String, val progress: Int) : FreeTokenStatus
    /**
     * 服务已就绪。[note] 用来解释「服务在跑但结果不对」的情况：
     * 模型清单 401、清单里没有免费模型、或未解析到密码。
     */
    data class Running(val baseUrl: String, val model: String?, val note: String? = null) : FreeTokenStatus
    data class Error(val message: String, val retryable: Boolean = true) : FreeTokenStatus
}

/**
 * 免费 Token 通道的门面——这是整条链路唯一对 UI 暴露的入口。
 *
 * 一次 [enable] 做四件事，缺一不可：
 *  1. 校验并准备 `filesDir/opencode`（配置文件、日志目录）；
 *  2. 从 `nativeLibraryDir` 拉起 `libopencode.so serve --port 0`，解析真实端口；
 *  3. 在本机回环起 OpenAI 兼容桥接（见 [OpenAiBridgeServer]）；
 *  4. 把桥接地址注册成上游的一个普通 OpenAI provider，让聊天链路直接可用。
 *
 * 第 4 步是「能用」与「能看到一张诊断卡片」的分界线：不注册的话，用户在
 * 聊天里根本选不到这个模型。
 *
 * 所有步骤都在 IO 线程串行执行，状态逐段推给 UI——用户看到的是「正在做什么」，
 * 而不是一个不知何时结束的转圈。
 */
class FreeTokenManager private constructor(context: Context) {

    private val app = context.applicationContext
    private val store = FreeTokenStore(app)
    private val layout = OpencodeLayout(app.filesDir)
    private val installer = OpencodeRuntimeInstaller(layout)
    private val process = OpencodeProcessManager(installer, layout)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow<FreeTokenStatus>(FreeTokenStatus.Off)
    val status: StateFlow<FreeTokenStatus> = _status.asStateFlow()

    private val _models = MutableStateFlow<List<FreeModel>>(emptyList())
    val models: StateFlow<List<FreeModel>> = _models.asStateFlow()

    private val discovery = ModelDiscoveryCache()

    /** 最近一次模型发现失败的可读原因，UI 直接显示，避免「就是没有模型」这种哑谜。 */
    @Volatile
    private var modelError: String? = null

    private val bridge = OpenAiBridgeServer(
        service = { installer.readService() },
        http = http,
        models = { _models.value },
        log = { writeLog("bridge", it) },
    )

    @Volatile
    private var bridgePort: Int = 0

    /** 桥接地址（未启动时为 null）。群聊/测试都走这个地址。 */
    val bridgeBaseUrl: String? get() = if (bridgePort > 0) "http://127.0.0.1:$bridgePort/v1" else null

    val defaultModel: String? get() = store.defaultModel

    fun modelsBlocking(): List<FreeModel> = _models.value

    // ---- 生命周期 ---------------------------------------------------------

    /** 冷启动：只看用户是否启用过，不主动弹窗。 */
    fun autoStartIfOptedIn() {
        if (!store.optIn) {
            _status.value = FreeTokenStatus.Off
            return
        }
        if (_status.value is FreeTokenStatus.Running || _status.value is FreeTokenStatus.Working) return
        enable()
    }

    fun enable() {
        scope.launch {
            store.optIn = true
            _status.value = FreeTokenStatus.Working("检查运行时", 10)

            val binary = File(app.applicationInfo.nativeLibraryDir, BINARY_NAME)
            if (!binary.exists()) {
                fail("安装包里没有 $BINARY_NAME（nativeLibraryDir 未解压）", retryable = false)
                return@launch
            }
            if (!binary.canExecute()) {
                fail("$BINARY_NAME 没有执行权限：${binary.absolutePath}", retryable = false)
                return@launch
            }

            _status.value = FreeTokenStatus.Working("准备运行时目录", 25)
            when (val prepared = installer.prepare(binary.absolutePath)) {
                is PrepareResult.Failed -> {
                    fail(prepared.detail, retryable = prepared.reason == FailureReason.IO_ERROR)
                    return@launch
                }
                else -> Unit
            }

            _status.value = FreeTokenStatus.Working("启动本地服务", 45)
            val started = process.start(binary.absolutePath)
            if (started is StartResult.Failed) {
                fail(started.detail, retryable = started.reason !in NON_RETRYABLE)
                return@launch
            }
            val service = (started as StartResult.Ready).service
            writeLog("runtime", "opencode ready on ${service.baseUrl} pid=${service.pid}")

            _status.value = FreeTokenStatus.Working("启动本地桥接", 65)
            bridgePort = runCatching { bridge.start() }.getOrElse { e ->
                fail("桥接启动失败：${e.message}", retryable = true)
                return@launch
            }

            _status.value = FreeTokenStatus.Working("发现免费模型", 80)
            refreshModelsLocked(service)

            val model = store.defaultModel ?: _models.value.firstOrNull()?.id
            if (store.defaultModel == null && model != null) store.defaultModel = model

            _status.value = FreeTokenStatus.Working("注册到模型列表", 92)
            runCatching { registerProvider() }
                .onFailure { writeLog("register", "注册失败：${it.message}") }

            store.lastPort = bridgePort
            store.lastError = modelError
            _status.value = FreeTokenStatus.Running(
                baseUrl = bridgeBaseUrl ?: "",
                model = model,
                note = statusNote(),
            )
        }
    }

    fun disable() {
        scope.launch {
            store.optIn = false
            bridgePort = 0
            runCatching { bridge.stop() }
            runCatching { process.stop() }
            runCatching { installer.clear() }
            _models.value = emptyList()
            _status.value = FreeTokenStatus.Off
        }
    }

    fun restart() {
        scope.launch {
            bridgePort = 0
            runCatching { bridge.stop() }
            runCatching { process.stop() }
            enable()
        }
    }

    /** 换模型：桥接按请求里的 model 字段路由，这里只需记住默认值。 */
    fun selectModel(id: String) {
        store.defaultModel = id
        val current = _status.value
        if (current is FreeTokenStatus.Running) {
            _status.value = current.copy(model = id)
        }
    }

    /** 新对话：让桥接丢掉当前 OpenCode 会话，避免上下文串场。 */
    fun startNewConversation() {
        bridge.resetSession()
    }

    fun lastError(): String? = store.lastError

    fun lastPort(): Int = store.lastPort

    // ---- 模型发现 ---------------------------------------------------------

    private suspend fun refreshModelsLocked(service: ServiceConfig) {
        val client = OpencodeServerClient(http, service) { writeLog("server", it) }
        val payload = try {
            client.modelsJson()
        } catch (e: ServerHttpException) {
            // 401 基本只有一个原因：用的不是 serve 打印出来的那个密码
            val why = if (e.code == 401) {
                "模型清单被拒绝（HTTP 401）——认证密码与 serve 输出的不一致"
            } else {
                "模型清单请求失败 HTTP ${e.code}"
            }
            writeLog("models", "$why；响应：${e.bodyPreview.take(300)}")
            modelError = why
            return
        } catch (e: Exception) {
            val why = "模型清单请求异常：${e.message ?: e.javaClass.simpleName}"
            writeLog("models", why)
            modelError = why
            return
        }

        val found = discovery.selectFree(payload)
        writeLog("models", "发现 ${found.size} 个免费模型：${found.joinToString { it.id }}")
        if (found.isEmpty()) {
            modelError = "服务端返回了模型清单，但里面没有免费模型"
            writeLog("models", "清单原文（前 600 字）：${payload.take(600)}")
        } else {
            modelError = null
            _models.value = found
        }
    }

    fun refreshModels() {
        scope.launch {
            val service = installer.readService() ?: return@launch
            _status.value = FreeTokenStatus.Working("刷新模型", 70)
            refreshModelsLocked(service)
            _status.value = FreeTokenStatus.Running(
                baseUrl = bridgeBaseUrl ?: "",
                model = store.defaultModel,
                note = statusNote(),
            )
        }
    }

    // ---- 注册到上游 provider 体系 -----------------------------------------

    /**
     * 把桥接注册成一个普通 OpenAI 兼容 provider。
     *
     * 为什么走这条而不是自定义 `ProviderType`：上游的 `ProviderFactory` 只认识
     * 固定几个枚举，新增枚举要改工厂、模型选择器、导入导出——上游一更新就冲突。
     * 而「自定义 baseURL 的 OpenAI 实例」是上游**已经支持并测试过**的路径，
     * 空 API Key 也被 `allowsEmptyAPIKey` 明确允许（本地网关场景）。
     */
    private fun registerProvider() {
        val base = "http://127.0.0.1:$bridgePort"
        val repo = ProviderRepository(app)
        // 仓库是异步加载配置的：不先等它读完，instances 会是空列表，
        // 于是每次启动都当成"首次注册"，堆出一串同 id 的实例。
        // awaitConfigLoaded 是挂起函数，runCatching 的 lambda 不是挂起上下文，
        // 只能直接用 try/catch
        runBlocking { try { repo.awaitConfigLoaded() } catch (e: Exception) { /* 读不到就按空配置继续 */ } }
        val id = store.providerInstanceId ?: INSTANCE_ID

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
            store.providerInstanceId = id
        } else {
            // 端口每次启动都可能变，必须同步，否则旧地址会连不上
            if (existing.customBaseURL != base) {
                repo.updateInstance(existing.copy(customBaseURL = base))
            }
        }

        val have = repo.entriesFor(id).map { it.baseModel.id }.toHashSet()
        _models.value.forEach { m ->
            if (m.id !in have) {
                repo.addEntry(
                    ModelEntry(
                        providerInstanceId = id,
                        baseModel = LLMModel(
                            id = m.id,
                            displayName = m.label,
                            provider = "opencode",
                        ),
                        isCustom = true,
                    ),
                )
            }
        }
        ensureModelGroup(repo, id)
        writeLog("register", "provider=$id base=$base models=${_models.value.size}")
    }

    /**
     * 把免费模型放进一个模型组，并（仅在用户还没有默认组时）设为默认。
     *
     * 上游聊天页选的是「模型组」而不是单个 entry——只 addEntry 不进组，
     * 模型在选择器里就是不出现，用户看到的仍是「免费通道用不了」。
     * 已经有默认组的用户不动他的选择，避免升级后模型被悄悄换掉。
     */
    private fun ensureModelGroup(repo: ProviderRepository, instanceId: String) {
        val entryIds = repo.entriesFor(instanceId).map { it.id }
        if (entryIds.isEmpty()) return

        val name = GROUP_NAME
        val existing = repo.config.value.modelGroups.firstOrNull { it.name == name }
        val groupId = if (existing == null) {
            repo.addGroup(ModelGroup(name = name, memberEntryIds = entryIds.toMutableList()))
            repo.config.value.modelGroups.firstOrNull { it.name == name }?.id
        } else {
            repo.updateGroup(existing.copy(memberEntryIds = entryIds.toMutableList()))
            existing.id
        }

        if (groupId != null && repo.defaultPrimaryGroupId == null) {
            repo.defaultPrimaryGroupId = groupId
            writeLog("register", "默认模型组设为 $name")
        }
    }

    /** 运行中状态的补充说明：优先报错原因，其次报「一个模型都没有」。 */
    private fun statusNote(): String? = modelError
        ?: if (_models.value.isEmpty()) "没有可用模型：服务端清单为空或全部被判为收费" else null

    // ---- 诊断 -------------------------------------------------------------

    fun logs(): String = buildString {
        listOf(layout.initLog, layout.serverLog).forEach { f ->
            if (f.exists()) {
                appendLine("--- ${f.name} ---")
                appendLine(runCatching { f.readText().takeLast(8000) }.getOrDefault("(读取失败)"))
            }
        }
    }

    private fun writeLog(tag: String, message: String) {
        runCatching {
            if (!layout.logsDir.exists()) layout.logsDir.mkdirs()
            val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            layout.initLog.appendText("$ts [$tag] $message\n")
        }
    }

    private fun fail(message: String, retryable: Boolean) {
        writeLog("fail", message)
        store.lastError = message
        _status.value = FreeTokenStatus.Error(message, retryable)
    }

    /** 同步探活，供 UI 的「检查」按钮使用。 */
    fun probeNow(): Boolean {
        val service = installer.readService() ?: return false
        return runBlocking {
            withContext(Dispatchers.IO) { process.defaultHealth(service) }
        }
    }

    companion object {
        const val BINARY_NAME = "libopencode.so"
        const val INSTANCE_ID = "opencode-local-free"
        const val LABEL = "内置 OpenCode（免费）"
        const val GROUP_NAME = "内置 OpenCode（免费）"

        private val NON_RETRYABLE = setOf(
            StartFailure.BINARY_MISSING,
            StartFailure.BINARY_NOT_EXECUTABLE,
        )

        @Volatile
        private var instance: FreeTokenManager? = null

        fun get(context: Context): FreeTokenManager =
            instance ?: synchronized(this) {
                instance ?: FreeTokenManager(context).also { instance = it }
            }
    }
}
