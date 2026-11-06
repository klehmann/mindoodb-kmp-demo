import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.1"
}

group = "com.mindoo.db"
version = "0.0.1"

// Any JDK from 21 on builds and runs the demo; the bytecode and API level stay at Java 21.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjdk-release=21")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

dependencies {
    implementation("com.mindoo.db:mindoodb-kmp-client:0.0.1")
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    testImplementation(kotlin("test"))
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
}

tasks.test {
    // The walkthrough test needs a MindooDB server; it is skipped without one.
    environment("MINDOODB_TEST_SERVER_URL", System.getenv("MINDOODB_TEST_SERVER_URL") ?: "")
    systemProperty("demo.screenshots", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
    systemProperty("demo.density", System.getProperty("demo.density") ?: "1")
}

compose.desktop {
    application {
        mainClass = "com.mindoo.db.demo.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "MindooDB Kotlin Demo"
            packageVersion = "1.0.0"
        }
    }
}
