package com.txdost

/**
 * Shared text helpers for the chat UI + ViewModel.
 * Keeps AI replies clean: no emoji, no leaked action tags.
 */
object TextUtils {

    /** True if the code point is an emoji / pictograph / decorative symbol. */
    fun isEmoji(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF ||   // emoji + symbols (surrogate pairs)
        cp in 0x2600..0x27BF ||     // misc symbols, dingbats (⚡✅❌⚠✗✓)
        cp in 0x2190..0x21FF ||     // arrows (→ ←)
        cp in 0x2500..0x257F ||     // box drawing (─│╔═╗║)
        cp in 0x2580..0x259F ||     // block elements
        cp in 0x25A0..0x25FF ||     // geometric shapes
        cp == 0x2B06 || cp == 0x2B07 || cp == 0x2B50 ||
        cp == 0x2B1B || cp == 0x2B1C || cp == 0x2B05 ||
        cp == 0x23F0 || cp == 0x23F1 || cp == 0x23F3 || cp == 0x23F8 ||
        cp == 0x23F9 || cp == 0x23FA || cp == 0x231B || cp == 0x2049 ||
        cp == 0x203C || cp == 0xFE0F || cp == 0x200D ||
        cp == 0x2022 || cp == 0x25CF || cp == 0x25CB

    /** Remove every emoji / decorative symbol, keeping normal text intact. */
    fun stripEmoji(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (!isEmoji(cp)) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** Tidy whitespace left behind after removing symbols. */
    fun tidy(s: String): String = s
        .replace(Regex("[ \t]+\n"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
