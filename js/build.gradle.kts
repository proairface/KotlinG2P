plugins { kotlin("multiplatform") version "2.0.21" }

kotlin {
    js(IR) {
        browser()
        binaries.library()
        useEsModules()
        generateTypeScriptDefinitions()
    }
    sourceSets {
        jsMain {
            // Every library source, unchanged, except the two JVM-only files: BundledResources.kt
            // (replaced by this build's own, see src/jsMain) and JvmStreams.kt (java.io).
            kotlin.srcDir("../src/main/kotlin")
            kotlin.exclude("**/BundledResources.kt", "**/JvmStreams.kt")
        }
    }
}

// Node comes from the machine (`node` on PATH) instead of a download from nodejs.org.
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension>().download = false
}
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension>().download = false
}
