plugins {
  kotlin("jvm")
  id("okhttp.jvm-conventions")
  id("okhttp.quality-conventions")
  id("okhttp.testing-conventions")
  application
}

application {
  mainClass.set("okhttp3.sample.ech.JvmEchKt")
}

repositories {
  // The DEfO Conscrypt fork (https://github.com/defo-project/conscrypt) does not publish to
  // Maven Central. Build it locally and install with `./gradlew :openjdk-uber:publishToMavenLocal`,
  // then this module will pick it up. See README.md for full instructions.
  mavenLocal()
}

// The DEfO Conscrypt build identifies itself with `defo` in its version string. Override on
// the command line, e.g. `-PdefoConscryptVersion=2.5.3-defo`, to point at your local build.
val defoConscryptVersion = (findProperty("defoConscryptVersion") as String?) ?: "2.5.3-defo"

dependencies {
  implementation(projects.okhttp)
  implementation(projects.okhttpDnsoverhttps)

  // Resolved from mavenLocal() when the local DEfO build is installed. If absent the project
  // will fail to resolve; in that case run with `-PdefoConscryptVersion=2.5.3` to use stock
  // Conscrypt (ECH then degrades to standard TLS — useful for testing the no-ECH path).
  implementation("org.conscrypt:conscrypt-openjdk-uber:$defoConscryptVersion")
}
