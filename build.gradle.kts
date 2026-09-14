plugins {
    // the kotlin plugin itself arrives with the regolith.jvm convention plugin in buildSrc.
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt)
}

// one detekt run covers every module, so a rule change never has to be repeated per build file.
detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/detekt.yml")
    source.setFrom(listOf("protocol", "server", "pages", "sdk", "koog").flatMap { listOf("$it/src/main/kotlin", "$it/src/test/kotlin") })
    autoCorrect = false
}
