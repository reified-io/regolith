plugins {
    id("regolith.library")
    alias(libs.plugins.kotlin.serialization)
}

description = "Coroutine client for a Regolith sandbox server."

dependencies {
    api(project(":protocol"))
    api(libs.kotlinx.coroutines.core)
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    testImplementation(libs.ktor.client.mock)
}
