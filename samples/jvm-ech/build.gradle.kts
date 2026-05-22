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
  // Maven Central. Build it locally and install with
  //   ./gradlew :openjdk-uber:publishToMavenLocal
  // then this module will pick it up. See README.md for full instructions.
  mavenLocal()
}

// The DEfO Conscrypt build identifies itself with `defo` in its version string. Override on
// the command line, e.g. `-PdefoConscryptVersion=2.5.3-defo`, to point at your local build.
val defoConscryptVersion = (findProperty("defoConscryptVersion") as String?) ?: "2.5.3-defo"

dependencies {
  implementation(projects.okhttp)
  implementation(projects.okhttpDnsoverhttps)

  // Compile-time dependency on the DEfO Conscrypt fork. ConscryptEchModeConfiguration calls
  // Conscrypt.setEchConfigList(...) directly and won't compile against stock Conscrypt — by
  // design, so we cannot silently ship an ECH-stripped build.
  implementation("org.conscrypt:conscrypt-openjdk-uber:$defoConscryptVersion")
}
