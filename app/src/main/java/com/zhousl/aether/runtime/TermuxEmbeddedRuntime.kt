package com.zhousl.aether.runtime

import android.content.Context
import android.system.Os
import com.zhousl.aether.data.AetherDiagnosticLogger
import com.zhousl.aether.data.LocalRuntimeId
import com.zhousl.aether.data.TermuxEnvironmentVariable
import com.zhousl.aether.data.normalizeTermuxEnvironmentVariables
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TermuxWatchWindowMillis = 45_000L
private const val TermuxDefaultTailBytes = 12 * 1024
private const val TermuxMaxTailBytes = 64 * 1024
private const val TermuxHostLinker = "/system/bin/linker64"
private const val TermuxBootstrapAsset = "runtimes/termux/bootstrap-aarch64.zip"
private const val TermuxProotAssetRoot = "runtimes/alpine/arm64-v8a"
private const val TermuxGuestPrefix = "/data/data/com.termux/files/usr"
private const val TermuxGuestHome = "/data/data/com.termux/files/home"
private const val TermuxGuestBash = "$TermuxGuestPrefix/bin/bash"

/**
 * Guest path the embedded Termux workspace is mounted at.
 *
 * It is deliberately not the external Termux workspace
 * (`$TermuxGuestHome/.aether/workspace`), so a workspace path on its own is
 * enough to tell the two Termux runtimes apart instead of guessing.
 */
internal const val EmbeddedTermuxWorkspaceRootGuestPath = "$TermuxGuestHome/workspace"

private const val TermuxSecondStageScript =
    "$TermuxGuestPrefix/etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh"
private val TermuxMirrorSources = """
    # Qing: China mirrors (Tsinghua primary, USTC fallback)
    deb https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/ stable main
    deb https://mirrors.ustc.edu.cn/termux/apt/termux-main/ stable main
""".trimIndent() + "\n"

class TermuxEmbeddedRuntime(
    private val context: Context,
    private val diagnosticLogger: AetherDiagnosticLogger = AetherDiagnosticLogger.NoOp,
) : LocalRuntime {
    private val appContext = context.applicationContext
    private val runtimeRoot = File(appContext.filesDir, "runtimes/termux")
    private val stagingRoot = File(runtimeRoot.parentFile, "termux-installing")
    private val rootfsDir = File(runtimeRoot, "data/data/com.termux/files")
    private val usrDir = File(rootfsDir, "usr")
    private val homeHostDir = File(rootfsDir, "home")
    private val workspaceDir = File(runtimeRoot, "workspace")
    private val hostTmpDir = File(runtimeRoot, "tmp")
    private val hostProotDir = File(runtimeRoot, "host")
    private val prootFile = File(hostProotDir, "bin/proot")
    private val loaderFile = File(hostProotDir, "libexec/proot/loader")
    private val hostLibDir = File(hostProotDir, "lib")
    private val runs = ConcurrentHashMap<String, TermuxEmbeddedRun>()
    private val nextRunId = AtomicInteger(1)
    private val packageRefreshMutex = Mutex()
    @Volatile
    private var environmentVariables: List<TermuxEnvironmentVariable> = emptyList()

    override val id: LocalRuntimeId = LocalRuntimeId.EmbeddedTermux
    override val displayName: String = "内嵌 Termux"
    override val homeDirectory: String = TermuxGuestHome
    override val workspaceRoot: String = EmbeddedTermuxWorkspaceRootGuestPath
    override val managedCommandsDirectory: String = "$TermuxGuestHome/.aether/bash-runs"

    fun setEnvironmentVariables(variables: List<TermuxEnvironmentVariable>) {
        environmentVariables = normalizeTermuxEnvironmentVariables(variables)
    }

    suspend fun initialize(
        onProgress: (TermuxSetupProgress) -> Unit = {},
    ): LocalRuntimeSetupState = withContext(Dispatchers.IO) {
        inspectSetup().let { state ->
            if (state.issue != LocalRuntimeIssue.NotInstalled && state.issue != LocalRuntimeIssue.Failed) {
                return@withContext state
            }
        }
        if (!isSupportedAbi()) return@withContext unsupportedAbiState()
        if (!hasBundledAssets()) {
            return@withContext LocalRuntimeSetupState(
                runtimeId = id,
                issue = LocalRuntimeIssue.MissingAssets,
                detail = "Termux bootstrap assets are not bundled in this build.",
            )
        }
        runCatching {
            installFromAssets(onProgress)
        }.fold(
            onSuccess = {
                inspectSetup().let { state ->
                    if (state.isReady) state.copy(detail = "内嵌 Termux 运行时已就绪。") else state
                }
            },
            onFailure = { throwable ->
                diagnosticLogger.exception("termux_embedded", "install_failed", throwable)
                LocalRuntimeSetupState(
                    runtimeId = id,
                    issue = LocalRuntimeIssue.Failed,
                    detail = throwable.message ?: "Failed to install embedded Termux runtime.",
                )
            },
        )
    }

    suspend fun reset(): LocalRuntimeSetupState = withContext(Dispatchers.IO) {
        runs.values.forEach { run ->
            run.process?.takeIf(Process::isAlive)?.let { process ->
                run.cancelled = true
                runCatching { process.destroy() }
                if (!process.waitFor(800, TimeUnit.MILLISECONDS)) {
                    runCatching { process.destroyForcibly() }
                }
            }
        }
        runs.clear()
        check(!runtimeRoot.exists() || runtimeRoot.deleteRecursively()) {
            "Unable to reset embedded Termux runtime data."
        }
        check(!stagingRoot.exists() || stagingRoot.deleteRecursively()) {
            "Unable to reset incomplete embedded Termux installation data."
        }
        LocalRuntimeSetupState(
            runtimeId = id,
            issue = LocalRuntimeIssue.NotInstalled,
            detail = "内嵌 Termux 数据已重置。",
        )
    }

    override suspend fun inspectSetup(): LocalRuntimeSetupState = withContext(Dispatchers.IO) {
        when {
            !isSupportedAbi() -> unsupportedAbiState()
            !hasBundledAssets() -> LocalRuntimeSetupState(
                runtimeId = id,
                issue = LocalRuntimeIssue.MissingAssets,
                detail = "This build does not include the embedded Termux bootstrap.",
            )
            !usrDir.isDirectory -> LocalRuntimeSetupState(
                runtimeId = id,
                issue = LocalRuntimeIssue.NotInstalled,
                detail = "内嵌 Termux 尚未安装。",
            )
            !File(usrDir, "bin/bash").exists() -> LocalRuntimeSetupState(
                runtimeId = id,
                issue = LocalRuntimeIssue.Failed,
                detail = "内嵌 Termux 安装不完整：缺少 bin/bash。",
            )
            !prootFile.isFile || !loaderFile.isFile -> LocalRuntimeSetupState(
                runtimeId = id,
                issue = LocalRuntimeIssue.Failed,
                detail = "内嵌 Termux 宿主运行时缺失。",
            )
            !File(usrDir, "etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh.lock").exists() ->
                LocalRuntimeSetupState(
                    runtimeId = id,
                    issue = LocalRuntimeIssue.Failed,
                    detail = "内嵌 Termux 未完成初始化（bootstrap 第二阶段未执行）。",
                )
            else -> LocalRuntimeSetupState(id, LocalRuntimeIssue.Ready)
        }
    }
    override suspend fun execute(
        argumentsJson: String,
        onProgress: (suspend (String) -> Unit)?,
    ): String = withContext(Dispatchers.IO) {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return@withContext invalidArguments("Arguments were not valid JSON.")
        val command = arguments.optString("command").trim()
        val workingDirectory = normalizePath(
            arguments.optString("working_directory").trim()
                .ifBlank { arguments.optString("workingDirectory").trim() }
                .ifBlank { homeDirectory }
        )
        if (command.isBlank()) return@withContext invalidArguments("Missing required 'command' argument.")

        val setup = inspectSetup()
        if (!setup.isReady) return@withContext setupError(command, workingDirectory, setup)

        ensureWorkspace()
        val runId = nextRunId.getAndIncrement()
        val runDir = File(runtimeRoot, "runs/$runId").apply { mkdirs() }
        val stdoutFile = File(runDir, "stdout.log")
        val stderrFile = File(runDir, "stderr.log")
        val run = TermuxEmbeddedRun(
            runId = runId,
            command = command,
            workingDirectory = workingDirectory,
            startedAtMillis = System.currentTimeMillis(),
            stdoutFile = stdoutFile,
            stderrFile = stderrFile,
        )
        runs[runId.toString()] = run

        val process = runCatching {
            buildTermuxProcess(command, workingDirectory)
                .redirectOutput(stdoutFile)
                .redirectError(stderrFile)
                .start()
        }.getOrElse { throwable ->
            runs.remove(runId.toString())
            return@withContext commandError(
                command = command,
                workingDirectory = workingDirectory,
                runId = runId,
                message = throwable.message ?: "Failed to start embedded Termux command.",
            )
        }
        run.process = process
        TermuxEmbeddedRunReaper.watch(run, runs)

        try {
            val deadline = System.currentTimeMillis() + TermuxWatchWindowMillis
            while (System.currentTimeMillis() < deadline && process.isAlive) {
                delay(1_000L)
                onProgress?.invoke(snapshot(run, TermuxDefaultTailBytes))
            }
            snapshot(run, TermuxDefaultTailBytes)
        } catch (cancellationException: CancellationException) {
            runCatching { killExecutionByRunId(runId.toString()) }
            throw cancellationException
        }
    }

    override suspend fun fetchExecution(argumentsJson: String): String = withContext(Dispatchers.IO) {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return@withContext invalidArguments("Arguments were not valid JSON.")
        val runId = arguments.optString("run_id").trim()
            .ifBlank { arguments.optString("runId").trim() }
        if (runId.isBlank()) return@withContext invalidArguments("Missing required 'run_id' argument.")
        val tailBytes = resolveTailBytes(arguments)
        runs[runId]?.let { return@withContext snapshot(it, tailBytes) }
        invalidArguments("Unknown run_id '$runId'.")
    }

    override suspend fun killExecution(argumentsJson: String): String = withContext(Dispatchers.IO) {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return@withContext invalidArguments("Arguments were not valid JSON.")
        val runId = arguments.optString("run_id").trim()
            .ifBlank { arguments.optString("runId").trim() }
        if (runId.isBlank()) return@withContext invalidArguments("Missing required 'run_id' argument.")
        val tailBytes = resolveTailBytes(arguments)
        runs[runId]?.let { run ->
            run.process?.takeIf(Process::isAlive)?.let { process ->
                run.cancelled = true
                runCatching { process.destroy() }
                if (!process.waitFor(800, TimeUnit.MILLISECONDS)) {
                    runCatching { process.destroyForcibly() }
                }
            }
            return@withContext snapshot(run, tailBytes)
        }
        invalidArguments("Unknown run_id '$runId'.")
    }

    override suspend fun killExecutionByRunId(
        runId: String,
        tailBytes: Int,
    ): String = withContext(Dispatchers.IO) {
        runs[runId]?.let { run ->
            run.process?.takeIf(Process::isAlive)?.let { process ->
                run.cancelled = true
                runCatching { process.destroy() }
                if (!process.waitFor(800, TimeUnit.MILLISECONDS)) {
                    runCatching { process.destroyForcibly() }
                }
            }
            return@withContext snapshot(run, tailBytes)
        }
        invalidArguments("Unknown run_id '$runId'.")
    }

    override suspend fun executeCommand(
        command: String,
        workingDirectory: String,
        awaitTimeoutMillis: Long,
    ): String = withContext(Dispatchers.IO) {
        val normalizedWorkingDirectory = normalizePath(workingDirectory)
        val setup = inspectSetup()
        if (!setup.isReady) return@withContext setupError(command, normalizedWorkingDirectory, setup)
        ensureWorkspace()
        val startedAtMillis = System.currentTimeMillis()
        val stdoutFile = File.createTempFile("aether-termux-stdout", ".log", runtimeRoot)
        val stderrFile = File.createTempFile("aether-termux-stderr", ".log", runtimeRoot)
        val process = runCatching {
            buildTermuxProcess(command, normalizedWorkingDirectory).apply {
                redirectOutput(stdoutFile)
                redirectError(stderrFile)
            }.start()
        }.getOrElse { throwable ->
            return@withContext commandError(
                command = command,
                workingDirectory = normalizedWorkingDirectory,
                message = throwable.message ?: "Failed to start embedded Termux command.",
            )
        }
        val finished = process.waitFor(awaitTimeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            runCatching { process.destroy() }
            if (!process.waitFor(800, TimeUnit.MILLISECONDS)) {
                runCatching { process.destroyForcibly() }
            }
        }
        val stdout = stdoutFile.readTextSafe()
        val stderr = stderrFile.readTextSafe()
        stdoutFile.delete()
        stderrFile.delete()
        JSONObject().apply {
            put("ok", finished && process.exitValueSafe() == 0)
            put("command", command)
            put("working_directory", normalizedWorkingDirectory)
            put("duration_ms", System.currentTimeMillis() - startedAtMillis)
            put("stdout", stdout)
            put("stderr", stderr)
            put("exit_code", if (finished) process.exitValueSafe() else -1)
            put("err", if (finished) -1 else -2)
            put("errmsg", if (finished) "" else "Timed out waiting for embedded Termux to reply.")
        }.toString()
    }

    suspend fun createTerminalLaunchSpec(): TermuxEmbeddedTerminalLaunchSpec = withContext(Dispatchers.IO) {
        requireReady()
        ensureWorkspace()
        val command = listOf(
            TermuxHostLinker,
            prootFile.absolutePath,
            "-r",
            runtimeRoot.absolutePath,
            "-b",
            "${workspaceDir.absolutePath}:$workspaceRoot",
            "-b",
            "/system",
            "-b",
            "/apex",
            "-b",
            "/dev",
            "-b",
            "/proc",
            "-b",
            "/sys",
            "-w",
            homeDirectory,
            TermuxGuestBash,
            "-i",
        )
        TermuxEmbeddedTerminalLaunchSpec(
            executable = TermuxHostLinker,
            arguments = command.toTypedArray(),
            environment = buildTermuxProcessEnvironment()
                .map { (key, value) -> "$key=$value" }
                .toTypedArray(),
            workingDirectory = runtimeRoot.absolutePath,
        )
    }

    suspend fun refreshPackageMirrors(
        onProgress: (TermuxSetupProgress) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        packageRefreshMutex.lock()
        try {
            if (inspectSetup().isReady) {
                configureAptMirrors()
                runAptUpdate(onProgress)
            }
        } finally {
            packageRefreshMutex.unlock()
        }
    }

    internal fun resolveWorkspaceHostPath(
        path: String,
        workingDirectory: String = workspaceRoot,
    ): TermuxEmbeddedWorkspaceHostPath? {
        val normalizedWorkingDirectory = normalizePath(workingDirectory.trim())
            .ifBlank { workspaceRoot }
        val normalizedGuestPath = when {
            path.isBlank() -> normalizedWorkingDirectory
            path.startsWith("/") -> java.nio.file.Paths.get(path).normalize().toString()
            else -> java.nio.file.Paths.get(normalizedWorkingDirectory).resolve(path).normalize().toString()
        }
        if (
            normalizedGuestPath != workspaceRoot &&
            !normalizedGuestPath.startsWith("$workspaceRoot/")
        ) {
            return null
        }
        val relativePath = normalizedGuestPath.removePrefix(workspaceRoot).trimStart('/')
        val canonicalWorkspace = workspaceDir.canonicalFile
        val hostFile = File(canonicalWorkspace, relativePath).canonicalFile
        if (
            hostFile != canonicalWorkspace &&
            !hostFile.path.startsWith("${canonicalWorkspace.path}${File.separator}")
        ) {
            return null
        }
        return TermuxEmbeddedWorkspaceHostPath(
            guestPath = normalizedGuestPath,
            hostFile = hostFile,
        )
    }

    private suspend fun requireReady() {
        val state = inspectSetup()
        check(state.isReady) { state.detail.ifBlank { "内嵌 Termux 未就绪。" } }
    }

    private fun buildTermuxProcess(
        command: String,
        workingDirectory: String,
    ): ProcessBuilder {
        val prootCommand = listOf(
            TermuxHostLinker,
            prootFile.absolutePath,
            "-r",
            runtimeRoot.absolutePath,
            "-b",
            "${workspaceDir.absolutePath}:$workspaceRoot",
            "-b",
            "/system",
            "-b",
            "/apex",
            "-b",
            "/dev",
            "-b",
            "/proc",
            "-b",
            "/sys",
            "-w",
            workingDirectory,
            TermuxGuestBash,
            "-lc",
            command,
        )
        return ProcessBuilder(prootCommand).apply {
            directory(runtimeRoot)
            environment().putAll(buildTermuxProcessEnvironment())
        }
    }

    private fun buildTermuxProcessEnvironment(): Map<String, String> =
        buildMap {
            put("PREFIX", TermuxGuestPrefix)
            put("HOME", TermuxGuestHome)
            put("TMPDIR", "$TermuxGuestPrefix/tmp")
            put("PATH", "$TermuxGuestPrefix/bin")
            put("AETHER_RUNTIME", id.storageValue)
            put("AETHER_HOST_WORKSPACE", workspaceDir.absolutePath)
            put("PROOT_ROOTFS", runtimeRoot.absolutePath)
            put("PROOT_BIN", prootFile.absolutePath)
            put("PROOT_LOADER", loaderFile.absolutePath)
            put("PROOT_TMP_DIR", hostTmpDir.absolutePath)
            put("LD_LIBRARY_PATH", hostLibDir.absolutePath)
            put("TERM", "xterm-256color")
            put("PS1", "qing-termux:\\w$ ")
        }.toMutableMap().also { environment ->
            environmentVariables.forEach { variable ->
                environment[variable.name] = variable.value
            }
        }

    private fun isSupportedAbi(): Boolean =
        android.os.Build.SUPPORTED_ABIS.any { abi ->
            abi == "arm64-v8a" || abi == "aarch64"
        }

    private fun unsupportedAbiState(): LocalRuntimeSetupState =
        LocalRuntimeSetupState(
            runtimeId = id,
            issue = LocalRuntimeIssue.UnsupportedAbi,
            detail = "内嵌 Termux 仅支持 64 位 ARM 设备（arm64-v8a）。",
        )

    private fun hasBundledAssets(): Boolean =
        assetExists(TermuxBootstrapAsset) &&
            assetExists("$TermuxProotAssetRoot/proot.bin") &&
            assetExists("$TermuxProotAssetRoot/loader.bin") &&
            assetExists("$TermuxProotAssetRoot/libtalloc.so.2")

    private fun assetExists(path: String): Boolean =
        runCatching { appContext.assets.open(path).use { true } }.getOrDefault(false)

    private suspend fun installFromAssets(
        onProgress: (TermuxSetupProgress) -> Unit,
    ) {
        onProgress(TermuxSetupProgress(output = "准备内嵌 Termux 文件...\n"))
        stagingRoot.apply {
            deleteRecursively()
            mkdirs()
        }
        val stagingUsr = File(stagingRoot, "data/data/com.termux/files/usr").apply { mkdirs() }
        val stagingProot = File(stagingRoot, "host").apply { mkdirs() }

        onProgress(TermuxSetupProgress(output = "复制宿主 proot 运行时...\n"))
        copyAsset("$TermuxProotAssetRoot/proot.bin", File(stagingProot, "bin/proot"), executable = true)
        copyAsset("$TermuxProotAssetRoot/loader.bin", File(stagingProot, "libexec/proot/loader"), executable = true)
        copyAsset("$TermuxProotAssetRoot/libtalloc.so.2", File(stagingProot, "lib/libtalloc.so.2"), executable = false)

        onProgress(
            TermuxSetupProgress(
                activity = TermuxSetupActivity.Extracting,
                output = "解压 Termux bootstrap（约 70MB，需 1-2 分钟）...\n",
            )
        )
        extractBootstrap(stagingUsr)
        File(stagingRoot, ".installed-version").writeText("termux-bootstrap-2026.02.12-r1+apt.android-7\n")

        runtimeRoot.deleteRecursively()
        if (!stagingRoot.renameTo(runtimeRoot)) {
            copyDirectory(stagingRoot, runtimeRoot)
            stagingRoot.deleteRecursively()
        }

        onProgress(TermuxSetupProgress(output = "创建运行时目录...\n"))
        createRuntimeDirectories()
        onProgress(TermuxSetupProgress(output = "运行 bootstrap 第二阶段（配置软件包）...\n"))
        runSecondStage()
        configureAptMirrors()

        onProgress(
            TermuxSetupProgress(
                activity = TermuxSetupActivity.UpdatingIndexes,
                output = "同步软件包索引（首次较慢）...\n",
            )
        )
        runAptUpdate(onProgress)
        onProgress(TermuxSetupProgress(output = "内嵌 Termux 安装完成。\n"))
    }

    private fun createRuntimeDirectories() {
        homeHostDir.mkdirs()
        File(homeHostDir, "workspace").mkdirs()
        File(usrDir, "tmp").mkdirs()
        File(usrDir, "var/lib/apt/lists").mkdirs()
        File(usrDir, "var/cache/apt/archives/partial").mkdirs()
        File(runtimeRoot, "data/data/com.termux/cache/apt/archives/partial").mkdirs()
        File(runtimeRoot, "linkerconfig").mkdirs()
        File(runtimeRoot, "linkerconfig/ld.config.txt").writeText("")
        workspaceDir.mkdirs()
        hostTmpDir.mkdirs()
    }

    private fun extractBootstrap(stagingUsr: File) {
        val symlinks = mutableListOf<Pair<String, String>>()
        appContext.assets.open(TermuxBootstrapAsset).use { input ->
            java.util.zip.ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name == "SYMLINKS.txt") {
                        val reader = zip.bufferedReader(Charsets.UTF_8)
                        var line = reader.readLine()
                        while (line != null) {
                            val parts = line.split('\u2190')
                            if (parts.size == 2) {
                                symlinks.add(parts[0] to parts[1])
                            }
                            line = reader.readLine()
                        }
                    } else {
                        val target = File(stagingUsr, name)
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { output -> zip.copyTo(output) }
                            val needsExec = name.startsWith("bin/") ||
                                name.startsWith("libexec") ||
                                name.startsWith("lib/apt/apt-helper") ||
                                name.startsWith("lib/apt/methods") ||
                                name.startsWith("etc/termux/termux-bootstrap") ||
                                name.startsWith("var/lib/dpkg/info")
                            target.setReadable(true, true)
                            target.setWritable(true, true)
                            if (needsExec) {
                                target.setExecutable(true, true)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        if (symlinks.isEmpty()) error("Bootstrap SYMLINKS.txt was empty or missing.")
        symlinks.forEach { (target, linkPath) ->
            val normalizedLink = linkPath.trim().removePrefix("./")
            val linkFile = File(stagingUsr, normalizedLink)
            val resolvedTarget = when {
                target.startsWith("/data/data/com.termux/files/usr/") -> {
                    val usrRelative = target.removePrefix("/data/data/com.termux/files/usr/")
                    val targetFile = File(stagingUsr, usrRelative)
                    val parent = linkFile.parentFile
                    if (parent == null || parent.absolutePath == stagingUsr.absolutePath) {
                        usrRelative
                    } else {
                        runCatching { targetFile.toRelativeString(parent) }.getOrDefault(usrRelative)
                    }
                }
                else -> target
            }
            linkFile.parentFile?.mkdirs()
            runCatching {
                if (linkFile.exists()) linkFile.delete()
                Os.symlink(resolvedTarget, linkFile.absolutePath)
            }.onFailure { error ->
                diagnosticLogger.exception("termux_embedded", "symlink_failed", error)
            }
        }
    }

    private fun runSecondStage() {
        val command = listOf(
            TermuxHostLinker,
            prootFile.absolutePath,
            "-r",
            runtimeRoot.absolutePath,
            "-b",
            "/system",
            "-b",
            "/apex",
            "-b",
            "/dev",
            "-b",
            "/proc",
            "-b",
            "/sys",
            "-w",
            TermuxGuestHome,
            TermuxGuestBash,
            "-c",
            "TERMUX__UID=${android.os.Process.myUid()} $TermuxGuestBash $TermuxSecondStageScript",
        )
        val process = ProcessBuilder(command).apply {
            directory(runtimeRoot)
            environment().putAll(buildTermuxProcessEnvironment())
            redirectErrorStream(true)
        }.start()
        val output = StringBuilder()
        process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(4096)
            while (true) {
                val read = reader.read(buffer)
                if (read == -1) break
                if (read > 0) {
                    output.append(buffer, 0, read)
                    if (output.length > 32_000) output.delete(0, output.length - 32_000)
                }
            }
        }
        val finished = process.waitFor(300, TimeUnit.SECONDS)
        if (!finished) {
            runCatching { process.destroyForcibly() }
            error("Bootstrap 第二阶段超时。")
        }
        if (process.exitValue() != 0) {
            error("Bootstrap 第二阶段失败（exit=${process.exitValue()}）：\n${output.takeLast(2000)}")
        }
    }

    private fun configureAptMirrors() {
        val sourcesFile = File(usrDir, "etc/apt/sources.list")
        if (!sourcesFile.isFile) return
        val original = runCatching { sourcesFile.readText() }.getOrNull() ?: return
        if (original != TermuxMirrorSources) {
            runCatching { sourcesFile.writeText(TermuxMirrorSources) }
        }
    }

    private suspend fun runAptUpdate(
        onProgress: (TermuxSetupProgress) -> Unit = {},
    ) {
        val result = executeCommand(
            command = "apt update 2>&1",
            workingDirectory = TermuxGuestHome,
            awaitTimeoutMillis = 240_000L,
        )
        val json = runCatching { JSONObject(result) }.getOrNull()
        val detail = buildString {
            append(json?.optString("stdout").orEmpty())
            append(json?.optString("stderr").orEmpty())
        }.trim()
        onProgress(
            TermuxSetupProgress(
                output = detail.takeLast(1500).ifBlank { "apt update 完成。" } + "\n",
            )
        )
    }

    private fun ensureWorkspace() {
        workspaceDir.mkdirs()
    }
    private fun snapshot(
        run: TermuxEmbeddedRun,
        requestedTailBytes: Int,
    ): String {
        val tailBytes = requestedTailBytes.coerceIn(0, TermuxMaxTailBytes)
        val stdout = run.stdoutFile.takeIf(File::isFile)?.readTailSafe(tailBytes).orEmpty()
        val stderr = run.stderrFile.takeIf(File::isFile)?.readTailSafe(tailBytes).orEmpty()
        val running = run.process?.isAlive ?: false
        val exitCode = if (running) null else run.process?.exitValueSafe()
        if (!running) {
            runs.remove(run.runId.toString())
        }
        return JSONObject().apply {
            put("ok", !running && exitCode == 0)
            put("command", run.command)
            put("working_directory", run.workingDirectory)
            put("duration_ms", System.currentTimeMillis() - run.startedAtMillis)
            put("stdout", stdout)
            put("stderr", stderr)
            put("exit_code", exitCode ?: -1)
            put("err", if (running) -3 else if (exitCode == 0) -1 else 1)
            put("errmsg", when {
                running -> "Command is still running."
                exitCode == 0 -> ""
                else -> "Command exited with code $exitCode."
            })
        }.toString()
    }

    private fun invalidArguments(message: String): String =
        JSONObject().apply {
            put("ok", false)
            put("err", -1)
            put("errmsg", message)
        }.toString()

    private fun setupError(command: String, workingDirectory: String, setup: LocalRuntimeSetupState): String =
        JSONObject().apply {
            put("ok", false)
            put("command", command)
            put("working_directory", workingDirectory)
            put("err", 1)
            put("errmsg", setup.detail.ifBlank { "内嵌 Termux 未就绪。" })
        }.toString()

    private fun commandError(
        command: String,
        workingDirectory: String,
        runId: Int,
        message: String,
    ): String = JSONObject().apply {
        put("ok", false)
        put("command", command)
        put("working_directory", workingDirectory)
        put("run_id", runId)
        put("err", 1)
        put("errmsg", message)
    }.toString()

    private fun commandError(
        command: String,
        workingDirectory: String,
        message: String,
    ): String = JSONObject().apply {
        put("ok", false)
        put("command", command)
        put("working_directory", workingDirectory)
        put("err", 1)
        put("errmsg", message)
    }.toString()

    private fun resolveTailBytes(arguments: JSONObject): Int {
        val tailBytes = arguments.optInt("tail_bytes", -1)
            .takeIf { it >= 0 }
            ?: arguments.optInt("tailBytes", -1)
            .takeIf { it >= 0 }
            ?: TermuxDefaultTailBytes
        return tailBytes.coerceIn(0, TermuxMaxTailBytes)
    }

    private fun copyAsset(
        assetPath: String,
        target: File,
        executable: Boolean,
    ) {
        target.parentFile?.mkdirs()
        appContext.assets.open(assetPath).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        target.setReadable(true, true)
        target.setWritable(true, true)
        if (executable) target.setExecutable(true, true)
    }

    private fun copyDirectory(source: File, target: File) {
        source.copyRecursively(target, overwrite = true)
    }
}

private fun File.readTextSafe(): String =
    runCatching { readText(Charsets.UTF_8) }.getOrDefault("")

private fun File.readTailSafe(maxBytes: Int): String =
    runCatching {
        if (!isFile || length() == 0L) return@runCatching ""
        val skip = (length() - maxBytes).coerceAtLeast(0L)
        inputStream().use { stream ->
            stream.skip(skip)
            stream.readBytes().toString(Charsets.UTF_8)
        }
    }.getOrDefault("")

private fun Process.exitValueSafe(): Int =
    runCatching { exitValue() }.getOrDefault(-1)

data class TermuxEmbeddedWorkspaceHostPath(
    val guestPath: String,
    val hostFile: File,
)

data class TermuxEmbeddedTerminalLaunchSpec(
    val executable: String,
    val arguments: Array<String>,
    val environment: Array<String>,
    val workingDirectory: String,
)

enum class TermuxSetupActivity {
    None,
    Extracting,
    UpdatingIndexes,
}

data class TermuxSetupProgress(
    val activity: TermuxSetupActivity = TermuxSetupActivity.None,
    val bytesPerSecond: Long = 0L,
    val progressPercent: Int? = null,
    val output: String = "",
)

private class TermuxEmbeddedRun(
    val runId: Int,
    val command: String,
    val workingDirectory: String,
    val startedAtMillis: Long,
    val stdoutFile: File,
    val stderrFile: File,
) {
    @Volatile
    var process: Process? = null
    @Volatile
    var cancelled: Boolean = false
}

private object TermuxEmbeddedRunReaper {
    fun watch(run: TermuxEmbeddedRun, runs: ConcurrentHashMap<String, TermuxEmbeddedRun>) {
        val thread = Thread(
            {
                runCatching {
                    run.process?.waitFor()
                }
                if (!run.cancelled) {
                    runs.remove(run.runId.toString())
                }
            },
            "aether-termux-run-reaper-${run.runId}",
        ).apply {
            isDaemon = true
            start()
        }
    }
}

