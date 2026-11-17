plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
}
repositories { mavenCentral() }
kotlin { jvmToolchain(21) }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("io.github.dokar3:quickjs-kt-jvm:1.0.15")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
sourceSets {
    main {
        kotlin.srcDirs(
            "../../platform/protocol/src/main/kotlin",
            "../../platform/core/src/main/kotlin",
            "../../platform/runtime-quickjs/src/main/kotlin"
        )
        kotlin.include(
            "dev/rikkahub/plugins/protocol/**",
            "dev/rikkahub/plugins/core/**",
            "dev/rikkahub/plugins/runtime/PluginRuntime.kt",
            "dev/rikkahub/plugins/runtime/PluginInstance.kt",
            "dev/rikkahub/plugins/verification/**"
        )
    }
    test {
        kotlin.srcDirs("../../platform/core/src/test/kotlin", "../../platform/runtime-quickjs/src/contractTest/kotlin")
    }
}
application { mainClass.set("dev.rikkahub.plugins.verification.PluginContractCliKt") }
tasks.test { useJUnit() }
