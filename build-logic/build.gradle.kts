plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
    google()
}

dependencies {
    // Dependencies required by convention plugins
    implementation("com.diffplug.spotless:spotless-plugin-gradle:8.0.0")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
    implementation("ru.vyarus:gradle-animalsniffer-plugin:2.0.1")
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:2.0.0")
    implementation("com.vanniktech:gradle-maven-publish-plugin:0.35.0")
    implementation("org.jetbrains.kotlinx:binary-compatibility-validator:0.18.1")
}
