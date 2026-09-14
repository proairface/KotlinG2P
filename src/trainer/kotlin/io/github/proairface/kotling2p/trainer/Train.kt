package io.github.proairface.kotling2p.trainer

import io.github.proairface.kotling2p.FeatureLayout
import io.github.proairface.kotling2p.LtsModel
import io.github.proairface.kotling2p.Phoneme
import io.github.proairface.kotling2p.StressFeatureLayout
import java.io.File

/**
 * Trains the letter-to-sound model and writes it to the path given as the first argument, then
 * scores it on the held-out split.
 *
 * Run via `./gradlew trainLts`. The resulting file is committed, so this is not part of
 * a normal build — it exists so the shipped model can be reproduced and audited rather than taken
 * on faith. Hyperparameters can be swept without editing code:
 * `./gradlew trainLts -Dlts.trees=15 -Dlts.context=5`.
 */
fun main(arguments: Array<String>) {
    val output = File(arguments.firstOrNull() ?: error("usage: train <output-file>"))
    val emIterations = intProperty("lts.emIterations", 12)
    val ensembleSize = intProperty("lts.trees", 9)
    val seed = intProperty("lts.seed", 20260913).toLong()

    val started = System.currentTimeMillis()
    val all = Corpus.load()
    val training = Corpus.train(all)
    val heldOut = Corpus.heldOut(all)
    println("corpus: ${all.size} usable entries, ${training.size} for training, ${heldOut.size} held out")

    val alphabet = Alphabet.of(all)
    val phonemes = PhonemeVocabulary.of(all)
    val layout = FeatureLayout(
        contextRadius = intProperty("lts.context", 4),
        phonemeHistory = intProperty("lts.history", 4),
        valueSpace = maxOf(alphabet.size, phonemes.size),
    )
    println(
        "settings: contextRadius=${layout.contextRadius} phonemeHistory=${layout.phonemeHistory} " +
            "emIterations=$emIterations",
    )

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
    printSampleAlignments(aligned, chunks)

    // A single tree sees every feature at every split and every training row exactly once; that
    // is plain CART, and was the whole model before the ensemble existed. Several trees only help
    // if they disagree, so each gets its own bootstrap resample and a random subset of features
    // per split — the two standard sources of disagreement in a random forest.
    val settings = TreeTrainer.Settings(
        minLeaf = intProperty("lts.minLeaf", 1),
        minGain = 1e-6,
        maxDepth = intProperty("lts.maxDepth", 64),
        // Ten of twelve. Holding back two features per split is enough to make the trees
        // disagree where the evidence is thin, which is the whole point; holding back four
        // leaves them too weak to vote well (67.7% against 68.0% at nine trees, and a wider
        // 68.0% against 68.9% at fifteen).
        featuresPerSplit = intProperty(
            "lts.featuresPerSplit",
            if (ensembleSize == 1) layout.featureCount else layout.featureCount - 2,
        ),
    )
    println("settings: ensembleSize=$ensembleSize $settings")

    val examples = Examples.build(aligned, alphabet, phonemes, layout, chunks)
    val forest = HashMap<Int, List<TreeTrainer.Node>>()
    for (letter in alphabet.characters.indices) {
        val letterExamples = examples.forLetter(letter) ?: continue
        val rowCount = letterExamples.labels.size
        val random = kotlin.random.Random(seed + letter)
        forest[letter] = List(ensembleSize) { tree ->
            val rows = if (ensembleSize == 1) {
                IntArray(rowCount) { it }
            } else {
                IntArray(rowCount) { random.nextInt(rowCount) }
            }
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

    // A second, independent forest corrects stress after the letter forest has decoded a whole
    // word. It cannot be folded into the per-letter trees above: stress depends on how many
    // syllables the *whole word* has and where this one falls among them, which a tree that only
    // ever looks a few letters either side structurally cannot know.
    val stressLayout = StressFeatureLayout(
        // 5, not 3: the extra reach measurably helped (58.7% with no suffix at all, 59.8% at 3,
        // 59.9% at 5, flat beyond). English's stress-bearing suffixes run longer than three
        // letters ("-ation", "-ical", "-esque"), so the model needs to see that far to use them.
        suffixLength = intProperty("lts.stressSuffix", 5),
        positionCap = intProperty("lts.stressPositionCap", 6),
        valueSpace = maxOf(alphabet.size, phonemes.size, intProperty("lts.stressPositionCap", 6) + 1),
    )
    println("stress oracle check (position/suffix signal alone, no prior guess):")
    reportStressOracleAccuracy(heldOut, alphabet, phonemes, stressLayout)

    // The stress forest needs the letter forest's own guess as a feature (see
    // StressFeatureLayout.PRIOR_STRESS_GUESS), and that feature has to come from the letter
    // forest's actual predictions, not the ground truth its label is drawn from — otherwise the
    // tree just learns to copy a feature that won't be trustworthy at decode time. So every
    // training word is first decoded by the letter forest alone (stress correction switched off
    // by handing it an empty stress section), through a real, if throwaway, `LtsModel` — not a
    // simulation of one, so this sees exactly what real decoding sees, beam search included.
    val letterOnlyBytes = run {
        val temp = File.createTempFile("kotling2p-letter-only", ".bin")
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

    val stressEnsembleSize = intProperty("lts.stressTrees", 9)
    val stressSettings = TreeTrainer.Settings(
        minLeaf = intProperty("lts.stressMinLeaf", 5),
        minGain = 1e-6,
        maxDepth = intProperty("lts.stressMaxDepth", 24),
        featuresPerSplit = intProperty(
            "lts.stressFeaturesPerSplit",
            if (stressEnsembleSize == 1) stressLayout.featureCount else stressLayout.featureCount - 1,
        ),
    )
    println("stress settings: ensembleSize=$stressEnsembleSize $stressSettings")

    val stressRandom = kotlin.random.Random(seed - 1)
    val stressRowCount = stressData.labels.size
    val stressForest = List(stressEnsembleSize) {
        val rows = if (stressEnsembleSize == 1) {
            IntArray(stressRowCount) { it }
        } else {
            IntArray(stressRowCount) { stressRandom.nextInt(stressRowCount) }
        }
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
    val beamWidth = intProperty("lts.beam", LtsModel.DEFAULT_BEAM_WIDTH)
    println("held-out score (beam width $beamWidth):")
    println(Evaluation.score(model, heldOut, beamWidth))
    Evaluation.printMistakes(model, heldOut, beamWidth, limit = 25)
}

/** A word whose every letter now has the phoneme chunk it is responsible for. */
class AlignedWord(val word: String, val labels: IntArray)

/**
 * The aligned corpus reshaped into one training matrix per letter: a row for every occurrence of
 * that letter, whose features describe its surroundings and whose label is the phoneme chunk it
 * produced.
 *
 * Features come in two blocks, as [FeatureLayout] describes. First the letters either side,
 * which is the obvious signal. Then the phonemes already emitted to its left, skipping over silent
 * letters so the feature is the previous *sound* rather than "the previous letter said nothing".
 * Those carry constraints letters alone cannot express, above all that English does not drop the
 * vowel out of a syllable, which is what the letter-only model's worst mistakes looked like
 * ("abshire" as `AE1 B SH R`).
 *
 * A third block, how far the letter sits from each end of the word, was tried and removed: it
 * looked like the missing signal for stress placement and measured 1.8 points *worse*, the trees
 * spending their splits memorizing exact word lengths.
 *
 * At training time the left phonemes are the true ones; when decoding they are the model's own
 * earlier guesses, so an early mistake can mislead later positions. That exposure to its own
 * errors is a real cost, paid here only because measurement showed it outweighed — see the
 * README's accuracy table.
 */
class Examples private constructor(private val perLetter: Array<Letter?>) {

    class Letter(val features: ByteArray, val labels: IntArray, val chunkIds: IntArray) {
        /** Rewrites leaf labels from this letter's dense indices back to global chunk ids. */
        fun toGlobalLabels(node: TreeTrainer.Node): TreeTrainer.Node = when (node) {
            is TreeTrainer.Leaf -> TreeTrainer.Leaf(chunkIds[node.label])
            is TreeTrainer.Branch -> TreeTrainer.Branch(
                node.feature, node.value, toGlobalLabels(node.yes), toGlobalLabels(node.no),
            )
        }
    }

    fun forLetter(letter: Int): Letter? = perLetter[letter]

    companion object {
        fun build(
            aligned: List<AlignedWord>,
            alphabet: Alphabet,
            phonemes: PhonemeVocabulary,
            layout: FeatureLayout,
            chunks: ChunkTable,
        ): Examples {
            val occurrences = IntArray(alphabet.size)
            for (word in aligned) for (char in word.word) occurrences[alphabet.idOf(char)]++

            val features = Array(alphabet.size) { ByteArray(occurrences[it] * layout.featureCount) }
            val globalLabels = Array(alphabet.size) { IntArray(occurrences[it]) }
            val filled = IntArray(alphabet.size)
            val history = IntArray(layout.phonemeHistory)

            for (word in aligned) {
                val ids = IntArray(word.word.length) { alphabet.idOf(word.word[it]) }
                history.fill(phonemes.boundary)
                for (position in ids.indices) {
                    val letter = ids[position]
                    val row = filled[letter]++
                    val base = row * layout.featureCount
                    for (feature in 0 until layout.featureCount) {
                        val value = when {
                            layout.isLetterFeature(feature) -> {
                                val neighbour = position + layout.letterOffset(feature)
                                if (neighbour < 0 || neighbour >= ids.size) {
                                    alphabet.boundary
                                } else {
                                    ids[neighbour]
                                }
                            }
                            else -> history[layout.historySlot(feature)]
                        }
                        features[letter][base + feature] = value.toByte()
                    }
                    val chunk = word.labels[position]
                    globalLabels[letter][row] = chunk
                    for (phoneme in chunks.phonemesOf(chunk)) {
                        for (slot in history.size - 1 downTo 1) history[slot] = history[slot - 1]
                        history[0] = phonemes.idOf(phoneme)
                    }
                }
            }

            return Examples(
                Array(alphabet.size) { letter ->
                    if (occurrences[letter] == 0) return@Array null
                    // Trees index labels densely so their histograms stay small: a letter uses a
                    // few dozen of the corpus's ~3000 chunks.
                    val dense = LinkedHashMap<Int, Int>()
                    val labels = IntArray(globalLabels[letter].size) { row ->
                        dense.getOrPut(globalLabels[letter][row]) { dense.size }
                    }
                    Letter(features[letter], labels, dense.keys.toIntArray())
                },
            )
        }
    }
}

/**
 * Training rows for the stress-correction forest: one row per vowel occurrence, features as
 * [StressFeatureLayout] describes, label the vowel's true stress digit (0, 1 or 2) from CMUdict.
 */
class StressExamples private constructor(val features: ByteArray, val labels: IntArray, val skippedWords: Int) {
    companion object {
        /**
         * Builds rows from what the letter forest actually predicts for each training word, not
         * from the alignment's ground truth — see the [StressFeatureLayout.PRIOR_STRESS_GUESS]
         * doc comment for why that distinction is the whole point.
         *
         * A predicted word whose vowel *count* disagrees with the true count is dropped rather
         * than guessed at: with a different number of vowels there is no principled way to say
         * which predicted vowel a given true digit belongs to, and forcing a guess would train
         * on a wrong label rather than an absent one.
         */
        fun bootstrap(
            training: List<Entry>,
            letterOnlyModel: LtsModel,
            alphabet: Alphabet,
            phonemes: PhonemeVocabulary,
            layout: StressFeatureLayout,
        ): StressExamples {
            val featureRows = ArrayList<IntArray>()
            val labelRows = ArrayList<Int>()
            var skipped = 0

            for (entry in training) {
                val predicted = letterOnlyModel.predict(entry.word)
                val predictedVowels = predicted.indices.filter { predicted[it].stress != null }
                val trueDigits = entry.phonemes.mapNotNull { Phoneme(it).stress }
                if (predictedVowels.isEmpty() || predictedVowels.size != trueDigits.size) {
                    skipped++
                    continue
                }

                val total = predictedVowels.size
                val letterIds = IntArray(entry.word.length) { alphabet.idOf(entry.word[it]) }
                predictedVowels.forEachIndexed { index, position ->
                    val phoneme = predicted[position]
                    val row = IntArray(layout.featureCount)
                    row[StressFeatureLayout.VOWEL_FEATURE] = phonemes.idOf(phoneme.arpabet)
                    row[StressFeatureLayout.INDEX_FROM_START] = minOf(index, layout.positionCap)
                    row[StressFeatureLayout.INDEX_FROM_END] = minOf(total - 1 - index, layout.positionCap)
                    row[StressFeatureLayout.TOTAL_VOWELS] = minOf(total, layout.positionCap)
                    row[StressFeatureLayout.PRIOR_STRESS_GUESS] = phoneme.stress ?: 0
                    for (slot in 0 until layout.suffixLength) {
                        val letterIndex = letterIds.size - 1 - slot
                        row[layout.suffixSlot(slot)] = if (letterIndex >= 0) letterIds[letterIndex] else alphabet.boundary
                    }
                    featureRows += row
                    labelRows += trueDigits[index]
                }
            }

            val features = ByteArray(labelRows.size * layout.featureCount)
            for (i in featureRows.indices) {
                val base = i * layout.featureCount
                for (f in 0 until layout.featureCount) features[base + f] = featureRows[i][f].toByte()
            }
            return StressExamples(features, labelRows.toIntArray(), skipped)
        }
    }
}

/**
 * Trains and scores a throwaway stress forest on held-out words using their *true* phoneme
 * sequences, with [StressFeatureLayout.PRIOR_STRESS_GUESS] fixed at a neutral 0 rather than any
 * real guess — isolating what the position/suffix/identity features alone are worth, decoupled
 * from both the letter forest's phoneme mistakes and its (real, and stronger — see
 * [StressFeatureLayout.PRIOR_STRESS_GUESS]) local signal. A diagnostic, not part of the shipped
 * model: this trains and evaluates on the same held-out words, which is only valid because the
 * result is never serialized or used for anything but this one printed number.
 */
private fun reportStressOracleAccuracy(entries: List<Entry>, alphabet: Alphabet, phonemes: PhonemeVocabulary, layout: StressFeatureLayout) {
    val rows = ArrayList<IntArray>()
    val labels = ArrayList<Int>()
    for (entry in entries) {
        val vowelPositions = entry.phonemes.indices.filter { Phoneme(entry.phonemes[it]).stress != null }
        if (vowelPositions.isEmpty()) continue
        val total = vowelPositions.size
        val letterIds = IntArray(entry.word.length) { alphabet.idOf(entry.word[it]) }
        vowelPositions.forEachIndexed { index, position ->
            val phoneme = entry.phonemes[position]
            val row = IntArray(layout.featureCount)
            row[StressFeatureLayout.VOWEL_FEATURE] = phonemes.idOf(phoneme)
            row[StressFeatureLayout.INDEX_FROM_START] = minOf(index, layout.positionCap)
            row[StressFeatureLayout.INDEX_FROM_END] = minOf(total - 1 - index, layout.positionCap)
            row[StressFeatureLayout.TOTAL_VOWELS] = minOf(total, layout.positionCap)
            for (slot in 0 until layout.suffixLength) {
                val letterIndex = letterIds.size - 1 - slot
                row[layout.suffixSlot(slot)] = if (letterIndex >= 0) letterIds[letterIndex] else alphabet.boundary
            }
            rows += row
            labels += Phoneme(phoneme).stress!!
        }
    }

    val features = ByteArray(rows.size * layout.featureCount)
    for (i in rows.indices) for (f in 0 until layout.featureCount) features[i * layout.featureCount + f] = rows[i][f].toByte()
    val tree = TreeTrainer(
        features = features,
        labels = labels.toIntArray(),
        numFeatures = layout.featureCount,
        valueSpace = layout.valueSpace,
        numLabels = 3,
        settings = TreeTrainer.Settings(minLeaf = 5, minGain = 1e-6, maxDepth = 24, featuresPerSplit = layout.featureCount),
        random = kotlin.random.Random(0),
    ).train(IntArray(labels.size) { it })

    var correct = 0
    for (i in rows.indices) if (classifyNode(tree, rows[i]) == labels[i]) correct++
    println("  %.1f%% over %d vowels (trained and scored on the same words — a diagnostic ceiling, not a real score)"
        .format(100.0 * correct / labels.size.coerceAtLeast(1), labels.size))
}

private fun classifyNode(node: TreeTrainer.Node, features: IntArray): Int = when (node) {
    is TreeTrainer.Leaf -> node.label
    is TreeTrainer.Branch -> classifyNode(if (features[node.feature] == node.value) node.yes else node.no, features)
}

private fun intProperty(name: String, fallback: Int): Int =
    System.getProperty(name)?.toIntOrNull() ?: fallback

/**
 * Prints the alignment for a handful of words whose correct division is not in doubt, because the
 * only honest way to judge an unsupervised step is to look at what it produced.
 */
private fun printSampleAlignments(aligned: List<AlignedWord>, chunks: ChunkTable) {
    val interesting = listOf(
        "box", "phone", "night", "cute", "school", "psychology", "thought", "queen",
        "cough", "knight", "xylophone", "amsterdam", "roosevelt", "bridge", "ocean",
    )
    val byWord = aligned.associateBy { it.word }
    println("sample alignments:")
    for (word in interesting) {
        val entry = byWord[word] ?: continue
        println("  " + word.indices.joinToString("  ") { i ->
            val chunk = chunks.nameOf(entry.labels[i])
            "${word[i]}:${chunk.ifEmpty { "-" }}"
        })
    }
}
