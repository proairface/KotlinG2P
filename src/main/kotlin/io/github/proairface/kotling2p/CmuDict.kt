package io.github.proairface.kotling2p


/**
 * Loads the bundled CMU Pronouncing Dictionary (BSD-style licensed, Carnegie Mellon
 * University — see `src/main/resources/cmudict/LICENSE`) and exposes exact-match lookups.
 *
 * CMUdict lists alternate pronunciations as "WORD(1)", "WORD(2)", etc. — homograph
 * disambiguation (e.g. "READ" past vs. present tense) needs part-of-speech context this
 * library doesn't have, so only the first (primary) pronunciation of each word is kept.
 */
class CmuDict private constructor(
    /** Every entry, keyed by uppercase word — iterable so the model trainer can read the corpus. */
    val entries: Map<String, List<Phoneme>>,
) {

    fun lookup(word: String): List<Phoneme>? = entries[word.uppercase()]

    companion object {
        private const val RESOURCE_PATH = "cmudict/cmudict.dict"
        private val commentPrefix = ";;;"

        fun loadBundled(): CmuDict = load(latin1(BundledResources.bytes(RESOURCE_PATH)))

        /** The file is ISO-8859-1, which maps each byte straight to the same code point. */
        private fun latin1(bytes: ByteArray): String =
            CharArray(bytes.size) { (bytes[it].toInt() and 0xFF).toChar() }.concatToString()

        internal fun load(text: String): CmuDict {
            val entries = LinkedHashMap<String, List<Phoneme>>()
            for (rawLine in text.lineSequence()) {
                if (rawLine.startsWith(commentPrefix) || rawLine.isBlank()) continue
                val parts = rawLine.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size != 2) continue
                val word = parts[0].substringBefore('(').uppercase()
                if (entries.containsKey(word)) continue
                entries[word] = parts[1].split(" ").map { Phoneme(it) }
            }
            return CmuDict(entries)
        }
    }
}
