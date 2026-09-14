package io.reified.regolith.server.app

import io.github.oshai.kotlinlogging.KotlinLogging
import io.reified.regolith.server.domain.RegolithError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/** One mutex per key, for serializing work on a single sandbox without blocking the others. */
class KeyedLocks<K : Any> {
    private val locks = ConcurrentHashMap<K, Mutex>()

    suspend inline fun <T> withLock(key: K, action: () -> T): T = mutex(key).withLock(action = action)

    fun mutex(key: K): Mutex = locks.computeIfAbsent(key) { Mutex() }
}

/** Named checks behind `GET /v1/health`, and the gate that refuses work while one is failing. */
class Health {
    data class Check(val ok: Boolean, val detail: String?)

    private val checks = ConcurrentHashMap<String, Check>()

    fun report(name: String, ok: Boolean, detail: String? = null) {
        checks[name] = Check(ok, detail)
    }

    fun snapshot(): Map<String, Check> = checks.toSortedMap()

    val healthy: Boolean get() = checks.values.all { it.ok }

    /** Throws [RegolithError.Unavailable] while [name] is failing. */
    fun require(name: String) {
        val check = checks[name] ?: return
        if (!check.ok) throw RegolithError.Unavailable(check.detail ?: "The $name check is failing")
    }

    companion object {
        const val NETWORK = "network"
        const val STORAGE = "storage"
    }
}

private val log = KotlinLogging.logger {}

/** Runs [action] every [period] until the scope ends; a failed round is logged and the next one runs. */
fun CoroutineScope.every(period: Duration, name: String, action: suspend () -> Unit): Job = launch {
    while (isActive) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(e) { "Background task failed: task=[$name]" }
        }
        delay(period)
    }
}
