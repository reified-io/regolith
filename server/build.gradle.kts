plugins {
    id("regolith.jvm")
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":protocol"))
    // the server image carries both roles: `serve` is the control plane, `pages` the public one.
    implementation(project(":pages"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.kotlin.logging.jvm)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    // the api tests drive the server through the real sdk, so the pair is checked as one contract.
    testImplementation(project(":sdk"))
}

application {
    mainClass.set("io.reified.regolith.server.MainKt")
}

tasks.jar {
    manifest {
        attributes("Implementation-Title" to "regolith-server", "Implementation-Version" to project.version)
    }
}

tasks.test {
    // real docker tests run only when asked for, since they start containers on the machine running the build.
    val dockerTests = System.getenv("REGOLITH_DOCKER_TESTS") ?: ""
    // host tests also change this machine's firewall and loop devices, so they have a switch of their own.
    val hostTests = System.getenv("REGOLITH_HOST_TESTS") ?: ""
    environment("REGOLITH_DOCKER_TESTS", dockerTests)
    environment("REGOLITH_HOST_TESTS", hostTests)
    environment("REGOLITH_HOST_TEST_LAN", System.getenv("REGOLITH_HOST_TEST_LAN") ?: "")

    // a test task's environment is not one of its inputs: without these, a result stored without a
    // switch is served to a run with it, and the other way round.
    inputs.property("dockerTests", dockerTests)
    inputs.property("hostTests", hostTests)
    // and a run against a real daemon or a real machine is never the same run twice: the helper
    // scripts, the images and the host it checks are no inputs of this task, so it would otherwise
    // report itself up to date after exactly the changes it exists to check.
    val real = dockerTests == "1" || hostTests == "1"
    outputs.upToDateWhen { !real }
    outputs.cacheIf { !real }
}
