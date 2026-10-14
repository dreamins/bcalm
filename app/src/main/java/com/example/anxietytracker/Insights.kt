package com.example.anxietytracker

import kotlin.math.abs

/** One comparison row: [diff] is the mean anxiety difference (positive = higher anxiety). */
data class DiffRow(val label: String, val n: Int, val diff: Double)

data class Insights(
    val count: Int,
    val avg: Double,
    val sentence: String,
    val causes: List<DiffRow>,
    val otherCauses: Int,
    val factors: List<DiffRow>,
)

/** Typographic minus (U+2212). */
const val MINUS = "−"

fun fmtDiff(d: Double): String {
    val r = Math.round(d * 10) / 10.0
    return when {
        r > 0 -> "+" + "%.1f".format(r)
        r < 0 -> MINUS + "%.1f".format(-r)
        else -> "0.0"
    }
}

private fun hr12(h: Int): Int = (h % 12).let { if (it == 0) 12 else it }
private fun ampm(h: Int): String = if (h % 24 < 12) "am" else "pm"

/** Three-hour window starting at [start], e.g. "2–5 pm" or "10 am–1 pm". */
fun windowLabel(start: Int): String {
    val end = (start + 3) % 24
    val a = ampm(start)
    val b = ampm(end)
    return if (a == b) "${hr12(start)}–${hr12(end)} $a" else "${hr12(start)} $a–${hr12(end)} $b"
}

/** [data] must only hold entries with a non-null anxiety value. */
fun computeInsights(data: List<Entry>): Insights {
    val n = data.size
    val avg = if (n == 0) 0.0 else data.map { it.anxiety!! }.average()

    val sentence = if (n < 10) {
        "Log a few more times to see patterns."
    } else {
        var bestStart = -1
        var bestAvg = 0.0
        var bestN = 0
        for (s in 0..21) {
            val w = data.filter { it.dateTime.hour >= s && it.dateTime.hour < s + 3 }.map { it.anxiety!! }
            if (w.size < 3) continue
            val m = w.average()
            val better = bestStart < 0 || m > bestAvg + 1e-9 ||
                (abs(m - bestAvg) <= 1e-9 && w.size > bestN)
            if (better) { bestStart = s; bestAvg = m; bestN = w.size }
        }
        if (bestStart < 0 || bestAvg - avg < 0.3) {
            "No time of day stands out yet."
        } else {
            "Your hardest time is usually ${windowLabel(bestStart)} " +
                "(avg ${"%.1f".format(bestAvg)}, vs ${"%.1f".format(avg)} overall)."
        }
    }

    val byCause = data.flatMap { e -> e.causes.distinct().map { it to e.anxiety!! } }
        .groupBy({ it.first }, { it.second })
    val causes = byCause.filter { it.value.size >= 3 }
        .map { DiffRow(it.key, it.value.size, it.value.average() - avg) }
        .sortedByDescending { it.diff }
    val other = byCause.count { it.value.size < 3 }

    fun vals(p: (Entry) -> Boolean): List<Int> = data.filter(p).map { it.anxiety!! }
    fun cmp(label: String, a: List<Int>, b: List<Int>): DiffRow? =
        if (a.size >= 3 && b.size >= 3) DiffRow(label, a.size + b.size, a.average() - b.average()) else null

    val factors = listOfNotNull(
        cmp("After caffeine", vals { it.caffeine == true }, vals { it.caffeine == false }),
        cmp("Food in last 3h", vals { it.food == true }, vals { it.food == false }),
        cmp("Exercised that day", vals { it.exercise == true }, vals { it.exercise == false }),
        cmp("Partner tense vs good", vals { it.partner == 2 }, vals { it.partner == 0 }),
        cmp("Poor sleep (≤2 vs ≥4)", vals { (it.sleep ?: 99) <= 2 }, vals { (it.sleep ?: 0) >= 4 }),
    )
    return Insights(n, avg, sentence, causes, other, factors)
}
