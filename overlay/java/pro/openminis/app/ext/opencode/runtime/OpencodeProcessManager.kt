package pro.openminis.app.ext.opencode.runtime

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

sealed interface StartResult {
    data class Ready(val service: ServiceConfig, val reused: Boolean) : StartResult
    data class Failed(val reason: StartFailure, val detail: String) : StartResult
}

enum class StartFailure {
    BINARY_MISSING,
    BINARY_NOT_EXECUTABLE,
    IO_ERROR,
    PORT_UNAVAILABLE,
    HEALTH_TIMEOUT,
}

/**
 * 拉起 / 停止本地 OpenCode 服务器（规格 §9.5）。
 *
 * 关键设计：**端口不写死**。以 `--port 0` 启动由内核分配，再从 stdout 解析实际端口。
 * 固定 4096 在 Termux 共存设备上几乎必然 `EADDRINUSE`，而那个报错用户完全无从下手。
 *
 * "复用还是重启"的判据是**健康检查**而不是文件存在：进程被系统杀掉后
 * service.json 仍留在盘上，只信文件会让 UI 显示"已就绪"但每个请求都连不上。
 */
class OpencodeProcessManager(
    private val installer: OpencodeRuntimeInstaller,
    private val layout: OpencodeLayout,
    private val http: OkHttpClient = OkHttpClient(),
    private val launcher: ProcessLauncher = SystemProcessLauncher(),
    private val portTimeoutMs: Long = 15_000,
    private val healthTimeoutMs: Long = 20_000,
    private val healthPollMs: Long = 250,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    /** 测试注入；为 null 时真的打 /global/health。 */
    private val healthProbe: ((ServiceConfig) -> Boolean)? = null,
) {

    @Volatile
    private var running: Process? = null

    /** 当前进程是否存活（UI 状态 chip 用）。 */
    fun isRunning(): Boolean = running?.isAlive == true

    /**
     * 启动或复用。
     *
     * 幂等：若已有 service.json、进程存活且健康检查通过，直接返回 `reused = true`，
     * 不重复拉起（规格 §9.5「目录与 service.json 完整则跳过初始化直接拉起服务器」）。
     */
    fun start(binaryPath: String, personaInstruction: String? = null): StartResult {
        val prepared = installer.prepare(binaryPath, personaInstruction)
        if (prepared is PrepareResult.Failed) {
            val reason = when (prepared.reason) {
                FailureReason.BINARY_MISSING -> StartFailure.BINARY_MISSING
                FailureReason.BINARY_NOT_EXECUTABLE -> StartFailure.BINARY_NOT_EXECUTABLE
                FailureReason.IO_ERROR -> StartFailure.IO_ERROR
            }
            return StartResult.Failed(reason, prepared.detail)
        }

        // 复用判定：进程活着 + 健康检查通过
        val existing = installer.readService()
        if (existing != null && isRunning() && probe(existing)) {
            return StartResult.Ready(existing, reused = true)
        }

        val command = listOf(
            binaryPath, "serve",
            "--port", "0",
            "--hostname", ServiceConfig.HOSTNAME,
        )

        return try {
            val process = launcher.start(command, layout.environment(), layout.workspace)
            running = process

            val port = awaitPort(process)
            if (port == null) {
                destroyQuietly(process)
                running = null
                installer.clearService()
                return StartResult.Failed(
                    StartFailure.PORT_UNAVAILABLE,
                    "无法从 opencode 的输出中解析端口（15 秒超时）。" +
                        "常见原因：二进制不可执行、启动即崩溃、或输出被 ANSI 控制码干扰。",
                )
            }

            val service = installer.newService(port, process.safePid())
            installer.writeService(service)

            if (!awaitHealthy(service)) {
                destroyQuietly(process)
                running = null
                installer.clearService()
                return StartResult.Failed(
                    StartFailure.HEALTH_TIMEOUT,
                    "服务器已启动（端口 $port）但 /global/health 在 20 秒内未通过。",
                )
            }

            StartResult.Ready(service, reused = false)
        } catch (e: Exception) {
            running = null
            StartResult.Failed(StartFailure.IO_ERROR, e.message ?: e.javaClass.simpleName)
        }
    }

    /** 停止：先 TERM，5 秒不退再强杀，最后清掉 service.json（避免下次误判为存活）。 */
    fun stop() {
        val p = running ?: run {
            installer.clearService()
            return
        }
        destroyQuietly(p)
        running = null
        installer.clearService()
    }

    /** 真实健康检查：`GET /global/health`，带 Basic 认证。 */
    fun defaultHealth(service: ServiceConfig): Boolean = try {
        val req = Request.Builder()
            .url("${service.baseUrl}/global/health")
            .header("Authorization", service.authorization)
            .build()
        http.newCall(req).execute().use { it.isSuccessful }
    } catch (e: Exception) {
        false
    }

    // ---- 内部 ------------------------------------------------------------

    /** 读 stdout 直到解析出端口。读线程单独开，主线程带超时等待。 */
    private fun awaitPort(process: Process): Int? {
        val reader = process.inputStream.bufferedReader()
        val exec = Executors.newSingleThreadExecutor { r ->
            Thread(r, "opencode-stdout").apply { isDaemon = true }
        }
        return try {
            val future = exec.submit<Int?> {
                var found: Int? = null
                while (true) {
                    val line = try {
                        reader.readLine()
                    } catch (e: Exception) {
                        break
                    } ?: break
                    val port = PortParser.parsePort(line)
                    if (port != null) {
                        found = port
                        break
                    }
                }
                found
            }
            future.get(portTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            null
        } finally {
            exec.shutdownNow()
        }
    }

    /** 构造函数默认参数不能引用成员函数，所以在这里做一次解析。 */
    private fun probe(service: ServiceConfig): Boolean =
        (healthProbe ?: { defaultHealth(it) }).invoke(service)

    private fun awaitHealthy(service: ServiceConfig): Boolean {
        val deadline = System.nanoTime() + healthTimeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (probe(service)) return true
            sleeper(healthPollMs)
        }
        return false
    }

    private fun destroyQuietly(process: Process) {
        try {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
        } catch (e: Exception) {
            // 已经死了
        }
    }
}

/** 进程工厂抽象：生产用 [SystemProcessLauncher]，测试可换成脚本级替身。 */
interface ProcessLauncher {
    fun start(command: List<String>, env: Map<String, String>, workDir: File?): Process
}

class SystemProcessLauncher : ProcessLauncher {
    override fun start(command: List<String>, env: Map<String, String>, workDir: File?): Process {
        val pb = ProcessBuilder(command)
        pb.environment().clear()
        pb.environment().putAll(env)
        if (workDir != null) {
            if (!workDir.exists()) workDir.mkdirs()
            pb.directory(workDir)
        }
        pb.redirectErrorStream(false)
        return pb.start()
    }
}

/**
 * `Process.pid()` 是 Java 9 API；Android 的 libcore 在部分版本上没有它。
 * 反射取，取不到返回 -1——service.json 里 pid 只是辅助信息，缺失不影响启动。
 */
private fun Process.safePid(): Long = try {
    (javaClass.getMethod("pid").invoke(this) as? Long) ?: -1L
} catch (e: Exception) {
    -1L
}
