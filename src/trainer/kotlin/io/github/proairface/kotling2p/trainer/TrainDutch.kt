package io.github.proairface.kotling2p.trainer

import io.github.proairface.kotling2p.FeatureLayout
import io.github.proairface.kotling2p.LtsModel
import io.github.proairface.kotling2p.StressFeatureLayout
import io.github.proairface.kotling2p.TrainingSplit
import java.io.File

/**
 * Trains the Dutch letter-to-sound model shipped as `lts/dutch-model.bin` and loaded by
 * [io.github.proairface.kotling2p.DutchG2P] — **read that class's own doc comment before using
 * this**; the Dutch path is genuinely experimental, with one known unsolved problem (compound
 * stress). This trainer reuses the exact same Aligner / TreeTrainer / ModelWriter / LtsModel
 * machinery the English model uses, unmodified — nothing in that machinery is English-specific,
 * only [Corpus] (CMUdict-loading) was.
 *
 * The input corpus is built by `trainer/dutch/prepare_corpus.py` from
 * [WikiPron](https://github.com/CUNY-CL/wikipron)'s Dutch export — a real, offline, one-time
 * step (needs a WikiPron checkout and Python; see that script's own README), not part of this
 * Gradle build. An espeak-ng-oracle variant (stress and phoneme choices copied from real
 * espeak-ng output) was also built and compared by ear during development; it was **not**
 * adopted — the project's whole premise is avoiding GPLv3 espeak-ng entanglement, and using its
 * output as training data raises exactly the licensing question that premise exists to avoid.
 * Only the plain WikiPron-derived corpus (`option_a.tsv`) is trained here. See
 * `trainer/dutch/prepare_corpus.py` for where espeak-ng was still used, strictly for
 * verification/comparison during development — never as training data.
 *
 * Usage: `<corpus.tsv> <output.bin>`
 */
fun main(arguments: Array<String>) {
    val corpusFile = File(arguments.getOrNull(0) ?: error("usage: <corpus.tsv> <output.bin>"))
    val output = File(arguments.getOrNull(1) ?: error("usage: <corpus.tsv> <output.bin>"))
    val emIterations = intProp("lts.emIterations", 12)
    val ensembleSize = intProp("lts.trees", 9)
    val seed = intProp("lts.seed", 20260913).toLong()

    val started = System.currentTimeMillis()
    val all = loadTsv(corpusFile)
    val training = all.filterNot { TrainingSplit.isHeldOut(it.word) }
    val heldOut = all.filter { TrainingSplit.isHeldOut(it.word) }
    println("corpus: ${all.size} usable entries, ${training.size} for training, ${heldOut.size} held out")

    val alphabet = Alphabet.of(all)
    val phonemes = PhonemeVocabulary.of(all)
    val layout = FeatureLayout(
        contextRadius = intProp("lts.context", 4),
        phonemeHistory = intProp("lts.history", 4),
        valueSpace = maxOf(alphabet.size, phonemes.size),
    )
    println("settings: contextRadius=${layout.contextRadius} phonemeHistory=${layout.phonemeHistory} emIterations=$emIterations")

    val chunks = ChunkTable()
    val prepared = training.map { it to Aligner.prepare(it, alphabet, chunks) }
    val unalignable = prepared.count { it.second == null }
    println("alphabet: ${alphabet.characters.joinToString("")} (${alphabet.size} ids incl. boundary)")
    println("chunks: ${chunks.size} distinct; $unalignable entries need >2 phonemes on one letter, dropped")

    val alignments = Aligner(chunks, alphabet.size)
        .align(prepared.mapNotNull { it.second }, emIterations) { iteration, logLikelihood ->
            println("  EM iteration ${iteration + 1}/$emIterations: mean log-likelihood %.4f".format(logLikelihood))
        }

    val aligned = ArrayList<AlignedWord>(alignments.size)
    var index = 0
    for ((entry, word) in prepared) {
        if (word == null) continue
        alignments[index++]?.let { aligned += AlignedWord(entry.word, it) }
    }
    println("aligned ${aligned.size} of ${training.size} training entries")

    val settings = TreeTrainer.Settings(
        minLeaf = intProp("lts.minLeaf", 1),
        minGain = 1e-6,
        maxDepth = intProp("lts.maxDepth", 64),
        featuresPerSplit = intProp("lts.featuresPerSplit", if (ensembleSize == 1) layout.featureCount else layout.featureCount - 2),
    )
    println("settings: ensembleSize=$ensembleSize $settings")

    val examples = Examples.build(aligned, alphabet, phonemes, layout, chunks)
    val forest = HashMap<Int, List<TreeTrainer.Node>>()
    for (letter in alphabet.characters.indices) {
        val letterExamples = examples.forLetter(letter) ?: continue
        val rowCount = letterExamples.labels.size
        val random = kotlin.random.Random(seed + letter)
        forest[letter] = List(ensembleSize) {
            val rows = if (ensembleSize == 1) IntArray(rowCount) { it } else IntArray(rowCount) { random.nextInt(rowCount) }
            val trained = TreeTrainer(
                features = letterExamples.features,
                labels = letterExamples.labels,
                numFeatures = layout.featureCount,
                valueSpace = layout.valueSpace,
                numLabels = letterExamples.chunkIds.size,
                settings = settings,
                random = random,
            ).train(rows)
            letterExamples.toGlobalLabels(trained)
        }
    }

    val stressLayout = StressFeatureLayout(
        suffixLength = intProp("lts.stressSuffix", 5),
        positionCap = intProp("lts.stressPositionCap", 6),
        valueSpace = maxOf(alphabet.size, phonemes.size, intProp("lts.stressPositionCap", 6) + 1),
    )

    val letterOnlyBytes = run {
        val temp = File.createTempFile("kotling2p-nl-letter-only", ".bin")
        try {
            ModelWriter.write(
                temp, alphabet, phonemes, layout, forest, chunks,
                StressFeatureLayout(suffixLength = 0, positionCap = 0, valueSpace = 1), emptyList(),
            )
            temp.readBytes()
        } finally {
            temp.delete()
        }
    }
    val letterOnlyModel = LtsModel.read(letterOnlyBytes.inputStream())

    val stressData = StressExamples.bootstrap(training, letterOnlyModel, alphabet, phonemes, stressLayout)
    println(
        "stress examples: ${stressData.labels.size} vowel occurrences from ${training.size} training words " +
            "(${stressData.skippedWords} skipped: predicted and true vowel counts disagreed)",
    )

    val stressEnsembleSize = intProp("lts.stressTrees", 9)
    val stressSettings = TreeTrainer.Settings(
        minLeaf = intProp("lts.stressMinLeaf", 5),
        minGain = 1e-6,
        maxDepth = intProp("lts.stressMaxDepth", 24),
        featuresPerSplit = intProp("lts.stressFeaturesPerSplit", if (stressEnsembleSize == 1) stressLayout.featureCount else stressLayout.featureCount - 1),
    )
    val stressRandom = kotlin.random.Random(seed - 1)
    val stressRowCount = stressData.labels.size
    val stressForest = List(stressEnsembleSize) {
        val rows = if (stressEnsembleSize == 1) IntArray(stressRowCount) { it } else IntArray(stressRowCount) { stressRandom.nextInt(stressRowCount) }
        TreeTrainer(
            features = stressData.features,
            labels = stressData.labels,
            numFeatures = stressLayout.featureCount,
            valueSpace = stressLayout.valueSpace,
            numLabels = 3,
            settings = stressSettings,
            random = stressRandom,
        ).train(rows)
    }

    val stats = ModelWriter.write(output, alphabet, phonemes, layout, forest, chunks, stressLayout, stressForest)
    println(
        "model: ${stats.nodes} nodes (${stats.leaves} leaves), ${stats.labels} labels, " +
            "stress forest ${stats.stressNodes} nodes (${stats.stressLeaves} leaves), " +
            "%.1f KB at $output".format(stats.bytes / 1024.0),
    )
    println("trained in ${(System.currentTimeMillis() - started) / 1000}s")

    val model = output.inputStream().use { LtsModel.read(it) }
    val beamWidth = intProp("lts.beam", LtsModel.DEFAULT_BEAM_WIDTH)
    println("held-out score (beam width $beamWidth):")
    println(Evaluation.score(model, heldOut, beamWidth))
    Evaluation.printMistakes(model, heldOut, beamWidth, limit = 40)
}

/** Reads "word<TAB>phon1 phon2 ..." lines, phonemes already carrying an optional trailing
 * stress digit on vowels — same convention [io.github.proairface.kotling2p.Phoneme] expects. */
private fun loadTsv(file: File): List<Entry> =
    file.readLines().filter { it.isNotBlank() }.map { line ->
        val (word, phonemeField) = line.split('\t', limit = 2)
        Entry(word, phonemeField.split(' '))
    }

private fun intProp(name: String, fallback: Int): Int =
    System.getProperty(name)?.toIntOrNull() ?: fallback
