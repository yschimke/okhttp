import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  kotlin("jvm")
}

project.applyJavaModules("mockwebserver3.junit4")

dependencies {
  api(projects.okhttp)
  api(projects.mockwebserver3)
  api(libs.junit)

  testImplementation(libs.assertk)
  testImplementation(libs.junit.vintage.engine)
}

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Empty()))
}
