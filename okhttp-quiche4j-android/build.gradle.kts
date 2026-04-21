plugins {
  id("com.android.library")
  id("okhttp.base-conventions")
}

android {
  namespace = "okhttp3.quiche4j.android"
  compileSdk = 36

  defaultConfig {
    minSdk = 21
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

}

kotlin {
  jvmToolchain(21)
}

dependencies {
  api(projects.okhttpQuiche4j)
  implementation(libs.dnsjava)
  implementation(libs.androidx.annotation)
}
