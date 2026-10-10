plugins {
    id("regolith.jvm")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // the table the control plane sends is the only structured input; everything else is raw bytes.
    implementation(libs.kotlinx.serialization.json)
}
