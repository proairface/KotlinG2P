plugins {
    kotlin("jvm") version "2.0.21"
    `java-library`
}

group = "io.github.proairface"
version = "0.4.0"

repositories {
    mavenCentral()
}

/**
 * The model trainer lives in its own source set rather than its own subproject.
 *
 * It has to stay out of the published jar — it is build-time tooling, and no app should carry a
 * CMUdict-crunching decision-tree learner around in its APK. A subproject looked like the tidy
 * way to arrange that and quietly did the opposite: JitPack publishes every module it finds, so
 * the root coordinate became an aggregate POM that pulled the trainer in as a transitive
 * dependency of anything depending on this library. A source set has no such failure mode, since
 * `jar` packages only `main`.
 */
val trainer: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
}

configurations[trainer.implementationConfigurationName]
    .extendsFrom(configurations[sourceSets["main"].implementationConfigurationName])

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
    }
}

// Nothing depends on the trainer, so it would otherwise never be compiled and could rot
// unnoticed until someone tried to retrain.
tasks.check {
    dependsOn(tasks.named("compile${trainer.name.replaceFirstChar { it.uppercase() }}Kotlin"))
}

/** Hyperparameters, forwarded from the command line so they can be swept without editing code. */
val trainingKnobs = listOf(
    "lts.context", "lts.history", "lts.emIterations", "lts.minLeaf", "lts.maxDepth",
    "lts.trees", "lts.featuresPerSplit", "lts.beam", "lts.seed",
)

/**
 * Rebuilds `src/main/resources/lts/model.bin` from the bundled CMUdict.
 *
 * The output is committed, so a normal build (and JitPack) never runs this — it exists so the
 * model is reproducible rather than a mystery blob. Training needs more heap than Gradle's
 * default and takes about twenty seconds.
 */
val trainLts by tasks.registering(JavaExec::class) {
    group = "kotling2p"
    description = "Train the letter-to-sound model and write it into the library's resources."
    mainClass.set("io.github.proairface.kotling2p.trainer.TrainKt")
    classpath = trainer.runtimeClasspath
    maxHeapSize = "6g"
    args = listOf(file("src/main/resources/lts/model.bin").absolutePath)
    for (knob in trainingKnobs) {
        System.getProperty(knob)?.let { systemProperty(knob, it) }
    }
}

/** Scores the committed model against the held-out split and prints the metrics. */
val evaluateLts by tasks.registering(JavaExec::class) {
    group = "kotling2p"
    description = "Evaluate the committed letter-to-sound model on held-out CMUdict words."
    mainClass.set("io.github.proairface.kotling2p.trainer.EvaluateKt")
    classpath = trainer.runtimeClasspath
    maxHeapSize = "2g"
}
