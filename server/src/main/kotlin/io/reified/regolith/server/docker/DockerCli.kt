package io.reified.regolith.server.docker

import io.reified.regolith.server.domain.RegolithError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The docker CLI, run as a child process with arguments passed as a list — never through a shell.
 *
 * Talking to the daemon through its CLI keeps the server free of a docker client library and lets
 * a compatible CLI such as podman stand in for it.
 */
class DockerCli(private val binary: String) {
    class Result(val exitCode: Int, val stdout: ByteArray, val stderr: String) {
        val ok: Boolean get() = exitCode == 0

        val text: String get() = stdout.decodeToString()
    }

    /**
     * Runs docker to completion. Stdout beyond [maxStdout] is read and discarded rather than left in
     * the pipe: a reader that stops early can deadlock the process writing to it.
     */
    suspend fun run(
        args: List<String>,
        input: ByteArray? = null,
        maxStdout: Int = DEFAULT_MAX_STDOUT,
        timeout: Duration = 2.minutes,
    ): Result =
        withContext(Dispatchers.IO) {
            val process = start(args, stdin = input != null)
            if (input != null) launch { process.outputStream.use { it.write(input) } }
            val out = async { drain(process.inputStream, maxStdout) }
            val err = async { drain(process.errorStream, MAX_STDERR) }
            val exited = withTimeoutOrNull(timeout) { process.onExit().await() }
            if (exited == null) {
                process.destroyForcibly()
                throw RegolithError.Unavailable("docker ${args.firstOrNull()} did not finish within $timeout")
            }
            Result(process.exitValue(), out.await(), err.await().decodeToString().trim())
        }

    /** Starts docker and hands the streams to the caller; with [stdin] unset the child reads nothing. */
    fun start(args: List<String>, stdin: Boolean): Process {
        val builder = ProcessBuilder(listOf(binary) + args)
        if (!stdin) builder.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))

        return builder.start()
    }

    private fun drain(stream: InputStream, max: Int): ByteArray {
        val kept = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        stream.use {
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                val room = max - kept.size()
                if (room > 0) kept.write(buffer, 0, minOf(room, count))
            }
        }

        return kept.toByteArray()
    }

    private companion object {
        const val DEFAULT_MAX_STDOUT = 4 * 1024 * 1024
        const val MAX_STDERR = 16 * 1024
    }
}

/** Throws unless docker exited with 0; the message carries docker's own explanation. */
fun DockerCli.Result.requireOk(what: String): DockerCli.Result {
    check(ok) { "$what failed (exit $exitCode): $stderr" }

    return this
}
