package com.qiuminal.juicedict.engine

/**
 * Morphological fallback candidates, mirroring sdcv's `LookupSimilarWord`
 * suffix handling for English words: trailing s/d, ly, ing, es, ed,
 * ied->y, ies->y, er, including doubled-consonant reduction
 * (running -> run, stopped -> stop).
 */
object Morphology {

    fun expand(word: String): List<String> {
        if (word.length <= 1) return emptyList()
        val out = ArrayList<String>(8)
        val lower = word.lowercase()

        fun add(candidate: String) {
            if (candidate.isNotEmpty() && candidate != word && !out.contains(candidate)) out.add(candidate)
        }

        /** running -> runn -> run; stopped -> stopp -> stop. */
        fun doubledCut(stem: String): String? {
            val n = stem.length
            if (n >= 4 && stem[n - 1] == stem[n - 2] && !isVowel(stem[n - 2]) && isVowel(stem[n - 3])) {
                return stem.dropLast(1)
            }
            return null
        }

        // trailing 's' / 'S' / "ed" (sdcv cuts a single char here)
        if (lower.endsWith("s") || lower.endsWith("ed")) add(word.dropLast(1))

        // cut 'ly'
        if (lower.endsWith("ly") && word.length > 2) {
            val stem = word.dropLast(2)
            doubledCut(stem)?.let { add(it) }
            add(stem)
        }

        // cut 'ing' (also try 'e' restored)
        if (lower.endsWith("ing") && word.length > 3) {
            val stem = word.dropLast(3)
            doubledCut(stem)?.let { add(it) }
            add(stem)
            add(stem + "e")
        }

        // cut 'es' after s/x/o/ch/sh
        if (lower.endsWith("es") && word.length > 3 &&
            (lower.endsWith("ses") || lower.endsWith("xes") || lower.endsWith("oes") ||
                lower.endsWith("ches") || lower.endsWith("shes"))
        ) {
            add(word.dropLast(2))
        }

        // cut 'ed' (with doubled-consonant reduction)
        if (lower.endsWith("ed") && word.length > 3) {
            val stem = word.dropLast(2)
            doubledCut(stem)?.let { add(it) }
            add(stem)
        }

        // 'ied' -> 'y'
        if (lower.endsWith("ied") && word.length > 3) add(word.dropLast(3) + "y")

        // 'ies' -> 'y'
        if (lower.endsWith("ies") && word.length > 3) add(word.dropLast(3) + "y")

        // cut 'er'
        if (lower.endsWith("er") && word.length > 2) {
            val stem = word.dropLast(2)
            doubledCut(stem)?.let { add(it) }
            add(stem)
        }
        return out
    }

    private fun isVowel(c: Char): Boolean = when (c.lowercaseChar()) {
        'a', 'e', 'i', 'o', 'u' -> true
        else -> false
    }
}
