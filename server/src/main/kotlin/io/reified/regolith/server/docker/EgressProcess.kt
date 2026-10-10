package io.reified.regolith.server.docker

import io.github.oshai.kotlinlogging.KotlinLogging
import io.reified.regolith.egress.EgressSandbox
import io.reified.regolith.egress.EgressTable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * The running egress proxy, driven over its stdin. Every table goes in whole and counts as in force only
 * once the proxy has acknowledged it; what else the proxy prints is a refusal, which goes to this server's
 * log. When the proxy exits, everything still waiting on it fails at once.
 */
internal class EgressProcess(private val process: Process) {
    private val ready = CompletableDeferred<Unit>()
    private val acks = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
    private val input = process.outputStream.bufferedWriter()
    private var seq = 0L

    val alive: Boolean get() = process.isAlive

    init {
        thread(isDaemon = true, name = "egress-out") {
            try {
                process.inputStream.bufferedReader().forEachLine(::heard)
            } catch (_: IOException) {
            }
            val gone = IllegalStateException("The egress proxy exited")
            ready.completeExceptionally(gone)
            acks.values.forEach { it.completeExceptionally(gone) }
        }
        thread(isDaemon = true, name = "egress-err") {
            try {
                process.errorStream.bufferedReader().forEachLine { log.warn { "Egress proxy: $it" } }
            } catch (_: IOException) {
            }
        }
    }

    /** Returns once the proxy listens; throws when it exits or takes longer than a JVM should. */
    suspend fun awaitReady() {
        withTimeoutOrNull(READY_TIMEOUT) { ready.await() } ?: error("The egress proxy did not start within $READY_TIMEOUT")
    }

    /** Sends the next table and returns once the proxy has it in force. */
    suspend fun push(refused: List<String>, sandboxes: Map<String, EgressSandbox>) {
        check(alive) { "The egress proxy is not running" }
        val table = EgressTable(++seq, refused, sandboxes)
        val ack = CompletableDeferred<Unit>().also { acks[table.seq] = it }

        try {
            withContext(Dispatchers.IO) {
                input.write(table.encode())
                input.newLine()
                input.flush()
            }
            withTimeoutOrNull(ACK_TIMEOUT) { ack.await() } ?: error("The egress proxy did not take its table within $ACK_TIMEOUT")
        } finally {
            acks.remove(table.seq)
        }
    }

    /** Ends the proxy; the caller removes its container. */
    fun destroy() {
        process.destroy()
    }

    private fun heard(line: String) {
        when {
            line == EgressTable.READY -> ready.complete(Unit)
            line.startsWith(EgressTable.ACK) -> line.removePrefix(EgressTable.ACK).toLongOrNull()?.let { acks[it]?.complete(Unit) }
            else -> log.info { "Egress proxy: $line" }
        }
    }

    private companion object {
        val log = KotlinLogging.logger {}
        val READY_TIMEOUT = 60.seconds
        val ACK_TIMEOUT = 10.seconds
    }
}
