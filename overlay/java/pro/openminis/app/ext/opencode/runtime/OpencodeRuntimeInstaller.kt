package pro.openminis.app.ext.opencode.runtime

import java.io.File

/**
 * 文件系统抽象。
 *
 * 存在的唯一理由：让「幂等初始化 / 断点续做 / 清除」变成**纯逻辑**，
 * 能在 JVM 上把「目录齐但缺 service.json」「config 被截断」「半成品残留」
 * 这些真实故障场景直接跑成单元测试（规格 §11「中断恢复」验收项）。
 */
interface RuntimeFs {
    fun exists(path: String): Boolean
    fun isDirectory(path: String): Boolean
    fun isExecutable(path: String): Boolean
    fun mkdirs(path: String): Boolean
    fun write(path: String, content: String)
    fun read(path: String): String?
    fun deleteRecursively(path: String): Boolean
}

/** 生产实现：直接落到 java.io.File（Android 上同一套 API）。 */
class JavaRuntimeFs : RuntimeFs {
    override fun exists(path: String) = File(path).exists()
    override fun isDirectory(path: String) = File(path).isDirectory
    override fun isExecutable(path: String) = File(path).canExecute()
    override fun mkdirs(path: String) = File(path).let { it.exists() || it.mkdirs() }

    override fun write(path: String, content: String) {
        val f = File(path)
        f.parentFile?.let { if (!it.exists()) it.mkdirs() }
        f.writeText(content, Charsets.UTF_8)
    }

    override fun read(path: String): String? =
        File(path).takeIf { it.isFile }?.readText(Charsets.UTF_8)

    override fun deleteRecursively(path: String): Boolean {
        val f = File(path)
        if (!f.exists()) return true
        return f.deleteRecursively()
    }
}

/** 文件层体检结果。 */
sealed interface Inspection {
    /** 从未启用过（目录不存在）。 */
    data object NotEnabled : Inspection

    /** 有残留但不完整；[missing] 列出缺什么，供 UI 显示可读原因。 */
    data class Partial(val missing: List<String>) : Inspection

    /** 文件层完整，[service] 为上次写入的服务描述（进程是否存活另判）。 */
    data class Complete(val service: ServiceConfig) : Inspection
}

sealed interface PrepareResult {
    data class Ready(val reused: Boolean, val steps: List<String>) : PrepareResult
    data class Failed(val reason: FailureReason, val detail: String) : PrepareResult
}

sealed interface ClearResult {
    data object Cleared : ClearResult

    /** 目录本就不存在 —— 清除操作幂等（规格 §9.5「关闭开关后再次清除，操作幂等」）。 */
    data object AlreadyEmpty : ClearResult
    data class Failed(val detail: String) : ClearResult
}

enum class FailureReason {
    BINARY_MISSING,
    BINARY_NOT_EXECUTABLE,
    IO_ERROR,
}

/**
 * 免费通道运行时的**文件层**生命周期管理（规格 §9.5）。
 *
 * 职责边界（刻意划清，避免和进程管理搅在一起）：
 *  - 本类只管「目录、配置、service.json 的创建与清除」，**不**启动进程；
 *  - 端口只有进程起来后才存在，所以 service.json 由 [ProcessManager] 在拿到端口后
 *    通过 [writeService] 落盘；
 *  - 因此 [Inspection.Complete] 只代表「文件齐了」，不代表「服务器活着」。重启判定
 *    必须再走一次健康检查。
 *
 * 幂等性来自「先查后写」：任何一步都能重复执行而不产生副作用，进程被杀后重新进入
 * 也只会补做缺失的部分（断点续做）。
 */
class OpencodeRuntimeInstaller(
    private val layout: OpencodeLayout,
    private val fs: RuntimeFs = JavaRuntimeFs(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun inspect(): Inspection {
        val rootPath = layout.root.absolutePath
        if (!fs.exists(rootPath)) return Inspection.NotEnabled

        val missing = ArrayList<String>(4)
        layout.directories().forEach { dir ->
            if (!fs.exists(dir.absolutePath)) missing.add(relative(dir))
        }
        if (!fs.exists(layout.configFile.absolutePath)) {
            missing.add(relative(layout.configFile))
        } else {
            val cfg = fs.read(layout.configFile.absolutePath).orEmpty()
            if (!cfg.contains("\$schema")) missing.add("${relative(layout.configFile)}（内容无效）")
        }

        val service = readService()
        if (service == null) missing.add(relative(layout.serviceFile))

        return if (missing.isEmpty() && service != null) {
            Inspection.Complete(service)
        } else {
            Inspection.Partial(missing.distinct())
        }
    }

    /**
     * 确保运行时目录与最小配置就位。**不启动进程**。
     *
     * [binaryPath] 是 nativeLibraryDir 下 `libopencode.so` 的绝对路径。
     * 规格 §9.3 说明它由系统在安装时复制就位，所以这里只校验不复制；
     * 老设备（API < 29）才需要从 assets 解压，那条路在
     * [pro.openminis.app.ext.opencode.runtime.BinaryLocator] 里做降级。
     */
    fun prepare(binaryPath: String, personaInstruction: String? = null): PrepareResult {
        val steps = ArrayList<String>(4)

        if (!fs.exists(binaryPath)) {
            return PrepareResult.Failed(
                FailureReason.BINARY_MISSING,
                "可执行文件不存在：$binaryPath。请确认 APK 已包含 arm64-v8a/libopencode.so。",
            )
        }
        if (!fs.isExecutable(binaryPath)) {
            // 这是 API 29+ 上把 ELF 放 filesDir 的典型症状（规格 §9.3）
            return PrepareResult.Failed(
                FailureReason.BINARY_NOT_EXECUTABLE,
                "可执行文件没有执行权限：$binaryPath。请确认它位于 nativeLibraryDir，而不是 filesDir。",
            )
        }
        steps.add("verify-binary")

        try {
            layout.directories().forEach { dir ->
                if (!fs.exists(dir.absolutePath)) {
                    if (!fs.mkdirs(dir.absolutePath)) {
                        return PrepareResult.Failed(
                            FailureReason.IO_ERROR,
                            "无法创建目录：${relative(dir)}",
                        )
                    }
                }
            }
            steps.add("create-dirs")

            val current = fs.read(layout.configFile.absolutePath)
            val needWrite = current == null || !current.contains("\$schema")
            if (needWrite) {
                val instructions = personaInstruction?.let { writePersonaInstruction(it, steps) } ?: emptyList()
                fs.write(layout.configFile.absolutePath, OpencodeConfigWriter.minimal(instructions))
                steps.add("write-config")
            } else {
                steps.add("config-present")
            }
        } catch (e: Exception) {
            return PrepareResult.Failed(FailureReason.IO_ERROR, e.message ?: e.javaClass.simpleName)
        }

        // 「复用」的判据是配置文件已存在且有效，不是步骤数量：
        // 目可能仍需补建（上次被中断），但那不影响配置复用
        val reused = "config-present" in steps
        return PrepareResult.Ready(reused = reused, steps = steps)
    }

    /** 写入人设指令文件，返回其绝对路径（供 config 的 instructions 引用）。 */
    fun writePersonaInstruction(markdown: String, steps: MutableList<String>? = null): List<String> {
        val file = File(layout.instructionsDir, "persona.md")
        fs.write(file.absolutePath, markdown)
        steps?.add("write-persona")
        return listOf(file.absolutePath)
    }

    fun readService(): ServiceConfig? {
        val text = fs.read(layout.serviceFile.absolutePath) ?: return null
        return ServiceConfig.parse(text)
    }

    fun writeService(service: ServiceConfig) {
        fs.write(layout.serviceFile.absolutePath, service.toJson())
    }

    fun clearService() {
        fs.deleteRecursively(layout.serviceFile.absolutePath)
    }

    /**
     * 删除全部运行时产物（规格 §9.5「暂不启用」）。
     *
     * 明确一点：**nativeLibraryDir 里的 ELF 删不掉也不该删**（系统管理），
     * 所以 APK 体积不会因此变小。这里删的是配置、SQLite、会话快照、日志、临时目录
     * 以及 service.json，让下一次启用回到干净状态。
     */
    fun clear(): ClearResult {
        return try {
            if (!fs.exists(layout.root.absolutePath)) {
                ClearResult.AlreadyEmpty
            } else if (fs.deleteRecursively(layout.root.absolutePath)) {
                ClearResult.Cleared
            } else {
                ClearResult.Failed("目录删除失败：${layout.root.absolutePath}")
            }
        } catch (e: Exception) {
            ClearResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun newService(port: Int, pid: Long?, password: String): ServiceConfig =
        ServiceConfig.create(port, pid, clock(), password)

    /** 往 server.log 追一行说明。启动/认证这类「没抛异常但结果不对」的情况靠它留痕。 */
    fun appendServerNote(message: String) {
        runCatching {
            if (!layout.logsDir.exists()) layout.logsDir.mkdirs()
            layout.serverLog.appendText("[installer] $message\n")
        }
    }

    private fun relative(file: File): String =
        file.absolutePath.removePrefix(layout.root.absolutePath).trimStart('/').ifEmpty { "opencode/" }
}
