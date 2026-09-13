package io.github.proairface.kotling2p.trainer

import io.github.proairface.kotling2p.LtsModel

/**
 * Scores the committed model against the held-out split, without retraining.
 *
 * Run via `./gradlew evaluateLts`. Useful for checking that the file in the repository
 * is the one the numbers in the README describe.
 */
fun main() {
    val heldOut = Corpus.heldOut(Corpus.load())
    val model = LtsModel.loadBundled()
    val beamWidth = LtsModel.DEFAULT_BEAM_WIDTH
    println("held-out score (beam width $beamWidth):")
    println(Evaluation.score(model, heldOut, beamWidth))
    Evaluation.printMistakes(model, heldOut, beamWidth, limit = 40)
}
