plugins {
    id("regolith.jvm")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // the error shape and json settings are the project's, so a client reads one contract everywhere.
    implementation(project(":protocol"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.core)
    // netty, not cio: cio cannot terminate tls, and netty requires a client certificate whenever a trust
    // store is configured, which is what origin pulls from a cdn need.
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.status.pages)

    implementation(libs.kotlin.logging.jvm)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
}
