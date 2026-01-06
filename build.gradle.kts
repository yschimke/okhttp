@file:Suppress("UnstableApiUsage")

import com.diffplug.gradle.spotless.SpotlessExtension
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import java.net.URI
import kotlinx.validation.ApiValidationExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.dokka.gradle.DokkaTaskPartial
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.jvm.tasks.KotlinJvmTest
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import ru.vyarus.gradle.plugin.animalsniffer.AnimalSnifferExtension

buildscript {
  dependencies {
    classpath(libs.gradlePlugin.dokka)
    classpath(libs.gradlePlugin.kotlin)
    classpath(libs.gradlePlugin.kotlinSerialization)
    classpath(libs.gradlePlugin.androidJunit5)
    classpath(libs.gradlePlugin.android)
    classpath(libs.gradlePlugin.bnd)
    classpath(libs.gradlePlugin.burst)
    classpath(libs.gradlePlugin.shadow)
    classpath(libs.gradlePlugin.animalsniffer)
    classpath(libs.gradlePlugin.errorprone)
    classpath(libs.gradlePlugin.spotless)
    classpath(libs.gradlePlugin.mavenPublish)
    classpath(libs.gradlePlugin.binaryCompatibilityValidator)
    classpath(libs.gradlePlugin.mavenSympathy)
    classpath(libs.gradlePlugin.graalvmBuildTools)
    classpath(libs.gradlePlugin.ksp)
  }

  repositories {
    mavenCentral()
    gradlePluginPortal()
    google()
  }
}


plugins {
  id("okhttp.project-conventions")
}

val platform = System.getProperty("okhttp.platform", "jdk9")
val testJavaVersion = System.getProperty("test.java.version", "21").toInt()

/** Configure building for Java+Kotlin projects. */
subprojects {
  apply(plugin = "okhttp.project-conventions")

  if (name == "okhttp-bom" || name == "okhttp-android" || name == "android-test" ||
      name == "regression-test" || name == "android-test-app" || name == "container-tests" ||
      name == "module-tests") {
    return@subprojects
  }

  apply(plugin = "okhttp.jvm-conventions")
  apply(plugin = "okhttp.test-conventions")

  if (name != "okhttp") {
    apply(plugin = "okhttp.publish-conventions")
  }
}


plugins.withId("org.jetbrains.kotlin.jvm") {
  val test = tasks.named("test")
  tasks.register("jvmTest") {
    description = "Get 'gradlew jvmTest' to run the tests of JVM-only modules"
    dependsOn(test)
  }
}

tasks.wrapper {
  distributionType = Wrapper.DistributionType.ALL
}
