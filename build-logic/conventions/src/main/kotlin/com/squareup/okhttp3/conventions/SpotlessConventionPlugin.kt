package com.squareup.okhttp3.conventions

import com.squareup.okhttp3.conventions.utils.libs
import org.gradle.api.Plugin
import org.gradle.api.Project

open class SpotlessConventionPlugin : Plugin<Project> {

  override fun apply(target: Project) {
    target.plugins.apply(SpotlessPlugin::class.java)

    target.extensions.configure(SpotlessExtension::class.java) { ktlint ->
      ktlint.version.set(target.libs.versions.ktlint)
      val pathsToExclude = listOf(
        "build/generated",
        "root-build/generated",
        "included-build/generated",
      )
      ktlint.filter {
        it.exclude {
          pathsToExclude.any { excludePath ->
            it.file.path.contains(excludePath)
          }
        }
      }
      ktlint.verbose.set(true)
    }
  }
}
