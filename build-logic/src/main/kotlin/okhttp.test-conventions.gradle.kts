import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.targets.jvm.tasks.KotlinJvmTest

val platform = System.getProperty("okhttp.platform", "jdk9")
val testJavaVersion = System.getProperty("test.java.version", "21").toInt()

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("-Dokhttp.platform=$platform")

    if (platform == "loom") {
        jvmArgs("-Djdk.tracePinnedThreads=short")
    }

    val javaToolchains = project.extensions.getByType<JavaToolchainService>()
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(testJavaVersion))
    })

    maxParallelForks = Runtime.getRuntime().availableProcessors() * 2
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
    }

    systemProperty("okhttp.platform", platform)
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
    
    environment("OKHTTP_ROOT", rootDir)
}

tasks.withType<KotlinJvmTest>().configureEach {
    environment("OKHTTP_ROOT", rootDir)
}
