// buildSrc is a build of its own: it does not inherit the root's repositories, and it reads the
// version catalog from there so the Kotlin version is declared once for the whole project.
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
