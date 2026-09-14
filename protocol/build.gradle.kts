plugins {
    id("regolith.library")
    alias(libs.plugins.kotlin.serialization)
}

description = "Wire types of the Regolith /v1 API and of its pages intake."

dependencies {
    api(libs.kotlinx.serialization.json)
}
