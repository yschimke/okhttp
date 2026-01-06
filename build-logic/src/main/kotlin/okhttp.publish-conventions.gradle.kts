import com.vanniktech.maven.publish.MavenPublishBaseExtension
import kotlinx.validation.ApiValidationExtension
import org.jetbrains.dokka.gradle.DokkaTaskPartial
import java.net.URI

plugins {
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish.base")
}

if (!plugins.hasPlugin("binary-compatibility-validator")) {
    pluginManager.apply("binary-compatibility-validator")
}

tasks.withType<DokkaTaskPartial>().configureEach {
    dokkaSourceSets.configureEach {
        reportUndocumented.set(false)
        skipDeprecated.set(true)
        jdkVersion.set(8)
        perPackageOption {
            matchingRegex.set(".*\\.internal.*")
            suppress.set(true)
        }
        if (project.file("Module.md").exists()) {
            includes.from(project.file("Module.md"))
        }
        externalDocumentationLink {
            url.set(URI.create("https://square.github.io/okio/3.x/okio/").toURL())
            packageListUrl.set(URI.create("https://square.github.io/okio/3.x/okio/okio/package-list").toURL())
        }
    }
}

configure<MavenPublishBaseExtension> {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
    pom {
        name.set(project.name)
        description.set("Square’s meticulous HTTP client for Java and Kotlin.")
        url.set("https://square.github.io/okhttp/")
        licenses {
            license {
                name.set("The Apache Software License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        scm {
            connection.set("scm:git:https://github.com/square/okhttp.git")
            developerConnection.set("scm:git:ssh://git@github.com/square/okhttp.git")
            url.set("https://github.com/square/okhttp")
        }
        developers {
            developer {
                name.set("Square, Inc.")
            }
        }
    }
}

configure<ApiValidationExtension> {
    ignoredPackages += "okhttp3.logging.internal"
    ignoredPackages += "mockwebserver3.internal"
    ignoredPackages += "okhttp3.internal"
    ignoredPackages += "mockwebserver3.junit5.internal"
    ignoredPackages += "okhttp3.brotli.internal"
    ignoredPackages += "okhttp3.sse.internal"
    ignoredPackages += "okhttp3.tls.internal"
}
