package io.reified.regolith.koog

import ai.koog.agents.ext.tool.shell.ShellCommandExecutor
import io.reified.regolith.protocol.ExecRequest
import io.reified.regolith.sdk.Sandbox

/**
 * Runs Koog's shell tool inside a Regolith sandbox instead of on the machine running the agent.
 *
 * Paths the model passes as `workingDirectory` are paths inside the sandbox; a relative one resolves
 * against the sandbox home. Koog's file tools still act on the host, so give an agent this executor
 * together with file tools that also go through the sandbox, or its files and its commands will not
 * see each other.
 *
 * Output keeps its beginning and its end when it exceeds [maxOutputChars], because a failing build
 * explains itself in its last lines.
 */
public class RegolithShellCommandExecutor(
    private val sandbox: Sandbox,
    private val maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
) : ShellCommandExecutor {

    init {
        require(maxOutputChars >= MIN_OUTPUT_CHARS) { "maxOutputChars must be at least $MIN_OUTPUT_CHARS" }
    }

    override suspend fun execute(command: String, workingDirectory: String?, timeoutSeconds: Int): ShellCommandExecutor.ExecutionResult {
        val result = sandbox.run(ExecRequest(shell = command, cwd = workingDirectory, timeoutSeconds = timeoutSeconds))

        return ShellCommandExecutor.ExecutionResult(
            output = headAndTail(result.combined, maxOutputChars, result.truncated),
            exitCode = result.exitCode,
        )
    }

    public companion object {
        public const val DEFAULT_MAX_OUTPUT_CHARS: Int = 32_000
        private const val MIN_OUTPUT_CHARS = 1_000

        internal fun headAndTail(text: String, max: Int, alreadyTruncated: Boolean): String {
            if (text.length <= max) return if (alreadyTruncated) "$text\n[output truncated]" else text
            val half = (max - MARKER.length) / 2

            return text.take(half) + MARKER + text.takeLast(half)
        }

        private const val MARKER = "\n[... output truncated ...]\n"
    }
}
