package io.reified.regolith.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readLines
import kotlin.io.path.relativeTo
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The layer rules from AGENTS.md, checked on imports. The inner layers name no framework and no
 * adapter; only `http` knows the wire types, and nothing inside knows how it is wired.
 */
class ArchitectureTest {
    private val root: Path = Path.of("src/main/kotlin/io/reified/regolith/server")

    private val forbidden = mapOf(
        "domain" to listOf("io.ktor", "io.reified.regolith.protocol", "io.reified.regolith.server.app", "io.reified.regolith.server.ports", "kotlinx.coroutines", "java.io", "java.nio"),
        "ports" to listOf("io.ktor", "io.reified.regolith.protocol", "io.reified.regolith.server.app"),
        "output" to listOf("io.ktor", "io.reified.regolith.protocol", "io.reified.regolith.server.app"),
        "app" to listOf("io.ktor", "io.reified.regolith.protocol"),
        "config" to listOf("io.ktor", "io.reified.regolith.protocol", "io.reified.regolith.server.app"),
    )
    private val adapters = listOf("docker", "store", "http", "publish")

    // wiring and operator commands: they may use every layer, and no layer may use them.
    private val composition = listOf("Main.kt", "ops")

    @Test
    fun `inner layers depend only inward`() {
        val violations = Files.walk(root).use { paths ->
            paths.filter { it.extension == "kt" }.toList()
        }.flatMap { file ->
            val layer = file.relativeTo(root).first().toString()
            val banned = forbidden[layer].orEmpty() +
                (if (layer in adapters || layer in composition) emptyList() else adapters.map { "io.reified.regolith.server.$it" }) +
                (if (layer in composition) emptyList() else listOf("io.reified.regolith.server.ops"))
            file.readLines().filter { it.startsWith("import ") }.filter { line -> banned.any { line.removePrefix("import ").startsWith(it) } }.map { "$file: $it" }
        }
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `the pages role is reached only through its wire types`() {
        // Main.kt runs the role; everything else talks to it over http, with the types in protocol.
        val violations = Files.walk(root).use { paths ->
            paths.filter { it.extension == "kt" && it.relativeTo(root).toString() != "Main.kt" }.toList()
        }.flatMap { file ->
            file.readLines().filter { it.startsWith("import io.reified.regolith.pages.") }.map { "$file: $it" }
        }
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `adapters do not reach into each other`() {
        val violations = adapters.flatMap { adapter ->
            Files.walk(root.resolve(adapter)).use { it.filter { path -> path.extension == "kt" }.toList() }.flatMap { file ->
                file.readLines().filter { it.startsWith("import ") }
                    .filter { line -> (adapters - adapter).any { line.startsWith("import io.reified.regolith.server.$it.") } }
                    .map { "$file: $it" }
            }
        }
        assertEquals(emptyList(), violations)
    }
}
