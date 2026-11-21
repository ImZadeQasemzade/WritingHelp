package com.writinghelp.rewrite.voice.core

/** Word-level diff for previewing a smart edit. Pure Kotlin, unit tested. */
object WordDiff {

    enum class Kind { SAME, REMOVED, ADDED }

    data class Segment(val text: String, val kind: Kind)

    /** Words and the whitespace between them, so joining the tokens gives back the text. */
    private val TOKEN = Regex("\\s+|[^\\s]+")

    /** Above this many LCS cells, show the whole old text as removed and the new as added. */
    private const val MAX_CELLS = 300_000

    fun diff(old: String, new: String): List<Segment> {
        val a = TOKEN.findAll(old).map { it.value }.toList()
        val b = TOKEN.findAll(new).map { it.value }.toList()
        if (a.isEmpty() && b.isEmpty()) return emptyList()
        if (a.size.toLong() * b.size > MAX_CELLS) {
            return listOfNotNull(
                old.takeIf { it.isNotEmpty() }?.let { Segment(it, Kind.REMOVED) },
                new.takeIf { it.isNotEmpty() }?.let { Segment(it, Kind.ADDED) },
            )
        }
        // lcs[i][j] = LCS length of a[i..] and b[j..]
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = ArrayList<Segment>()
        fun add(text: String, kind: Kind) {
            val last = out.lastOrNull()
            if (last != null && last.kind == kind) out[out.size - 1] = Segment(last.text + text, kind)
            else out += Segment(text, kind)
        }
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { add(a[i], Kind.SAME); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { add(a[i], Kind.REMOVED); i++ }
                else -> { add(b[j], Kind.ADDED); j++ }
            }
        }
        while (i < a.size) add(a[i++], Kind.REMOVED)
        while (j < b.size) add(b[j++], Kind.ADDED)
        return out
    }
}
