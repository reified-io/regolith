plugins {
    id("regolith.library")
}

dependencies {
    api(project(":sdk"))
    api(libs.koog.agents.ext)

    testImplementation(libs.ktor.client.mock)
}
