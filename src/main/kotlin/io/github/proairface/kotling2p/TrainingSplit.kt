package io.github.proairface.kotling2p

/**
 * Splits CMUdict's vocabulary into a training portion and a held-out portion, by hashing the
 * word itself.
 *
 * This is public API on purpose. The letter-to-sound model ([LtsModel]) is *trained on CMUdict*,
 * so any accuracy number measured on words it trained on is meaningless — a lookup table scores
 * 100% on its own contents. The trainer excludes [isHeldOut] words and the benchmark scores only
 * [isHeldOut] words, both by calling exactly this function, so the two can't drift apart.
 *
 * Hashing the word (rather than shuffling with a seeded RNG, or slicing the file) makes the split
 * independent of dictionary order and of how many words happen to be filtered out beforehand:
 * adding, removing or reordering entries never moves an unrelated word between the two sides.
 * FNV-1a is used rather than [String.hashCode] only so the split is reproducible from the
 * description alone, in any language, by anyone checking our numbers.
 */
object TrainingSplit {

    /** 1-in-[HELD_OUT_EVERY] words are held out — roughly 5%, about 6000 real words. */
    const val HELD_OUT_EVERY = 20

    fun isHeldOut(word: String): Boolean =
        (fnv1a(word.uppercase()) ushr 1) % HELD_OUT_EVERY == 0

    private fun fnv1a(text: String): Int {
        var hash = -0x7ee3623b // 0x811c9dc5, FNV-1a's 32-bit offset basis
        for (char in text) {
            hash = hash xor char.code
            hash *= 0x01000193 // FNV prime
        }
        return hash
    }
}
