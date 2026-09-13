package io.github.proairface.kotling2p

/**
 * Expands text into word tokens ready for per-word phoneme lookup: numbers become words and
 * common street-address abbreviations are spelled out. Deliberately scoped to what a
 * turn-by-turn routing app actually needs to speak (street types, directionals, unit
 * numbers, ordinals) rather than full general-purpose text normalization.
 *
 * Known limitation: multi-digit house numbers are always spelled in full ("twenty three
 * hundred forty"), not read colloquially in pairs the way people sometimes say them
 * ("twenty-three forty" for 2340) — unambiguous beats colloquial for a first version.
 */
object TextNormalizer {

    private val streetAbbreviations = mapOf(
        "ST" to "STREET", "AVE" to "AVENUE", "BLVD" to "BOULEVARD", "DR" to "DRIVE",
        "RD" to "ROAD", "LN" to "LANE", "CT" to "COURT", "PKWY" to "PARKWAY",
        "HWY" to "HIGHWAY", "PL" to "PLACE", "TER" to "TERRACE", "CIR" to "CIRCLE",
        "SQ" to "SQUARE", "APT" to "APARTMENT", "STE" to "SUITE",
    )

    private val directionalAbbreviations = mapOf(
        "N" to "NORTH", "S" to "SOUTH", "E" to "EAST", "W" to "WEST",
        "NE" to "NORTHEAST", "NW" to "NORTHWEST", "SE" to "SOUTHEAST", "SW" to "SOUTHWEST",
    )

    private val ones = listOf(
        "ZERO", "ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN", "EIGHT", "NINE",
        "TEN", "ELEVEN", "TWELVE", "THIRTEEN", "FOURTEEN", "FIFTEEN", "SIXTEEN",
        "SEVENTEEN", "EIGHTEEN", "NINETEEN",
    )
    private val tens = listOf(
        "", "", "TWENTY", "THIRTY", "FORTY", "FIFTY", "SIXTY", "SEVENTY", "EIGHTY", "NINETY",
    )

    // The standard English-ordinal trick: spell the number as a cardinal, then replace only
    // the last word with its ordinal form ("twenty" + "one" -> "twenty" + "first").
    private val cardinalToOrdinal = mapOf(
        "ZERO" to "ZEROTH", "ONE" to "FIRST", "TWO" to "SECOND", "THREE" to "THIRD",
        "FOUR" to "FOURTH", "FIVE" to "FIFTH", "SIX" to "SIXTH", "SEVEN" to "SEVENTH",
        "EIGHT" to "EIGHTH", "NINE" to "NINTH", "TEN" to "TENTH", "ELEVEN" to "ELEVENTH",
        "TWELVE" to "TWELFTH", "THIRTEEN" to "THIRTEENTH", "FOURTEEN" to "FOURTEENTH",
        "FIFTEEN" to "FIFTEENTH", "SIXTEEN" to "SIXTEENTH", "SEVENTEEN" to "SEVENTEENTH",
        "EIGHTEEN" to "EIGHTEENTH", "NINETEEN" to "NINETEENTH", "TWENTY" to "TWENTIETH",
        "THIRTY" to "THIRTIETH", "FORTY" to "FORTIETH", "FIFTY" to "FIFTIETH",
        "SIXTY" to "SIXTIETH", "SEVENTY" to "SEVENTIETH", "EIGHTY" to "EIGHTIETH",
        "NINETY" to "NINETIETH", "HUNDRED" to "HUNDREDTH", "THOUSAND" to "THOUSANDTH",
    )

    private val ordinalSuffix = Regex("^(\\d+)(ST|ND|RD|TH)$")
    private val numberWithLetterSuffix = Regex("^(\\d+)([A-Z]+)$")
    private val plainNumber = Regex("^\\d+$")

    /** Splits normalized text into uppercase word tokens, ready for phoneme lookup. */
    fun tokenize(text: String): List<String> =
        text.uppercase()
            .split(Regex("\\s+"))
            .map { it.trim { c -> !c.isLetterOrDigit() && c != '#' } }
            .filter { it.isNotEmpty() }
            .flatMap { expandToken(it) }

    private fun expandToken(token: String): List<String> {
        streetAbbreviations[token]?.let { return listOf(it) }
        directionalAbbreviations[token]?.let { return listOf(it) }
        if (token.startsWith("#")) return listOf("NUMBER") + expandToken(token.removePrefix("#"))
        ordinalSuffix.find(token)?.let { return spellOrdinal(it.groupValues[1].toLong()) }
        // Building/unit numbers like "221B": speak the number, then the letter suffix as its
        // own token rather than silently dropping or mis-reading it.
        numberWithLetterSuffix.find(token)?.let { m ->
            return spellCardinal(m.groupValues[1].toLong()) + m.groupValues[2]
        }
        if (plainNumber.matches(token)) return spellCardinal(token.toLong())
        return listOf(token)
    }

    private fun spellCardinal(n: Long): List<String> = when {
        n < 0 -> listOf("NEGATIVE") + spellCardinal(-n)
        n < 20 -> listOf(ones[n.toInt()])
        n < 100 -> {
            val tensWord = tens[(n / 10).toInt()]
            val remainder = n % 10
            if (remainder == 0L) listOf(tensWord) else listOf(tensWord) + spellCardinal(remainder)
        }
        n < 1000 -> {
            val head = listOf(ones[(n / 100).toInt()], "HUNDRED")
            val remainder = n % 100
            if (remainder == 0L) head else head + spellCardinal(remainder)
        }
        n < 1_000_000 -> {
            val head = spellCardinal(n / 1000) + "THOUSAND"
            val remainder = n % 1000
            if (remainder == 0L) head else head + spellCardinal(remainder)
        }
        else -> listOf(n.toString()) // beyond realistic address numbers — fall back verbatim
    }

    private fun spellOrdinal(n: Long): List<String> {
        val cardinal = spellCardinal(n)
        val ordinalLast = cardinalToOrdinal[cardinal.last()] ?: cardinal.last()
        return cardinal.dropLast(1) + ordinalLast
    }
}
