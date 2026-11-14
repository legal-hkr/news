package sh.hnet.news.content

import java.text.Normalizer

/**
 * Text as search compares it (SPEC §11): lower case, without diacritics ("lodz" finds "Łódź").
 * Every character becomes exactly one, so a place found in the folded text is the same place in
 * the original – that is how the matching passage is cut out and its match marked.
 */
object Fold {
    fun fold(text: String): String {
        val out = CharArray(text.length)
        for (index in text.indices) out[index] = fold(text[index])
        return String(out)
    }

    private fun fold(char: Char): Char {
        if (char.code < 0x80) return if (char in 'A'..'Z') char + 32 else char
        if (char.isSurrogate()) return char
        val lower = char.lowercaseChar()
        // Letters that don't decompose into a base letter and a mark
        return when (lower) {
            'ł' -> 'l'
            'ø' -> 'o'
            'đ' -> 'd'
            'ß' -> 's'
            'æ' -> 'a'
            'œ' -> 'o'
            else -> Normalizer.normalize(lower.toString(), Normalizer.Form.NFD)[0]
        }
    }

    /** A typed query: folded, its white space collapsed; empty when nothing but spaces was typed. */
    fun query(typed: String): String = fold(typed.trim().replace(Regex("""\s+"""), " "))
}
