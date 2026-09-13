package io.github.proairface.kotling2p.trainer

import io.github.proairface.kotling2p.FeatureLayout
import io.github.proairface.kotling2p.LtsModel
import java.io.File

/**
 * Trains the letter-to-sound model and writes it to the path given as the first argument, then
 * scores it on the held-out split.
 *
 * Run via `./gradlew :trainer:trainLts`. The resulting file is committed, so this is not part of
 * a normal build — it exists so the shipped model can be reproduced and audited rather than taken
 * on faith. Hyperparameters can be swept without editing code:
 * `./gradlew :trainer:trainLts -Dlts.context=5 -Dlts.history=3`.
 */
fun main(arguments: Array<String>) {
    val output = File(arguments.firstOrNull() ?: error("usage: train <output-file>"))
    val emIterations = intProperty("lts.emIterations", 12)
    val settings = TreeTrainer.Settings(
        minLeaf = intProperty("lts.minLeaf", 1),
        minGain = 1e-6,
        maxDepth = intProperty("lts.maxDepth", 64),
    )

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
            "emIterations=$emIterations $settings",
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

    val examples = Examples.build(aligned, alphabet, phonemes, layout, chunks)
    val trees = HashMap<Int, TreeTrainer.Node>()
    for (letter in alphabet.characters.indices) {
        val letterExamples = examples.forLetter(letter) ?: continue
        val tree = TreeTrainer(
            features = letterExamples.features,
            labels = letterExamples.labels,
            numFeatures = layout.featureCount,
            valueSpace = layout.valueSpace,
            numLabels = letterExamples.chunkIds.size,
            settings = settings,
        ).train(IntArray(letterExamples.labels.size) { it })
        trees[letter] = letterExamples.toGlobalLabels(tree)
    }

    val stats = ModelWriter.write(output, alphabet, phonemes, layout, trees, chunks)
    println(
        "model: ${stats.nodes} nodes (${stats.leaves} leaves), ${stats.labels} labels, " +
            "%.1f KB at $output".format(stats.bytes / 1024.0),
    )
    println("trained in ${(System.currentTimeMillis() - started) / 1000}s")

    val model = output.inputStream().use { LtsModel.read(it) }
    println("held-out score:")
    println(Evaluation.score(model, heldOut))
    Evaluation.printMistakes(model, heldOut, limit = 25)
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
