plugins {
    `kotlin-dsl`
}

dependencies {
    // the convention plugins apply the kotlin plugin, so it has to be on their own classpath.
    implementation(libs.kotlin.gradle.plugin)
    // and dokka, which renders the kdoc a published module carries into the javadoc jar central asks for.
    implementation(libs.dokka.gradle.plugin)
}
