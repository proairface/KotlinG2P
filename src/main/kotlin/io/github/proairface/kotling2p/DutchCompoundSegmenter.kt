package io.github.proairface.kotling2p

/**
 * Splits an unknown Dutch word into its real-word constituents, so [DutchG2P] can give a genuine
 * compound (e.g. "kerkstraat", "julianalaan") first-constituent primary stress instead of the
 * wrong default (stress on the first non-schwa vowel of the whole, unsegmented word).
 *
 * Ported from a validated Python prototype (see `trainer/dutch/segment_prototype.py`) against
 * [OpenTaal's wordlist](https://github.com/OpenTaal/opentaal-wordlist) (dual Revised-BSD /
 * CC-BY-3.0 — no copyleft obligation, commercial use fine with attribution; verified against the
 * repo's own `LICENSE.txt`, not assumed).
 *
 * Two-stage strategy:
 * 1. A small, closed, hand-picked list of Dutch place/street-name suffixes ([PLACE_SUFFIXES]) is
 *    tried first. Many real compound street names ("Kerkstraat", "Hoofdweg", "Julianalaan") are
 *    themselves individually listed as OpenTaal headwords, which would otherwise make stage 2
 *    treat them as a single atomic word instead of finding the boundary — exactly backwards for
 *    the purpose here. This directly targets the case that matters most for a routing app.
 * 2. A dictionary-based dynamic-programming segmenter that finds the split into the FEWEST real
 *    dictionary-word pieces, allowing a single short linking segment (tussenklank: s/e/en/er)
 *    between two real words — the standard Dutch compounding pattern (verkeer+s+informatie).
 *    Minimizing real pieces (not total pieces) avoids spurious splits like "centrum" ->
 *    "cent"+"rum" (both real words, wrong split): the whole, unsplit word already wins there as a
 *    single real piece.
 *
 * Deliberately NOT attempted: general secondary stress on trailing constituents. See
 * [DutchG2P]'s class doc for why.
 */
internal object DutchCompoundSegmenter {
    private const val MIN_WORD_LEN = 3
    private val LINKERS = listOf("s", "e", "en", "er")

    // NOT "dam", "berg", "dorp", "stad": tried once and caused real damage (Amsterdam ->
    // "amster"+"dam", Rotterdam -> "rotter"+"dam" -- "Amster"/"Rotter" are not real standalone
    // Dutch words, even though the wrong split happens to still land on roughly the right stress
    // for these two specific well-known cities by coincidence). Left out until each can be
    // checked properly rather than assumed safe by analogy to the others.
    private val PLACE_SUFFIXES = listOf(
        "straat", "laan", "weg", "plein", "gracht", "dijk", "kade", "singel", "hof", "hout",
        "dreef", "steeg", "erf", "park", "baan", "pad", "markt", "kwartier", "buurt", "wijk",
        "veen",
    )

    /** Real-word constituents of [word], in order. Returns `[word]` unchanged if no split was found. */
    fun constituents(word: String, dictionary: Set<String>): List<String> {
        for (suffix in PLACE_SUFFIXES) {
            if (word.endsWith(suffix) && word.length > suffix.length + 2) {
                val prefix = word.substring(0, word.length - suffix.length)
                return genericConstituents(prefix, dictionary) + suffix
            }
        }
        return genericConstituents(word, dictionary)
    }

    private fun genericConstituents(word: String, dictionary: Set<String>): List<String> {
        val split = segment(word, dictionary) ?: return listOf(word)
        val out = mutableListOf<String>()
        for ((piece, isReal) in split) {
            if (isReal) {
                out.add(piece)
            } else if (out.isNotEmpty()) {
                out[out.size - 1] = out.last() + piece
            }
        }
        return out
    }

    private data class Piece(val text: String, val isReal: Boolean)
    private data class Best(val numRealWords: Int, val numTotalPieces: Int, val split: List<Piece>)

    /**
     * The split of [word] into the fewest real dictionary-word pieces (ties broken by fewest
     * total pieces, including linkers), or `null` if no multi-piece decomposition exists.
     */
    private fun segment(word: String, dictionary: Set<String>): List<Piece>? {
        val n = word.length
        val memo = arrayOfNulls<Best?>(n + 1)
        val computed = BooleanArray(n + 1)

        fun best(i: Int): Best? {
            if (i == n) return Best(0, 0, emptyList())
            if (computed[i]) return memo[i]
            computed[i] = true

            var bestResult: Best? = null
            for (j in (i + MIN_WORD_LEN)..n) {
                val piece = word.substring(i, j)
                if (piece !in dictionary) continue

                // A null no-linker continuation must NOT skip the linker attempt below (the
                // original Python prototype had exactly this bug via an early `continue`,
                // masked there because its real-word test cases happened to have the whole
                // linked compound already listed as a single dictionary headword).
                val rest = best(j)
                if (rest != null) {
                    val candidate = Best(rest.numRealWords + 1, rest.numTotalPieces + 1, listOf(Piece(piece, true)) + rest.split)
                    if (bestResult == null || betterThan(candidate, bestResult)) bestResult = candidate
                }

                for (linker in LINKERS) {
                    val k = j + linker.length
                    if (k > n || word.substring(j, k) != linker) continue
                    val rest2 = best(k) ?: continue
                    val candidate2 = Best(
                        rest2.numRealWords + 1,
                        rest2.numTotalPieces + 2,
                        listOf(Piece(piece, true), Piece(linker, false)) + rest2.split,
                    )
                    if (bestResult == null || betterThan(candidate2, bestResult)) bestResult = candidate2
                }
            }
            memo[i] = bestResult
            return bestResult
        }

        val result = best(0)
        if (result == null || result.numRealWords < 2) return null
        return result.split
    }

    private fun betterThan(a: Best, b: Best): Boolean =
        a.numRealWords < b.numRealWords || (a.numRealWords == b.numRealWords && a.numTotalPieces < b.numTotalPieces)
}
