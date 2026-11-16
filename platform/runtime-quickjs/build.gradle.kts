plugins {
    id("rikkahub.android.library")
}

android {
    namespace = "dev.rikkahub.plugins.runtime"
    sourceSets {
        getByName("test").java.srcDir("src/contractTest/kotlin")
        getByName("androidTest").java.srcDir("src/contractTest/kotlin")
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
