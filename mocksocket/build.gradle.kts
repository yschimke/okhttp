plugins {
  kotlin("jvm")
  id("okhttp.publish-conventions")
  id("okhttp.jvm-conventions")
  id("okhttp.quality-conventions")
  id("okhttp.testing-conventions")
}

project.applyJavaModules("mocksocket")

dependencies {
  api(libs.square.okio)
  api(libs.kotlinx.coroutines.core)
  implementation(libs.pkts.core)

  testImplementation(libs.assertk)
  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.junit.jupiter.api)
  testRuntimeOnly(libs.junit.jupiter.engine)
}

kotlin {
  explicitApi()
}
