pluginManagement {
  repositories {
    google()
    gradlePluginPortal()
    mavenCentral()
  }
  includeBuild("../settings")
}

plugins {
  id("com.squareup.okhttp3.gradle-settings")
}

rootProject.name = "conventions"
