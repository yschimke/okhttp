package com.squareup.okhttp3.builds.settings

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.api.initialization.dsl.VersionCatalogBuilder
import org.gradle.api.internal.file.FileOperations
import java.io.File
import java.net.URI
import javax.inject.Inject

abstract class SettingsPlugin @Inject constructor(
  private val fileOperations: FileOperations,
) : Plugin<Settings> {

  override fun apply(target: Settings) {
    target.dependencyResolutionManagement.versionCatalogs { container ->

      val catalogBuilder = container.maybeCreate("libs")

      val maybeFile = target.rootDir.resolveInParents("gradle/libs.versions.toml")

      if (maybeFile != target.rootDir.resolve("gradle/libs.versions.toml")) {
        catalogBuilder.from(fileOperations.immutableFiles(maybeFile))
      }

      catalogBuilder.version("config-warningsAsErrors", System.getenv("CI") ?: "false")
    }

    @Suppress("UnstableApiUsage")
    target.dependencyResolutionManagement.repositories { repos ->
      repos.mavenCentral()
      repos.gradlePluginPortal()
      repos.google()
    }
  }

  private fun File.resolveInParents(relativePath: String): File {
    return resolve(relativePath).takeIf { it.exists() }
      ?: parentFile?.resolveInParents(relativePath)
      ?: error("File $relativePath not found")
  }
}
