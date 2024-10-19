import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.spotless)
  id("java-gradle-plugin")
}

kotlin {
  jvmToolchain(libs.versions.jvm.toolchain.get().toInt())
  compilerOptions {
    jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvm.target.library.get()))
  }
}
java.targetCompatibility = JavaVersion.toVersion(libs.versions.jvm.target.library.get())

// TODO switch to spotless
//ktlint {
//  version = libs.versions.ktlint.get()
//}

gradlePlugin {
  plugins {
    register("settingsPlugin") {
      id = "com.squareup.okhttp3.gradle-settings"
      implementationClass = "com.squareup.okhttp3.builds.settings.SettingsPlugin"
    }
  }
}
