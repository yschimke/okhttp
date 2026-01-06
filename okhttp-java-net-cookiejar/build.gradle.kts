import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  kotlin("jvm")
}

project.applyOsgi(
  "Export-Package: okhttp3.java.net.cookiejar",
  "Bundle-SymbolicName: com.squareup.okhttp3.java.net.cookiejar"
)

project.applyJavaModules("okhttp3.java.net.cookiejar")

dependencies {
  "friendsApi"(projects.okhttp)
  compileOnly(libs.animalsniffer.annotations)
}

mavenPublishing {
  configure(KotlinJvm(javadocJar = JavadocJar.Empty()))
}
