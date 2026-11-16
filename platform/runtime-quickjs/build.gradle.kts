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
    implementation(libs.quickjs)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}

configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:${libs.versions.quickjs.get()}"))
    }
}
