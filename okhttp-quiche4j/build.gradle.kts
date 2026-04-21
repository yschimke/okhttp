plugins {
  kotlin("jvm")
  id("okhttp.publish-conventions")
  id("okhttp.jvm-conventions")
  id("okhttp.quality-conventions")
  id("okhttp.testing-conventions")
}

project.applyOsgi(
  "Export-Package: okhttp3.quiche4j",
  "Bundle-SymbolicName: com.squareup.okhttp3.quiche4j",
)

project.applyJavaModules("okhttp3.quiche4j")

dependencies {
  "friendsApi"(projects.okhttp)
  api("io.quiche4j:quiche4j-core")
  implementation(libs.dnsjava)

  testImplementation(projects.okhttpTestingSupport)
  testImplementation(projects.mockwebserver3)
  testImplementation(projects.okhttpTls)
  testImplementation(libs.junit)
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.junit.jupiter.engine)
  testImplementation(libs.junit.jupiter.params)
  testImplementation(libs.kotlin.test.common)
  testImplementation(libs.kotlin.test.junit)
  testImplementation(libs.assertk)
}

tasks.withType<Test>().configureEach {
  testLogging {
    events(
      org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED,
      org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED,
      org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED,
      org.gradle.api.tasks.testing.logging.TestLogEvent.STANDARD_OUT,
      org.gradle.api.tasks.testing.logging.TestLogEvent.STANDARD_ERROR,
    )
    showStandardStreams = true
  }
  systemProperty(
    "okhttp.quiche4j.debug",
    providers.systemProperty("okhttp.quiche4j.debug").orElse("false").get(),
  )
}
