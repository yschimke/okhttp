package com.squareup.okhttp3.conventions

import com.rickbusarow.kgx.pluginId
import com.squareup.okhttp3.conventions.utils.libs
import org.gradle.api.Project

open class AndroidLibraryPlugin : BasePlugin() {
  override fun Project.jvmTargetInt(): Int = libs.versions.jvm.target.library.get().toInt()

  override fun beforeApply(target: Project) {
    target.plugins.apply(target.libs.plugins.kotlin.jvm.pluginId)

    target.extensions.getByType(ConventionsExtension::class.java)
      .explicitApi.set(true)
  }
}
