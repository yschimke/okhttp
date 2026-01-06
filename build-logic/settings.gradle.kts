rootProject.name = "okhttp-build-logic"

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files(settingsDir.resolve("../gradle/libs.versions.toml")))
        }
    }
}
