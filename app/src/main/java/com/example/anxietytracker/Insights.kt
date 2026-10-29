package com.example.anxietytracker

import kotlin.math.abs

/** Linear-interpolated percentile ([p] in 0..100) of [values]; 0.0 when empty. */
fun percentile(values: List<Int>, p: Double): Double {
    if (values.isEmpty()) return 0.0
    val s = values.sorted()
    val pos = (p / 100.0) * (s.size - 1)
    val lo = pos.toInt()
    val hi = minOf(lo + 1, s.size - 1)
    return s[lo] + (s[hi] - s[lo]) * (pos - lo)
}

/** One comparison row: [diff] is the mean anxiety difference (positive = higher anxiety). */
data class DiffRow(val label: String, val n: Int, val diff: Double, val days: Int = 0)

data class Insights(
    val count: Int,
    val avg: Double,
    val sentence: String,
    val wakeSentence: String,
    val causes: List<DiffRow>,
    val otherCauses: Int,
    val factors: List<DiffRow>,
    /** Structured form of the hardest window behind [sentence] / [wakeSentence]; null when none stands out. */
    val hard: HardWindow? = null,
    val wakeHard: HardWindow? = null,
    val wakeCount: Int = 0,
    val wakeAvg: Double = 0.0,
)

/**
 * Hardest window found by [computeInsights]: [start] and [width] in hours, its [label], plain mean anxiety [avg],
 * [lift] (mean over days of the window's anxiety minus that day's mean) and the number of [days] it rests on.
 */
class HardWindow(val start: Int, val width: Int, val label: String, val avg: Double, val lift: Double, val days: Int)

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

    val hard = if (n < 10) null else hardestWindow(data, 3, 0..21, { it.dateTime.hour }) { windowLabel(it) }
    val sentence = if (n < 10) {
        "Log a few more times to see patterns."
    } else {
        hard?.let { "Your hardest time is usually ${it.text(avg)}" } ?: "No time of day stands out yet."
    }

    val wakeData = data.filter { it.hoursSinceWake != null }
    val wakeAvg = if (wakeData.isEmpty()) 0.0 else wakeData.map { it.anxiety!! }.average()
    val wakeHard = if (wakeData.size < 10) null else
        hardestWindow(wakeData, 2, 0..18, { it.hoursSinceWake!!.toInt() }) {
            "$it–${it + 2} h after you wake up"
        }
    val wakeSentence = if (wakeData.size < 10) {
        "Log a few more times with your wake time set to see how it relates to waking."
    } else {
        wakeHard?.let { "Relative to waking, it's usually hardest ${it.text(wakeAvg)}" } ?: "No time since waking stands out yet."
    }

    val dayMean = data.groupBy { it.dateTime.toLocalDate() }.mapValues { e -> e.value.map { it.anxiety!! }.average() }
    val byCause = data.flatMap { e -> e.causes.distinct().map { it to e } }.groupBy({ it.first }, { it.second })
    val causeRows = byCause.mapNotNull { (c, es) ->
        val perDay = es.groupBy { it.dateTime.toLocalDate() }
            .map { (d, l) -> l.map { it.anxiety!! - dayMean.getValue(d) }.average() }
        if (perDay.size >= 3) DiffRow(c, es.size, perDay.average(), perDay.size) else null
    }
    val causes = causeRows.sortedByDescending { it.diff }
    val other = byCause.size - causeRows.size

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
    return Insights(n, avg, sentence, wakeSentence, causes, other, factors, hard, wakeHard, wakeData.size, wakeAvg)
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

/** One row of the joy-by-dose table; averages are null when that side has no entries. */
data class DoseRow(val label: String, val anxN: Int, val anxAvg: Double?, val joyN: Int, val joyAvg: Double?)

data class JoyCauseRow(val label: String, val n: Int, val avg: Double)

data class JoyInsights(
    val count: Int,
    val avg: Double,
    val doses: List<DoseRow>,
    val causes: List<JoyCauseRow>,
)

/**
 * Joy summary over [all] (already limited to the chosen range). Entries without anxiety never touch the anxiety
 * averages; entries without joy never touch the joy ones. Returns null until at least 3 joy entries exist.
 */
fun computeJoyInsights(all: List<Entry>): JoyInsights? {
    val joyEntries = all.filter { it.joy != null }
    if (joyEntries.size < 3) return null
    val avg = joyEntries.map { it.joy!! }.average()

    // Group by the dose recorded on each entry: numeric value first (no dose number sorts last), then label.
    val groups = all.filter { it.medLabel != null }
        .groupBy { (it.medDose ?: Double.MAX_VALUE) to it.medLabel!! }
        .toSortedMap(compareBy<Pair<Double, String>>({ it.first }, { it.second }))
    val doses = groups.mapNotNull { (key, es) ->
        val anx = es.mapNotNull { it.anxiety }
        val joy = es.mapNotNull { it.joy }
        if (anx.size < 3 && joy.size < 3) null
        else DoseRow(key.second, anx.size, anx.takeIf { it.isNotEmpty() }?.average(),
            joy.size, joy.takeIf { it.isNotEmpty() }?.average())
    }

    val causes = joyEntries.flatMap { e -> e.causes.distinct().map { it to e.joy!! } }
        .groupBy({ it.first }, { it.second })
        .filter { it.value.size >= 2 }
        .map { JoyCauseRow(it.key, it.value.size, it.value.average()) }
        .sortedWith(compareByDescending<JoyCauseRow> { it.avg }.thenByDescending { it.n }.thenBy { it.label })
        .take(5)
    return JoyInsights(joyEntries.size, avg, doses, causes)
}

/** "<label> (avg x, vs y overall)." */
private fun HardWindow.text(overall: Double): String =
    "$label (avg ${"%.1f".format(avg)}, vs ${"%.1f".format(overall)} overall)."

/**
 * Best [width]-wide window (start in [starts], by [key]): per day the mean of (anxiety - that day's mean) of the
 * day's entries in the window, scored as the mean over days (each day counts once). Needs entries from >= 4 days
 * and a score >= 0.5, else null; ties go to more days.
 */
private fun hardestWindow(
    data: List<Entry>, width: Int, starts: IntRange, key: (Entry) -> Int, label: (Int) -> String,
): HardWindow? {
    val dayMean = data.groupBy { it.dateTime.toLocalDate() }.mapValues { e -> e.value.map { it.anxiety!! }.average() }
    var bestStart = -1
    var bestScore = 0.0
    var bestDays = 0
    var bestAvg = 0.0
    for (s in starts) {
        val w = data.filter { key(it) >= s && key(it) < s + width }
        val perDay = w.groupBy { it.dateTime.toLocalDate() }
            .map { (d, l) -> l.map { it.anxiety!! - dayMean.getValue(d) }.average() }
        if (perDay.size < 4) continue
        val score = perDay.average()
        val better = bestStart < 0 || score > bestScore + 1e-9 ||
            (abs(score - bestScore) <= 1e-9 && perDay.size > bestDays)
        if (better) { bestStart = s; bestScore = score; bestDays = perDay.size; bestAvg = w.map { it.anxiety!! }.average() }
    }
    if (bestStart < 0 || bestScore < 0.5) return null
    return HardWindow(bestStart, width, label(bestStart), bestAvg, bestScore, bestDays)
}

/** Min prompted rated entries / distinct days before time-of-day and factor analysis uses check-ins only. */
const val PROMPTED_ONLY_MIN_ENTRIES = 30
const val PROMPTED_ONLY_MIN_DAYS = 7

/** True when [rangeEntries] hold enough prompted rated entries (>= 30 on >= 7 distinct days) for a prompted-only analysis. */
fun promptedOnlyOk(rangeEntries: List<Entry>): Boolean {
    val p = rangeEntries.filter { it.isPrompted() && it.anxiety != null }
    return p.size >= PROMPTED_ONLY_MIN_ENTRIES && p.map { it.dateTime.toLocalDate() }.distinct().size >= PROMPTED_ONLY_MIN_DAYS
}

/**
 * [computeInsights] where the hardest windows and the factor rows come from prompted entries only when
 * [promptedOnlyOk]; causes always stay on all entries. Second value: whether prompted-only was used.
 */
fun computeInsightsFor(rangeEntries: List<Entry>): Pair<Insights, Boolean> {
    val base = computeInsights(rangeEntries)
    if (!promptedOnlyOk(rangeEntries)) return base to false
    val p = computeInsights(rangeEntries.filter { it.isPrompted() })
    return base.copy(count = p.count, avg = p.avg, sentence = p.sentence, wakeSentence = p.wakeSentence,
        factors = p.factors, hard = p.hard, wakeHard = p.wakeHard, wakeCount = p.wakeCount, wakeAvg = p.wakeAvg) to true
}

/** Mean anxiety [mean] over [n] entries; mean is null when n is 0. */
data class MeanN(val n: Int, val mean: Double?)

/**
 * Check-ins summary. [typical]/[windows] = prompted rated entries (windows Morning, Afternoon, Evening); [daily] =
 * typical-day mean for each of the last 7 days (oldest first, null = no check-ins); [spikes]/[topCauses] = manual
 * rated entries; [answered] of [total] resolved prompts; [skippedNearby] = mean anxiety of the manual entry nearest
 * each skipped/expired prompt (within 2 h). [hasAny] = any prompt or prompted entry in range.
 */
data class Checkins(
    val typical: MeanN, val windows: List<MeanN>, val daily: List<Double?>,
    val spikes: MeanN, val topCauses: List<Pair<String, Int>>,
    val answered: Int, val total: Int, val skippedNearby: MeanN, val hasAny: Boolean,
    val followAnswered: Int = 0, val followTotal: Int = 0,
)

fun computeCheckins(all: List<Entry>, prompts: List<PromptRec>, fromMs: Long): Checkins {
    val inRange = all.filter { it.ts >= fromMs && it.anxiety != null }
    val prompted = inRange.filter { it.isPrompted() }
    val manual = inRange.filter { it.isManual() }
    fun mn(l: List<Entry>) = MeanN(l.size, if (l.isEmpty()) null else l.map { it.anxiety!! }.average())
    val byId = prompts.associateBy { it.id }
    val win = prompted.groupBy { e ->
        byId[e.promptId]?.window?.coerceIn(0, 2) ?: e.dateTime.hour.let { if (it < 13) 0 else if (it < 18) 1 else 2 }
    }
    val today = java.time.LocalDate.now()
    val byDay = prompted.groupBy { it.dateTime.toLocalDate() }
    val daily = (6 downTo 0).map { k -> byDay[today.minusDays(k.toLong())]?.map { it.anxiety!! }?.average() }
    val causes = manual.flatMap { it.causes.distinct() }.groupingBy { it }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(3).map { it.key to it.value }
    val random = prompts.filter { !it.isFollowup() }
    val fu = prompts.filter { it.isFollowup() && it.scheduledTs >= fromMs && it.status != PROMPT_PENDING }
    val resolved = random.filter { it.scheduledTs >= fromMs && it.status != PROMPT_PENDING }
    val near = resolved.filter { it.status == PROMPT_SKIPPED || it.status == PROMPT_EXPIRED }.mapNotNull { p ->
        all.filter { it.isManual() && it.anxiety != null && abs(it.ts - p.scheduledTs) <= 2 * 3_600_000L }
            .minByOrNull { abs(it.ts - p.scheduledTs) }?.anxiety
    }
    return Checkins(mn(prompted), (0..2).map { mn(win[it].orEmpty()) }, daily, mn(manual), causes,
        resolved.count { it.status == PROMPT_ANSWERED }, resolved.size,
        MeanN(near.size, if (near.isEmpty()) null else near.average()),
        random.any { it.scheduledTs >= fromMs } || prompted.isNotEmpty(),
        fu.count { it.status == PROMPT_ANSWERED }, fu.size)
}
