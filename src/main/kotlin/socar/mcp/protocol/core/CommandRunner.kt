package socar.mcp.protocol.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class CommandSpec(
    val argv: List<String>,
    val directory: Path,
    val timeout: Duration,
    val environment: Map<String, String> = emptyMap(),
    val acceptedExitCodes: Set<Int> = setOf(0),
)

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
)

class CommandFailure(
    val argv: List<String>,
    val exitCode: Int?,
    val timedOut: Boolean,
    val stdout: String,
    val stderr: String,
) : RuntimeException(
    buildString {
        append(if (timedOut) "Command timed out" else "Command failed")
        if (exitCode != null) append(" with exit code $exitCode")
        append(": ")
        append(argv.firstOrNull() ?: "<empty>")
    },
)

class CommandRunner(
    private val maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
) {
    init {
        require(maxOutputBytes in 1..ABSOLUTE_MAX_OUTPUT_BYTES) { "maxOutputBytes is outside the safe range" }
    }

    fun run(spec: CommandSpec): CommandResult {
        validate(spec)
        val processBuilder = ProcessBuilder(spec.argv)
            .directory(spec.directory.toFile())
            .redirectInput(ProcessBuilder.Redirect.PIPE)
        processBuilder.environment().putAll(spec.environment)
        sanitizeEnvironment(processBuilder.environment())

        val process = processBuilder.start()
        process.outputStream.close()
        val executor = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "protocol-codegen-output").apply { isDaemon = true }
        }
        val stdoutFuture = executor.submit<BoundedOutput> { process.inputStream.readBounded(maxOutputBytes) }
        val stderrFuture = executor.submit<BoundedOutput> { process.errorStream.readBounded(maxOutputBytes) }

        val completed = try {
            process.waitFor(spec.timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            terminate(process)
            Thread.currentThread().interrupt()
            throw interrupted
        }
        if (!completed) terminate(process)

        val stdout = getOutput(stdoutFuture, process.inputStream)
        val stderr = getOutput(stderrFuture, process.errorStream)
        executor.shutdownNow()
        val exitCode = if (process.isAlive) null else process.exitValue()

        if (!completed || exitCode == null || exitCode !in spec.acceptedExitCodes) {
            throw CommandFailure(
                argv = spec.argv.toList(),
                exitCode = exitCode,
                timedOut = !completed,
                stdout = stdout.text,
                stderr = stderr.text,
            )
        }
        return CommandResult(
            exitCode = exitCode,
            stdout = stdout.text,
            stderr = stderr.text,
            stdoutTruncated = stdout.truncated,
            stderrTruncated = stderr.truncated,
        )
    }

    private fun validate(spec: CommandSpec) {
        require(spec.argv.isNotEmpty()) { "Command argv must not be empty" }
        require(spec.argv.none { it.contains('\u0000') }) { "Command argv contains a NUL byte" }
        require(!spec.timeout.isZero && !spec.timeout.isNegative) { "Command timeout must be positive" }
        require(Files.isDirectory(spec.directory)) { "Command directory does not exist: ${spec.directory}" }
        require(spec.environment.keys.none { it.contains('=') || it.contains('\u0000') }) {
            "Environment contains an invalid key"
        }
        require(spec.environment.values.none { it.contains('\u0000') }) { "Environment contains a NUL byte" }
    }

    private fun sanitizeEnvironment(environment: MutableMap<String, String>) {
        val dangerousNames = setOf(
            "BASH_ENV",
            "CDPATH",
            "DYLD_INSERT_LIBRARIES",
            "ENV",
            "GIT_ALTERNATE_OBJECT_DIRECTORIES",
            "GIT_ASKPASS",
            "GIT_CEILING_DIRECTORIES",
            "GIT_COMMON_DIR",
            "GIT_CONFIG",
            "GIT_CONFIG_COUNT",
            "GIT_DIR",
            "GIT_DISCOVERY_ACROSS_FILESYSTEM",
            "GIT_EXEC_PATH",
            "GIT_INDEX_FILE",
            "GIT_NAMESPACE",
            "GIT_OBJECT_DIRECTORY",
            "GIT_SSH",
            "GIT_SSH_COMMAND",
            "GIT_WORK_TREE",
            "LD_PRELOAD",
        )
        environment.keys
            .filter { key ->
                key in dangerousNames ||
                    key.startsWith("GIT_CONFIG_KEY_") ||
                    key.startsWith("GIT_CONFIG_VALUE_")
            }
            .forEach(environment::remove)
        environment["GIT_TERMINAL_PROMPT"] = "0"
    }

    private fun terminate(process: Process) {
        process.toHandle().descendants().forEach { it.destroy() }
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.toHandle().descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
    }

    private fun getOutput(
        future: java.util.concurrent.Future<BoundedOutput>,
        stream: InputStream,
    ): BoundedOutput = try {
        future.get(5, TimeUnit.SECONDS)
    } catch (error: Exception) {
        stream.close()
        future.cancel(true)
        BoundedOutput("<output unavailable: ${error.javaClass.simpleName}>", truncated = true)
    }

    private fun InputStream.readBounded(limit: Int): BoundedOutput = use { input ->
        val kept = ByteArrayOutputStream(limit.coerceAtMost(8192))
        val buffer = ByteArray(8192)
        var truncated = false
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            val remaining = limit - kept.size()
            if (remaining > 0) kept.write(buffer, 0, read.coerceAtMost(remaining))
            if (read > remaining) truncated = true
        }
        val suffix = if (truncated) "\n<output truncated>" else ""
        BoundedOutput(kept.toString(Charsets.UTF_8) + suffix, truncated)
    }

    private data class BoundedOutput(val text: String, val truncated: Boolean)

    companion object {
        const val DEFAULT_MAX_OUTPUT_BYTES: Int = 256 * 1024
        const val ABSOLUTE_MAX_OUTPUT_BYTES: Int = 1024 * 1024
    }
}
