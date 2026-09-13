package io.github.proairface.kotling2p.trainer

import io.github.proairface.kotling2p.LtsModel

/**
 * Scores the committed model against the held-out split, without retraining.
 *
 * Run via `./gradlew :trainer:evaluateLts`. Useful for checking that the file in the repository
 * is the one the numbers in the README describe.
 */
fun main() {
    val heldOut = Corpus.heldOut(Corpus.load())
    val model = LtsModel.loadBundled()
    println("held-out score:")
    println(Evaluation.score(model, heldOut))
    Evaluation.printMistakes(model, heldOut, limit = 40)
}
