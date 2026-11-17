plugins {
    id("rikkahub.android.library")
}

android {
    namespace = "dev.rikkahub.plugins.runtime"
    sourceSets {
        getByName("test").kotlin.directories.add("src/contractTest/kotlin")
        getByName("androidTest").kotlin.directories.add("src/contractTest/kotlin")
    }
}

dependencies {
    implementation(project(":plugin-core"))
    implementation(libs.quickjs)
    implementation(libs.kotlinx.coroutines.core)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${libs.versions.kotlin.coroutines.core.get()}")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("androidx.test:runner:1.7.0")
}

configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:${libs.versions.quickjs.get()}"))
    }
}
