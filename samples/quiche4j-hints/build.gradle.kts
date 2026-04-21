plugins {
  kotlin("jvm")
  id("okhttp.jvm-conventions")
  id("okhttp.quality-conventions")
}

dependencies {
  implementation(projects.okhttp)
  implementation(projects.okhttpQuiche4j)
}
