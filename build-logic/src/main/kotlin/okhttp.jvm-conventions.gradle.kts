import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.plugins.quality.Checkstyle

plugins {
    id("java")
    id("checkstyle")
    id("ru.vyarus.animalsniffer")
}

val testJavaVersion = System.getProperty("test.java.version", "21").toInt()

val checkstyleVersion = "12.3.0"

configure<JavaPluginExtension> {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<Checkstyle>().configureEach {
    exclude("**/CipherSuite.java")
}

val checkstyleConfig: Configuration by configurations.creating
dependencies {
    checkstyleConfig("com.puppycrawl.tools:checkstyle:$checkstyleVersion")
}

configure<CheckstyleExtension> {
    config = resources.text.fromArchiveEntry(checkstyleConfig, "google_checks.xml")
    toolVersion = checkstyleVersion
}

val androidSignature by configurations.creating
val jvmSignature by configurations.creating

dependencies {
    // These should ideally use libs catalog, but use hardcoded strings if libs fails
    androidSignature("net.sf.androidscents.signature:android-api-level-21:5.0.1_r2@signature")
    jvmSignature("org.codehaus.mojo.signature:java18:1.0@signature")
}

extensions.configure<ru.vyarus.gradle.plugin.animalsniffer.AnimalSnifferExtension> {
    annotation = "okhttp3.internal.SuppressSignatureCheck"
    defaultTargets("debug")
}

val javaVersionSetting =
    if (testJavaVersion > 8 && (project.name == "okcurl" || project.name == "native-image-tests")) {
        "17"
    } else {
        "1.8"
    }

val projectJvmTarget = JvmTarget.fromTarget(javaVersionSetting)
val projectJavaVersion = JavaVersion.toVersion(javaVersionSetting)

configurations {
    val friendsApi = register("friendsApi") {
        isCanBeResolved = true
        isCanBeConsumed = false
        isTransitive = true
    }
    val friendsImplementation = register("friendsImplementation") {
        isCanBeResolved = true
        isCanBeConsumed = false
        isTransitive = false
    }
    val friendsTestImplementation = register("friendsTestImplementation") {
        isCanBeResolved = true
        isCanBeConsumed = false
        isTransitive = false
    }
    configureEach {
        if (name == "implementation") {
            extendsFrom(friendsApi.get(), friendsImplementation.get())
        }
        if (name == "api") {
            extendsFrom(friendsApi.get())
        }
        if (name == "testImplementation") {
            extendsFrom(friendsTestImplementation.get())
        }
    }
}

tasks.withType<KotlinCompile>().configureEach {
    configurations.findByName("friendsApi")?.let {
        friendPaths.from(it.incoming.artifactView { }.files)
    }
    configurations.findByName("friendsImplementation")?.let {
        friendPaths.from(it.incoming.artifactView { }.files)
    }
    configurations.findByName("friendsTestImplementation")?.let {
        friendPaths.from(it.incoming.artifactView { }.files)
    }
    compilerOptions {
        jvmTarget.set(projectJvmTarget)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

tasks.withType<JavaCompile>().configureEach {
    if (name.contains("Java9")) {
        sourceCompatibility = "9"
        targetCompatibility = "9"
    } else {
        sourceCompatibility = projectJavaVersion.toString()
        targetCompatibility = projectJavaVersion.toString()
    }
}
