package io.reified.regolith.server.app

import io.reified.regolith.server.config.ServerConfig

/** The application as the HTTP adapter sees it. */
class Services(
    val config: ServerConfig,
    val version: String,
    val health: Health,
    val sandboxes: Sandboxes,
    val sessions: Sessions,
    val execs: Execs,
    val files: SandboxFiles,
    val sites: SitePublishing,
)
