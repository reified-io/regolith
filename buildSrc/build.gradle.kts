plugins {
    `kotlin-dsl`
}

dependencies {
    // the convention plugins apply the kotlin plugin, so it has to be on their own classpath.
    implementation(libs.kotlin.gradle.plugin)
}
