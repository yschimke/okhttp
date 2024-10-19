@file:Suppress("UnstableApiUsage")

rootProject.name = "settings"

dependencyResolutionManagement {
  repositories {
    mavenCentral()
    gradlePluginPortal()
    google()
  }
  versionCatalogs {
    create("libs") {
      from(files("../../gradle/libs.versions.toml"))
    }
  }
}
