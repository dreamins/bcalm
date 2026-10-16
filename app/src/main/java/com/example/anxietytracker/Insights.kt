package com.example.anxietytracker

import kotlin.math.abs

/** One comparison row: [diff] is the mean anxiety difference (positive = higher anxiety). */
data class DiffRow(val label: String, val n: Int, val diff: Double)

data class Insights(
    val count: Int,
    val avg: Double,
    val sentence: String,
    val wakeSentence: String,
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

/**
 * [all] may include entries without an anxiety value: those are ignored for anxiety averages but still
 * supply the day-level flags (alcohol, cannabis).
 */
fun computeInsights(all: List<Entry>): Insights {
    val data = all.filter { it.anxiety != null }
    val n = data.size
    val avg = if (n == 0) 0.0 else data.map { it.anxiety!! }.average()

    val sentence = if (n < 10) {
        "Log a few more times to see patterns."
    } else {
        hardestWindow(data, avg, 3, 0..21, { it.dateTime.hour }) { windowLabel(it) }
            ?.let { "Your hardest time is usually $it" } ?: "No time of day stands out yet."
    }

    val wakeData = data.filter { it.hoursSinceWake != null }
    val wakeSentence = if (wakeData.size < 10) {
        "Log a few more times with your wake time set to see how it relates to waking."
    } else {
        hardestWindow(wakeData, wakeData.map { it.anxiety!! }.average(), 2, 0..18, { it.hoursSinceWake!!.toInt() }) {
            "$it–${it + 2} h after you wake up"
        }?.let { "Relative to waking, it's usually hardest $it" } ?: "No time since waking stands out yet."
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

    // Day-level alcohol/cannabis: latest answered entry of each calendar day.
    fun dayFlag(pick: (Entry) -> Boolean?): Map<Long, Boolean> =
        all.groupBy { it.dateTime.toLocalDate().toEpochDay() }.mapNotNull { (d, es) ->
            es.sortedBy { it.ts }.lastOrNull { pick(it) != null }?.let { d to pick(it)!! }
        }.toMap()
    val alcDay = dayFlag { it.alcohol }
    val canDay = dayFlag { it.cannabis }
    fun sameDay(m: Map<Long, Boolean>, v: Boolean) =
        vals { m[it.dateTime.toLocalDate().toEpochDay()] == v }
    fun nextDay(m: Map<Long, Boolean>, v: Boolean) =
        vals { m[it.dateTime.toLocalDate().toEpochDay() - 1] == v }

    val factors = listOfNotNull(
        cmp("After caffeine", vals { it.caffeine == true }, vals { it.caffeine == false }),
        cmp("Food in last 3h", vals { it.food == true }, vals { it.food == false }),
        cmp("Exercised that day", vals { it.exercise == true }, vals { it.exercise == false }),
        cmp("Alcohol days", sameDay(alcDay, true), sameDay(alcDay, false)),
        cmp("Day after alcohol", nextDay(alcDay, true), nextDay(alcDay, false)),
        cmp("Cannabis days", sameDay(canDay, true), sameDay(canDay, false)),
        cmp("Day after cannabis", nextDay(canDay, true), nextDay(canDay, false)),
        cmp("Partner tense vs good", vals { it.partner == 2 }, vals { it.partner == 0 }),
        cmp("Poor sleep (≤2 vs ≥4)", vals { (it.sleep ?: 99) <= 2 }, vals { (it.sleep ?: 0) >= 4 }),
    )
    return Insights(n, avg, sentence, wakeSentence, causes, other, factors)
}

/** One line of the night-sweats section: label on the left, plain-text value on the right. */
data class SweatLine(val label: String, val value: String)

data class SweatInsights(val nights: Int, val sweats: Int, val lines: List<SweatLine>)

/**
 * Night-sweats summary over calendar days at or after [fromMs] (0 = all). A record on day D describes the
 * night between D-1 and D, so the "day before" anxiety, alcohol, cannabis and medication dose come from
 * day D-1 (dose falls back to D). One value per day: the latest entry of that day with the field set.
 * Returns null until at least 3 nights are answered.
 */
fun computeSweatInsights(all: List<Entry>, fromMs: Long = 0L): SweatInsights? {
    val byDay = all.groupBy { it.dateTime.toLocalDate().toEpochDay() }
    val anxDay = byDay.mapNotNull { (d, es) ->
        es.mapNotNull { it.anxiety }.takeIf { it.isNotEmpty() }?.let { d to it.average() }
    }.toMap()
    class Night(val day: Long, val sweat: Boolean, val alcohol: Boolean?, val cannabis: Boolean?, val dose: Entry?)
    fun lastOn(d: Long, pick: (Entry) -> Boolean?): Boolean? =
        byDay[d]?.sortedBy { it.ts }?.lastOrNull { pick(it) != null }?.let { pick(it) }
    fun doseOn(d: Long): Entry? = byDay[d]?.sortedBy { it.ts }?.lastOrNull { it.medDose != null }
    val nights = byDay.toSortedMap().mapNotNull { (d, es) ->
        val sorted = es.sortedBy { it.ts }
        if (sorted.last().ts < fromMs) return@mapNotNull null
        val sweat = sorted.lastOrNull { it.nightSweats != null }?.nightSweats ?: return@mapNotNull null
        Night(d, sweat,
            lastOn(d - 1) { it.alcohol },
            lastOn(d - 1) { it.cannabis },
            doseOn(d - 1) ?: doseOn(d))
    }
    if (nights.size < 3) return null

    fun rate(l: List<Night>) = "${l.count { it.sweat }}/${l.size}"
    fun pct(l: List<Night>) = "${Math.round(100.0 * l.count { it.sweat } / l.size)}%"
    fun yesNo(label: String, pick: (Night) -> Boolean?): SweatLine? {
        val y = nights.filter { pick(it) == true }
        val n = nights.filter { pick(it) == false }
        return if (y.size >= 2 && n.size >= 2)
            SweatLine(label, "${pct(y)} vs ${pct(n)}  (${rate(y)} vs ${rate(n)})") else null
    }

    val lines = ArrayList<SweatLine>()
    yesNo("After alcohol") { it.alcohol }?.let { lines += it }
    yesNo("After cannabis") { it.cannabis }?.let { lines += it }

    val prevS = nights.filter { it.sweat }.mapNotNull { anxDay[it.day - 1] }
    val prevN = nights.filter { !it.sweat }.mapNotNull { anxDay[it.day - 1] }
    if (prevS.size >= 2 && prevN.size >= 2)
        lines += SweatLine("Higher-anxiety days",
            "avg ${"%.1f".format(prevS.average())} vs ${"%.1f".format(prevN.average())} the day before")

    val byDose = nights.mapNotNull { n ->
        val e = n.dose
        val v = e?.medDose
        val lbl = e?.medLabel
        if (v == null || lbl == null) null else (v to lbl) to n
    }.groupBy({ it.first }, { it.second })
        .filter { it.value.size >= 2 }
        .toSortedMap(compareBy<Pair<Double, String>>({ it.first }, { it.second }))
    if (byDose.size >= 2)
        lines += SweatLine("By medication dose",
            byDose.entries.joinToString(" · ") { "${it.key.second} ${rate(it.value)}" })

    return SweatInsights(nights.size, nights.count { it.sweat }, lines)
}

/** Best [width]-wide window (start in [starts], by [key]) with >= 3 entries and mean >= 0.3 above [avg]; "<label> (avg x, vs y overall)." or null. */
private fun hardestWindow(
    data: List<Entry>, avg: Double, width: Int, starts: IntRange, key: (Entry) -> Int, label: (Int) -> String,
): String? {
    var bestStart = -1
    var bestAvg = 0.0
    var bestN = 0
    for (s in starts) {
        val w = data.filter { key(it) >= s && key(it) < s + width }.map { it.anxiety!! }
        if (w.size < 3) continue
        val m = w.average()
        val better = bestStart < 0 || m > bestAvg + 1e-9 ||
            (abs(m - bestAvg) <= 1e-9 && w.size > bestN)
        if (better) { bestStart = s; bestAvg = m; bestN = w.size }
    }
    if (bestStart < 0 || bestAvg - avg < 0.3) return null
    return "${label(bestStart)} (avg ${"%.1f".format(bestAvg)}, vs ${"%.1f".format(avg)} overall)."
}
