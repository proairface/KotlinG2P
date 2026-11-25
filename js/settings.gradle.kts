// A separate build, not included from the root: the library's JVM build and its JitPack
// publication stay exactly as they were. This one compiles the same sources for JavaScript, for
// Detour's web version. From the repo root:  ./gradlew -p js jsBrowserProductionLibraryDistribution
pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "kotling2p-js"
