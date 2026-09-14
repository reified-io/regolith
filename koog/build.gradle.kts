plugins {
    id("regolith.library")
}

description = "Koog ShellCommandExecutor that runs an agent's commands in a Regolith sandbox."

dependencies {
    api(project(":sdk"))
    api(libs.koog.agents.ext)

    testImplementation(libs.ktor.client.mock)
}
