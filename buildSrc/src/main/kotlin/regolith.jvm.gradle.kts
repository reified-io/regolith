// what every module of this project shares: the toolchain, the test runner and kotlin.test.

plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("skipped", "failed")
    }
}
