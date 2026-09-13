package io.github.proairface.kotling2p

import java.io.BufferedReader

/**
 * Loads the bundled CMU Pronouncing Dictionary (BSD-style licensed, Carnegie Mellon
 * University — see `src/main/resources/cmudict/LICENSE`) and exposes exact-match lookups.
 *
 * CMUdict lists alternate pronunciations as "WORD(1)", "WORD(2)", etc. — homograph
 * disambiguation (e.g. "READ" past vs. present tense) needs part-of-speech context this
 * library doesn't have, so only the first (primary) pronunciation of each word is kept.
 */
class CmuDict private constructor(private val entries: Map<String, List<Phoneme>>) {

    fun lookup(word: String): List<Phoneme>? = entries[word.uppercase()]

    companion object {
        private const val RESOURCE_PATH = "cmudict/cmudict.dict"
        private val commentPrefix = ";;;"

        fun loadBundled(): CmuDict {
            val stream = CmuDict::class.java.classLoader.getResourceAsStream(RESOURCE_PATH)
                ?: error("$RESOURCE_PATH not found on the classpath")
            return stream.bufferedReader(Charsets.ISO_8859_1).use { load(it) }
        }

        internal fun load(reader: BufferedReader): CmuDict {
            val entries = LinkedHashMap<String, List<Phoneme>>()
            reader.forEachLine { rawLine ->
                if (rawLine.startsWith(commentPrefix) || rawLine.isBlank()) return@forEachLine
                val parts = rawLine.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size != 2) return@forEachLine
                val word = parts[0].substringBefore('(').uppercase()
                if (entries.containsKey(word)) return@forEachLine
                entries[word] = parts[1].split(" ").map { Phoneme(it) }
            }
            return CmuDict(entries)
        }
    }
}
