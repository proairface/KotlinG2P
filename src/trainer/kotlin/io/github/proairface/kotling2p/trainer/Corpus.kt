package io.github.proairface.kotling2p.trainer

import io.github.proairface.kotling2p.CmuDict
import io.github.proairface.kotling2p.TrainingSplit

/** One dictionary entry, lowercased letters paired with its ARPAbet phonemes. */
data class Entry(val word: String, val phonemes: List<String>)

/**
 * Reads the bundled CMUdict into the form the trainer wants.
 *
 * Entries are filtered down to words the model can actually learn from: letters (plus the
 * apostrophe, which is common enough in real text — "don't", "o'brien" — to be worth modelling)
 * and nothing else. That drops CMUdict's punctuation entries ("!exclamation-point"), its spelled
 * abbreviations ("a.", "b."), and anything with a digit, none of which behave like words.
 */
object Corpus {

    /** The characters a word may contain. Anything else and the entry is dropped. */
    private val allowed = ('a'..'z').toSet() + '\''

    fun load(): List<Entry> {
        val dictionary = CmuDict.loadBundled()
        return dictionary.entries.mapNotNull { (word, phonemes) ->
            val letters = word.lowercase()
            if (letters.isEmpty() || letters.any { it !in allowed }) return@mapNotNull null
            Entry(letters, phonemes.map { it.arpabet })
        }
    }

    /** The portion the model is allowed to learn from. */
    fun train(all: List<Entry>): List<Entry> = all.filterNot { TrainingSplit.isHeldOut(it.word) }

    /** The portion reserved for measuring, never seen during training. */
    fun heldOut(all: List<Entry>): List<Entry> = all.filter { TrainingSplit.isHeldOut(it.word) }
}

/**
 * Assigns dense integer ids to the letters the corpus uses, plus one extra id standing for
 * "past the edge of the word" so context features have a value everywhere.
 *
 * The alphabet is written into the model file rather than hard-coded in the runtime, so the
 * trainer and the decoder cannot disagree about what id 7 means.
 */
class Alphabet(letters: List<Char>) {
    val characters: List<Char> = letters.sorted()
    private val index: Map<Char, Int> = characters.withIndex().associate { (i, c) -> c to i }

    /** Id used for positions before the first or after the last letter. */
    val boundary: Int = characters.size
    val size: Int = characters.size + 1

    fun idOf(char: Char): Int = index[char] ?: boundary

    companion object {
        fun of(entries: List<Entry>): Alphabet =
            Alphabet(entries.flatMap { it.word.toList() }.distinct())
    }
}

/**
 * Dense ids for the bare ARPAbet phonemes, stress digits removed.
 *
 * Used only for the "what did I just say" features. Stress is dropped there deliberately: the
 * useful signal is which *sound* precedes this letter, and keeping three stressed variants of
 * every vowel would split that evidence three ways for nothing.
 */
class PhonemeVocabulary(names: Collection<String>) {
    val names: List<String> = names.sorted()
    private val index: Map<String, Int> = this.names.withIndex().associate { (i, n) -> n to i }

    /** Id meaning "nothing has been said yet" — before the start of the word. */
    val boundary: Int = this.names.size
    val size: Int = this.names.size + 1

    fun idOf(phoneme: String): Int = index[stripStress(phoneme)] ?: boundary

    companion object {
        fun stripStress(phoneme: String): String = phoneme.trimEnd { it.isDigit() }

        fun of(entries: List<Entry>): PhonemeVocabulary =
            PhonemeVocabulary(entries.flatMapTo(HashSet()) { it.phonemes.map(::stripStress) })
    }
}

/**
 * Interns the phoneme chunks a single letter can produce — "" (a silent letter), "AH0", or a
 * pair like "K S" for the x in "box" — as dense integer ids.
 */
class ChunkTable {
    private val ids = LinkedHashMap<String, Int>()
    private val names = ArrayList<String>()

    val size: Int get() = names.size

    fun idOf(name: String): Int = ids.getOrPut(name) {
        names.add(name)
        names.size - 1
    }

    fun nameOf(id: Int): String = names[id]

    fun phonemesOf(id: Int): List<String> =
        names[id].let { if (it.isEmpty()) emptyList() else it.split(' ') }
}
