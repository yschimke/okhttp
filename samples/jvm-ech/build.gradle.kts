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

// The DEfO/Guardian Project's ECH-enabled Conscrypt is published on Maven Central as
// `info.guardianproject.conscrypt:conscrypt-openjdk`. The artifact is platform-specific —
// only the `linux-x86_64` classifier is published, so this sample currently builds and runs
// only on Linux x86_64 (which is what CI uses). On other platforms, build the fork locally.
val guardianConscryptVersion =
  (findProperty("guardianConscryptVersion") as String?) ?: "2.6.alpha1647601986.job2220801545"
val guardianConscryptClassifier =
  (findProperty("guardianConscryptClassifier") as String?) ?: "linux-x86_64"

dependencies {
  implementation(projects.okhttp)
  implementation(projects.okhttpDnsoverhttps)

  // Compile-time dependency on the Guardian Project's ECH-enabled Conscrypt build.
  // ConscryptEchModeConfiguration calls Conscrypt.setEchConfigList(...) directly and won't
  // compile against stock Conscrypt — by design, so we can't silently ship an ECH-stripped
  // build.
  implementation(
    "info.guardianproject.conscrypt:conscrypt-openjdk:$guardianConscryptVersion:$guardianConscryptClassifier",
  )
}
