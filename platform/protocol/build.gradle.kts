plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
dependencies {
    api(libs.kotlinx.serialization.json)
}
