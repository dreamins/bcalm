package com.example.anxietytracker

import kotlin.math.exp

/** A cause with how many (relevant) entries used it; [uses] is 0 for never-used causes. */
data class RankedCause(val name: String, val uses: Int)

/**
 * Ranks [causes] by recency-weighted frequency over [entries]: score = sum of exp(-ageDays / 14)
 * for each entry containing the cause. With [joyOnly] only entries with a joy rating count;
 * otherwise only entries with an anxiety value (falling back to all entries if that subset
 * never uses any listed cause). Ties and never-used causes keep their list order.
 */
fun rankCauses(entries: List<Entry>, causes: List<String>, joyOnly: Boolean, now: Long): List<RankedCause> {
    fun tally(subset: List<Entry>): Pair<Map<String, Double>, Map<String, Int>> {
        val score = HashMap<String, Double>()
        val count = HashMap<String, Int>()
        for (e in subset) {
            val ageDays = ((now - e.ts) / 86_400_000.0).coerceAtLeast(0.0)
            val w = exp(-ageDays / 14.0)
            for (c in e.causes.distinct()) {
                score[c] = (score[c] ?: 0.0) + w
                count[c] = (count[c] ?: 0) + 1
            }
        }
        return score to count
    }
    val primary = if (joyOnly) entries.filter { it.joy != null } else entries.filter { it.anxiety != null }
    var (score, count) = tally(primary)
    if (causes.none { it in count }) {
        val all = tally(entries)
        score = all.first; count = all.second
    }
    return causes.sortedByDescending { score[it] ?: 0.0 }   // stable: ties keep list order
        .map { RankedCause(it, count[it] ?: 0) }
}
