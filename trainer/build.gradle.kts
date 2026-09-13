// Build-time-only tooling. Not published: consumers get the root `kotling2p` artifact,
// which bundles this project's *output* (the compiled model resource), not its code.
plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(rootProject)
}

/**
 * Rebuilds `src/main/resources/lts/model.bin` from the bundled CMUdict.
 *
 * The output is committed, so a normal build (and JitPack) never runs this — it exists so the
 * model is reproducible rather than a mystery blob. Training takes a couple of minutes and
 * needs more heap than Gradle's default.
 */
val trainLts by tasks.registering(JavaExec::class) {
    group = "kotling2p"
    description = "Train the letter-to-sound model and write it into the library's resources."
    mainClass.set("io.github.proairface.kotling2p.trainer.TrainKt")
    classpath = sourceSets["main"].runtimeClasspath
    maxHeapSize = "4g"
    args = listOf(rootProject.file("src/main/resources/lts/model.bin").absolutePath)
    // Forwarded so hyperparameters can be swept from the command line:
    // ./gradlew :trainer:trainLts -Dlts.context=5 -Dlts.minLeaf=3
    val knobs = listOf("lts.context", "lts.history", "lts.emIterations", "lts.minLeaf", "lts.maxDepth")
    for (name in knobs) {
        System.getProperty(name)?.let { systemProperty(name, it) }
    }
}

/** Scores the committed model against the held-out split and prints the metrics. */
val evaluateLts by tasks.registering(JavaExec::class) {
    group = "kotling2p"
    description = "Evaluate the committed letter-to-sound model on held-out CMUdict words."
    mainClass.set("io.github.proairface.kotling2p.trainer.EvaluateKt")
    classpath = sourceSets["main"].runtimeClasspath
    maxHeapSize = "2g"
}
