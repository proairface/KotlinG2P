package io.github.proairface.kotling2p

import java.io.InputStream

/**
 * EXPERIMENTAL. Espeak-free Dutch grapheme-to-phoneme, built the same way [G2P] was for
 * English — a letter-to-sound model trained on a real pronunciation dictionary, zero espeak-ng
 * anywhere in the training data or the runtime path — but far less mature. Read this whole doc
 * comment before using it; it is not a drop-in Dutch equivalent of [G2P] yet.
 *
 * ### What this is built from
 *
 * The dictionary is [WikiPron](https://github.com/CUNY-CL/wikipron)'s Dutch export (mined from
 * Wiktionary, CC-BY-SA/GFDL like Wiktionary itself), not CMUdict-equivalent CELEX data (not
 * freely licensed) and not espeak-ng's own output. WikiPron carries no stress marks at all and,
 * for a full quarter of its Dutch entries, lists the SAME word with conflicting pronunciations
 * (mostly the well-known "-en" verb-ending alternation, e.g. "aanbakken" scraped as both
 * `aːnbɑkə` and `aːnbɑkən`) — both cleaned up in `trainer/dutch/prepare_corpus.py`, along with a
 * hand-verified override list for a small number of extremely common words the model doesn't
 * reliably get right on its own (see [KNOWN_WORDS] below). See that script's own comments for
 * the full account of what was found and fixed, including several instances of WikiPron simply
 * having picked the wrong regional/spurious variant for common words (e.g. Dutch numbers).
 *
 * ### The one real, unsolved problem: compound stress
 *
 * Primary stress defaults to a word's first non-schwa vowel — fine for simple words, wrong for
 * genuine compounds, where stress depends on where the word's meaningful parts begin, not on
 * syllable position. Confirmed directly: "kerkstraat" and "aangekomen" have the same vowel
 * count but completely different real stress patterns, and even the same name stresses
 * differently depending on what follows it ("Julianastraat" stresses the middle, "Julianaplein"
 * stresses the front — checked against espeak-ng's own output, for verification only, never as
 * training data). A rule that tried "stress every non-schwa vowel as secondary" was tried and
 * rejected by ear: it fixed short words like "aangekomen" but badly over-stressed longer ones.
 * There is no reliable rule-based fix for this without real compound-boundary knowledge, which
 * this class does not have. [KNOWN_WORDS] patches specific words that were checked by ear; it is
 * not a general solution. **Any address or place name this class hasn't seen before is a
 * plausible source of a wrong stress placement.**
 *
 * ### What IS solid, and checked by ear across a real, growing test set
 *
 * - Word-final "-en" cleaned up (schwa vs. full form no longer arbitrary).
 * - A handful of homograph-like function words ("een" the article vs. "een" the number,
 *   "het"/"er" reduced vs. citation forms) fixed with exact overrides — see [KNOWN_WORDS].
 * - Sentence assembly destresses closed-class function words ([UNSTRESSED_FUNCTION_WORDS]) and
 *   preserves sentence-ending punctuation as a real pause cue — without both of these, a
 *   multi-word sentence reads as a flat, evenly-paused list of words instead of a fluent
 *   sentence, which is not obvious from single-word testing alone.
 * - The offglide symbols in the WikiPron-derived phoneme inventory (`i̯`, `u̯`, `y̯`) use a
 *   different Unicode encoding (a combining diacritic) than what actual espeak-trained Piper
 *   voices expect (plain `ɪ`, `ʊ`, `y`) — this was silently corrupting diphthongs in words like
 *   "nieuwendijk" (dropped final consonants, an extra glide-like sound) until the phoneme map
 *   was corrected; see `dutch-phoneme-map.tsv`.
 * - **Compound primary-stress boundaries**, for the specific case that matters most for a
 *   routing app: street/place-name compounds ("Kerkstraat", "Julianalaan"). [DutchCompoundSegmenter]
 *   finds the real-word split (dictionary-backed, against a bundled OpenTaal wordlist) and gives
 *   ONLY the first constituent's own stress — the rest is deliberately left unstressed rather
 *   than guessing at secondary stress (see the section above: that guess was tried and rejected).
 *   A small, individually hand-verified [NAME_STRESS_OVERRIDES] table covers opaque proper names
 *   whose internal stress the default heuristic gets wrong (checked against real espeak-ng
 *   output for verification only) — three entries so far (juliana, beatrix, wilhelmina), added
 *   only after confirming each actually needs it. This does NOT generalize to arbitrary compounds
 *   or arbitrary proper names; any address this hasn't specifically been checked against remains
 *   a plausible source of wrong stress, same caveat as above, just narrower in scope now.
 *
 * None of this has been tried on-device, only through a desktop `onnxruntime` harness against
 * the real `nl_NL-pim-medium` Piper voice (CC0) — same caveat [G2P]'s own English pipeline had
 * before its on-device verification. No dictionary lookup exists for Dutch (unlike [CmuDict] for
 * English) — every word goes through the letter-to-sound model or [KNOWN_WORDS].
 *
 * @see G2P for the mature, dictionary-backed English equivalent this is modeled on.
 */
class DutchG2P(
    private val model: LtsModel = loadBundledModel(),
    private val tokenToIpa: Map<String, String> = loadBundledPhonemeMap(),
    private val compoundDictionary: Set<String> = loadBundledWordlist(),
) {

    /**
     * Espeak-style IPA transcription for [text], ready for a Piper-trained Dutch voice's phoneme
     * vocabulary — same convention as [G2P.toEspeakIpa]. Sentence-ending punctuation (`.!?`) is
     * preserved as a literal token: the target voice's vocabulary has one, and dropping it makes
     * a multi-sentence phrase read as one run-on sentence with no pause at the boundary.
     */
    fun toEspeakIpa(text: String): String {
        val sentenceEnd = Regex("""([.!?])""")
        val sentences = sentenceEnd.split(text).map { it.trim() }.filter { it.isNotEmpty() }
        val punctuation = sentenceEnd.findAll(text).map { it.value }.toList()

        return sentences.mapIndexed { index, sentence ->
            val words = sentence.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
                .joinToString(" ") { word -> transcribeWord(word) }
            words + (punctuation.getOrNull(index) ?: "")
        }.joinToString(" ").let(::fixDiphthongNucleus)
    }

    private fun transcribeWord(word: String): String {
        val destress = word in UNSTRESSED_FUNCTION_WORDS
        val symbols = KNOWN_WORDS[word] ?: transcribeCompoundAware(word)
        return symbols.joinToString("") { (ipa, stress) ->
            val marker = when {
                destress -> ""
                stress == 1 -> "ˈ"
                stress == 2 -> "ˌ"
                else -> ""
            }
            marker + ipa
        }
    }

    /**
     * Splits [word] into real-word constituents via [DutchCompoundSegmenter] when it's not a
     * [KNOWN_WORDS] entry. A genuine split (2+ constituents) keeps only the FIRST constituent's
     * own stress as the word's primary stress — matching Dutch's default compound-stress rule
     * (Booij: main stress falls on the first constituent in most cases) — and suppresses stress
     * on every later constituent entirely, rather than guessing at secondary stress (see the
     * class doc comment for why that guess was tried and rejected). No split found (the segmenter
     * returns the word unchanged) falls back to the existing single-word behavior.
     */
    private fun transcribeCompoundAware(word: String): List<Pair<String, Int>> {
        val constituents = DutchCompoundSegmenter.constituents(word, compoundDictionary)
        if (constituents.size < 2) return predictOrOverride(word)

        return constituents.mapIndexed { index, constituent ->
            val symbols = predictOrOverride(constituent)
            if (index == 0) symbols else symbols.map { (ipa, _) -> ipa to 0 }
        }.flatten()
    }

    private fun predictOrOverride(constituent: String): List<Pair<String, Int>> =
        NAME_STRESS_OVERRIDES[constituent] ?: model.predict(constituent).map { phoneme ->
            val ipa = tokenToIpa[phoneme.base]
                ?: error("unknown token '${phoneme.base}' for constituent '$constituent' — the model and phoneme map are out of sync")
            ipa to (phoneme.stress ?: 0)
        }

    // WikiPron's "ɑu̯" diphthong (koud, rauw, nou...) uses a different NUCLEUS vowel than what
    // espeak/Piper actually use for the same sound (ʌʊ, confirmed against real espeak output for
    // verification only) -- not just an offglide notation difference like the other three, so it
    // needs its own substring fix rather than a phoneme-map remap (ɑ is correct on its own
    // everywhere else, e.g. "kat").
    private fun fixDiphthongNucleus(ipa: String): String = ipa.replace("ɑʊ", "ʌʊ")

    companion object {
        private const val MODEL_RESOURCE_PATH = "lts/dutch-model.bin"
        private const val PHONEME_MAP_RESOURCE_PATH = "lts/dutch-phoneme-map.tsv"
        private const val WORDLIST_RESOURCE_PATH = "lts/dutch-wordlist.txt"

        private fun loadBundledModel(): LtsModel {
            val stream: InputStream = DutchG2P::class.java.classLoader.getResourceAsStream(MODEL_RESOURCE_PATH)
                ?: error("$MODEL_RESOURCE_PATH not found on the classpath")
            return stream.use { LtsModel.read(it) }
        }

        /** Plain newline-separated text, not a serialized set — see [loadBundledPhonemeMap]'s
         * own note on why this library avoids a real parser dependency for flat data. Backs
         * [DutchCompoundSegmenter]; see that class's doc comment for the data source and license
         * (OpenTaal, dual Revised-BSD/CC-BY-3.0) and `trainer/dutch/prepare_wordlist.py` for how
         * it was filtered down from OpenTaal's raw ~414k-entry list. */
        private fun loadBundledWordlist(): Set<String> {
            val stream = DutchG2P::class.java.classLoader.getResourceAsStream(WORDLIST_RESOURCE_PATH)
                ?: error("$WORDLIST_RESOURCE_PATH not found on the classpath")
            return stream.bufferedReader().useLines { lines -> lines.filter { it.isNotBlank() }.toHashSet() }
        }

        /** Plain "token\tipa" lines, not JSON — this library has no runtime dependencies and a
         * flat map needs no real parser to earn one. Tokens are short ASCII placeholders (not
         * single characters) interned during training — see `trainer/dutch/prepare_corpus.py`. */
        private fun loadBundledPhonemeMap(): Map<String, String> {
            val stream = DutchG2P::class.java.classLoader.getResourceAsStream(PHONEME_MAP_RESOURCE_PATH)
                ?: error("$PHONEME_MAP_RESOURCE_PATH not found on the classpath")
            return stream.bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.associate { line ->
                    val (token, ipa) = line.split('\t', limit = 2)
                    token to ipa
                }
            }
        }

        /**
         * A handful of extremely common Dutch words where the trained model either mispredicts
         * outright or doesn't reliably reproduce its own training label at decode time (beam
         * search across a tree ensemble can drift from a single ground-truth example, especially
         * for short high-frequency words). Bypassing prediction entirely for these guarantees the
         * fix holds. Each entry is (IPA symbol, stress digit: 0 none, 1 primary, 2 secondary).
         * Checked against real espeak-ng output for verification only, never used as training
         * data or bundled anywhere.
         */
        internal val KNOWN_WORDS: Map<String, List<Pair<String, Int>>> = mapOf(
            "een" to listOf("ə" to 0, "n" to 0),                     // the article, not the number (/eːn/)
            "het" to listOf("h" to 0, "ə" to 0, "t" to 0),           // the normal weak form
            "er" to listOf("ə" to 0, "r" to 0),                      // the normal weak form
            "twaalf" to listOf("t" to 0, "ʋ" to 0, "aː" to 1, "l" to 0, "f" to 0),
            "elf" to listOf("ɛ" to 1, "l" to 0, "f" to 0),
            "zeven" to listOf("z" to 0, "eː" to 1, "v" to 0, "ə" to 0, "n" to 0),
            "dertig" to listOf("d" to 0, "ɛ" to 1, "r" to 0, "t" to 0, "ə" to 0, "x" to 0),
            "veertig" to listOf("v" to 0, "eː" to 1, "r" to 0, "t" to 0, "ə" to 0, "x" to 0),
            "vijftig" to listOf("v" to 0, "ɛ" to 1, "ɪ" to 0, "f" to 0, "t" to 0, "ə" to 0, "x" to 0),
            "zestig" to listOf("z" to 0, "ɛ" to 1, "s" to 0, "t" to 0, "ə" to 0, "x" to 0),
            "zeventig" to listOf("z" to 0, "eː" to 1, "v" to 0, "ə" to 0, "n" to 0, "t" to 0, "ə" to 0, "x" to 0),
            // Real Dutch stresses both syllables here (confirmed against espeak, verification
            // only); the model's single-primary-stress-only default can't produce the secondary.
            "aangekomen" to listOf(
                "aː" to 1, "n" to 0, "ɣ" to 0, "ə" to 0, "k" to 0, "oː" to 2, "m" to 0, "ə" to 0, "n" to 0,
            ),
        )

        /**
         * Hand-verified overrides for opaque proper names whose internal stress
         * [DutchCompoundSegmenter]'s default per-constituent heuristic (first non-schwa vowel)
         * gets wrong. Checked individually against real espeak-ng output (verification only,
         * never training data) — each entry here was confirmed to actually need one; several
         * other candidate names (Maxima, Willem, Alexander, Emma) were checked and found to
         * already stress correctly under the default heuristic, so they're deliberately absent.
         * Reusable across every compound using that name (e.g. "julianalaan", "julianaplein",
         * "julianastraat" all reuse this "juliana" entry) — a real generalization over patching
         * each compound individually the way [KNOWN_WORDS] does.
         */
        internal val NAME_STRESS_OVERRIDES: Map<String, List<Pair<String, Int>>> = mapOf(
            "juliana" to listOf("j" to 0, "y" to 2, "l" to 0, "i" to 0, "aː" to 1, "n" to 0, "aː" to 0),
            "beatrix" to listOf("b" to 0, "ə" to 0, "ɑ" to 1, "t" to 0, "r" to 0, "ɪ" to 0, "k" to 0, "s" to 0),
            "wilhelmina" to listOf(
                "ʋ" to 0, "ɪ" to 2, "l" to 0, "h" to 0, "ɛ" to 0, "l" to 0, "m" to 0, "i" to 1, "n" to 0, "aː" to 0,
            ),
        )

        /**
         * Closed-class Dutch function words — articles, common prepositions, conjunctions, and
         * already-reduced weak-form pronouns — normally unstressed in a fluent sentence. Every
         * word getting its own stress makes a short phrase sound fine but a longer sentence sound
         * like a word-by-word list; this is the connected-speech phenomenon English has for
         * "a/the/and/of/to".
         */
        internal val UNSTRESSED_FUNCTION_WORDS: Set<String> = setOf(
            "een", "het", "de",
            "aan", "af", "bij", "in", "op", "om", "over", "naar", "van", "voor", "met", "door", "uit", "langs", "tot",
            "en", "of", "maar", "want", "dat", "als",
            "je", "ze", "we", "me",
            "te",
        )
    }
}
