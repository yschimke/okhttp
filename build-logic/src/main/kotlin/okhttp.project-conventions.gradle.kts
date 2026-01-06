import com.diffplug.gradle.spotless.SpotlessExtension

group = "com.squareup.okhttp3"
version = "5.4.0-SNAPSHOT"

repositories {
    mavenCentral()
    google()
}

// Spotless configuration
pluginManager.apply("com.diffplug.spotless")
configure<SpotlessExtension> {
    kotlin {
        target("**/*.kt")
        targetExclude("**/kotlinTemplates/**/*.kt")
        ktlint()
    }
}

// Normalization
normalization {
    runtimeClasspath {
        metaInf {
            ignoreAttribute("Bnd-LastModified")
        }
    }
}
