// a module published for someone else to compile against: every public declaration says `public`
// and carries KDoc, and the compiler enforces it. the same modules are what a release signs and
// hands to maven central.

plugins {
    id("regolith.jvm")
    id("org.jetbrains.dokka")
    `maven-publish`
    signing
}

kotlin {
    explicitApi()
}

java {
    withSourcesJar()
}

// central requires a javadoc artifact. dokka's javadoc format is still alpha, so the html
// publication ships in that jar instead: the validator checks that the file is there, people read
// what is inside it.
val javadocJar = tasks.register<Jar>("javadocJar") {
    archiveClassifier = "javadoc"
    from(tasks.named("dokkaGeneratePublicationHtml"))
}

publishing {
    publications.register<MavenPublication>("library") {
        from(components["java"])
        artifact(javadocJar)

        pom {
            name = "Regolith ${project.name}"
            url = "https://github.com/reified-io/regolith"

            licenses {
                license {
                    name = "The Apache License, Version 2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                }
            }

            developers {
                developer {
                    name = "Taras Adamchuk"
                    // an address of its own: a published pom is immutable, so the one address that
                    // may never be dropped — the site's — does not belong in it.
                    email = "maven@reified.io"
                    organization = "Reified"
                    organizationUrl = "https://reified.io"
                }
            }

            scm {
                connection = "scm:git:https://github.com/reified-io/regolith.git"
                developerConnection = "scm:git:ssh://git@github.com/reified-io/regolith.git"
                url = "https://github.com/reified-io/regolith"
            }
        }
    }

    // the release workflow zips this directory whole. it is already the repository layout, with the
    // checksums beside every file, that a central portal bundle is.
    repositories.maven {
        name = "bundle"
        url = uri(rootProject.layout.buildDirectory.dir("bundle"))
    }
}

// a module sets `description` in its own build file, which gradle reads after this plugin applies,
// so the pom takes it once that has happened. central refuses a pom without one.
afterEvaluate {
    publishing.publications.named<MavenPublication>("library") {
        pom.description = requireNotNull(project.description) {
            "${project.path} is published and has no description"
        }
    }
}

val signingKey = providers.environmentVariable("SIGNING_KEY")
val signingPassword = providers.environmentVariable("SIGNING_PASSWORD")
// hoisted: inside `signing { }` the publishing accessor resolves against the wrong receiver.
val publications = publishing.publications

// an ordinary build signs nothing: the key exists only in the release workflow's environment, and a
// developer who has none still gets a bundle shaped like the published thing.
if (signingKey.isPresent && signingPassword.isPresent) {
    signing {
        useInMemoryPgpKeys(signingKey.get(), signingPassword.get())
        sign(publications)
    }
}
