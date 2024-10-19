package com.squareup.okhttp3.conventions

import com.rickbusarow.kgx.checkProjectIsRoot
import com.squareup.okhttp3.conventions.utils.libs
import org.gradle.api.Project

open class RootPlugin : BasePlugin() {
  override fun Project.jvmTargetInt(): Int = libs.versions.jvm.target.minimal.get().toInt()
  override fun beforeApply(target: Project) {

    target.checkProjectIsRoot { "RootPlugin must only be applied to the root project" }

    target.plugins.apply("java-base")

    if (target.gradle.includedBuilds.isNotEmpty()) {
      target.plugins.apply(CompositePlugin::class.java)
    }
  }

  override fun afterApply(target: Project) {

    target.logVersionInfo()
  }

  private fun Project.logVersionInfo() {
    val kotlinVersion = libs.versions.kotlin.get()
    val fullTestRun = libs.versions.config.fullTestRun.get()

    logger.info(
      "Versions: ${
        mapOf(
          "Kotlin" to kotlinVersion,
          "Gradle" to gradle.gradleVersion,
          "Full Test Run" to fullTestRun,
        )
      }",
    )
  }
}
