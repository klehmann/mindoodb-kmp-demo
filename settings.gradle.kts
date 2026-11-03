rootProject.name = "mindoodb-kmp-demo"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

// The Kotlin client, built from a clone next to this one.
includeBuild("../mindoodb-kmp-client")
