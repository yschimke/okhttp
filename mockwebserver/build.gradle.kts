plugins {
  kotlin("jvm")
  id("okhttp.publish-conventions")
  id("okhttp.jvm-conventions")
  id("okhttp.quality-conventions")
  id("okhttp.testing-conventions")
  alias(libs.plugins.burst)
}

project.applyJavaModules("mockwebserver3")

dependencies {
  "friendsApi"(projects.okhttp)
  compileOnly(libs.animalsniffer.annotations)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(projects.okhttpTls)
  testImplementation(projects.mockwebserver3Junit5)
  testImplementation(libs.junit)
  testImplementation(libs.kotlin.test.common)
  testImplementation(libs.assertk)
  testImplementation(libs.kotlinx.coroutines.core)
  testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
  explicitApi()
}
