import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.spotless)
  id("java-gradle-plugin")
}

gradlePlugin {
  plugins {
    register("gradleTests") {
      id = "conventions.gradle-tests"
      implementationClass = "com.squareup.okhttp3.conventions.GradleTestsPlugin"
    }
    register("library") {
      id = "conventions.library"
      implementationClass = "com.squareup.okhttp3.conventions.LibraryPlugin"
    }
    register("publish") {
      id = "conventions.publish"
      implementationClass = "com.squareup.okhttp3.conventions.PublishConventionPlugin"
    }
    register("root") {
      id = "conventions.root"
      implementationClass = "com.squareup.okhttp3.conventions.RootPlugin"
    }
    register("spotless") {
      id = "conventions.spotless"
      implementationClass = "com.squareup.okhttp3.conventions.SpotlessPlugin"
    }
    register("androidLibrary") {
      id = "conventions.androidLibrary"
      implementationClass = "com.squareup.okhttp3.conventions.AndroidLibraryPlugin"
    }
  }
}

kotlin {
  jvmToolchain(libs.versions.jvm.toolchain.get().toInt())
  compilerOptions {
    jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvm.target.library.get()))
  }
}
java.targetCompatibility = JavaVersion.toVersion(libs.versions.jvm.target.library.get())

// TODO spotless
//ktlint {
//  version = libs.versions.ktlint.get()
//}

dependencies {
  compileOnly(gradleApi())

  api(libs.gradlePlugin.dokka)
  api(libs.gradlePlugin.kotlin)
  implementation(libs.kotlin.gradle.extensions)

  // Expose the generated version catalog API to the plugins.
  implementation(files(libs::class.java.superclass.protectionDomain.codeSource.location))
}
