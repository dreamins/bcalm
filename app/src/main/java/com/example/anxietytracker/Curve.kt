package com.example.anxietytracker

import java.time.LocalDate
import kotlin.math.ln
import kotlin.math.pow

/** Grid step of the day curves, in minutes. */
const val CURVE_STEP = 15
/** Fade half-life (hours) used until >= 5 fades are measured. */
const val DEFAULT_HALF_LIFE_H = 2.0

/** Piecewise-linear baseline over minute of day (flat beyond the first/last point); one point = constant. */
class BaselineFn(private val xs: DoubleArray, private val ys: DoubleArray) {
    fun at(m: Double): Double {
        if (xs.size == 1 || m <= xs[0]) return ys[0]
        for (i in 1 until xs.size) {
            if (m <= xs[i]) return ys[i - 1] + (ys[i] - ys[i - 1]) * (m - xs[i - 1]) / (xs[i] - xs[i - 1])
        }
        return ys[ys.size - 1]
    }
}

/** One profile slot: [mean] of the per-day means, [p10]/[p90] across days, mean [base]line there, number of [days]. */
class ProfilePt(val mean: Double, val p10: Double, val p90: Double, val base: Double, val days: Int)

/** Best window of a profile: first slot [start] and its [mean]. */
class CurveWindow(val start: Int, val mean: Double)

/** Cause row of the modeled patterns: [hours] attributed to [label] over [days] days, [diff] vs the rest of those days. */
class CurveCause(val label: String, val hours: Double, val days: Int, val diff: Double)

/** Patterns read off the curve: [causes] (>= 3 days, sorted by diff), [otherCauses] on too few days, [factors]. */
class CurvePatterns(val causes: List<CurveCause>, val otherCauses: Int, val factors: List<DiffRow>)

/**
 * Modeled anxiety: [baseline] plus ratings that shape the curve (see [buildCurve]); [halfLife] hours is the fade rate
 * ([halfLifeFits] = number of measured fades, 0 = default). [days] = per date a list of (minuteOfDay, value) on a
 * 15-minute grid covering the whole waking day. Every statistic below is computed from that grid, never from the
 * ratings themselves, so the way ratings were chosen (mostly when upset) does not bias them.
 * [hourProfile] has 24 slots (hour of day), [wakeProfile] 20 (hours since waking); null = fewer than 3 days.
 * [patterns] = causes and factors read off the curve.
 */
class CurveModel(
    val baseline: BaselineFn,
    val halfLife: Double,
    val halfLifeFits: Int,
    val baselineFromCheckins: Boolean,
    val days: Map<LocalDate, List<Pair<Int, Double>>>,
    val hourProfile: List<ProfilePt?>,
    val wakeProfile: List<ProfilePt?>,
    val hoursAbove5: Map<LocalDate, Double>,
    val hardest: CurveWindow?,
    val wakeHardest: CurveWindow?,
    val patterns: CurvePatterns = CurvePatterns(emptyList(), 0, emptyList()),
) {
    /** Mean of each day's curve grid values. */
    val dailyMean: Map<LocalDate, Double> = days.mapValues { (_, pts) -> pts.map { it.second }.average() }

    /** Time-weighted mean anxiety: mean over days of [dailyMean] (each day equally); null with no days. */
    val timeWeightedMean: Double? = if (dailyMean.isEmpty()) null else dailyMean.values.average()

    /** Per day (mean, p90, p10) of the curve grid values. */
    val dailyBand: Map<LocalDate, Triple<Double, Double, Double>> = days.mapValues { (d, pts) ->
        val s = pts.map { it.second }.sorted()
        Triple(dailyMean.getValue(d), pctD(s, 90.0), pctD(s, 10.0))
    }
}

fun Entry.minuteOfDay(): Int = dateTime.let { it.hour * 60 + it.minute }

private fun median(v: List<Double>): Double {
    val s = v.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
}

private fun pctD(sorted: List<Double>, p: Double): Double {
    val pos = (p / 100.0) * (sorted.size - 1)
    val lo = pos.toInt()
    val hi = minOf(lo + 1, sorted.size - 1)
    return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
}

private fun constant(v: Double) = BaselineFn(doubleArrayOf(0.0), doubleArrayOf(v))

/** Slot k of [n] = mean over days of that day's mean curve value in the slot (days with >= 2 grid points in it). */
private fun profileOf(
    n: Int, days: Map<LocalDate, List<Pair<Int, Double>>>, wakes: Map<LocalDate, Int>, base: BaselineFn, byWake: Boolean,
): List<ProfilePt?> {
    val vals = List(n) { ArrayList<Double>() }
    val bases = List(n) { ArrayList<Double>() }
    for ((d, pts) in days) {
        val wake = if (byWake) (wakes[d] ?: continue) else 0
        val sum = DoubleArray(n)
        val bsum = DoubleArray(n)
        val cnt = IntArray(n)
        for ((m, v) in pts) {
            if (byWake && m < wake) continue
            val k = if (byWake) (m - wake) / 60 else m / 60
            if (k < 0 || k >= n) continue
            sum[k] += v
            bsum[k] += base.at(m.toDouble())
            cnt[k]++
        }
        for (k in 0 until n) if (cnt[k] >= 2) { vals[k] += sum[k] / cnt[k]; bases[k] += bsum[k] / cnt[k] }
    }
    return List(n) { k ->
        val v = vals[k]
        if (v.size < 3) null else {
            val s = v.sorted()
            ProfilePt(v.average(), pctD(s, 10.0), pctD(s, 90.0), bases[k].average(), v.size)
        }
    }
}

/** Best [width]-slot window whose mean is >= overall profile mean + 0.5; null when none. */
private fun hardestOf(profile: List<ProfilePt?>, width: Int): CurveWindow? {
    val ok = profile.filterNotNull()
    if (ok.isEmpty()) return null
    val overall = ok.map { it.mean }.average()
    var best: CurveWindow? = null
    for (s in 0..profile.size - width) {
        val w = profile.subList(s, s + width)
        if (w.any { it == null }) continue
        val m = w.map { it!!.mean }.average()
        val b = best
        if (m >= overall + 0.5 && (b == null || m > b.mean)) best = CurveWindow(s, m)
    }
    return best
}

/**
 * Baseline over minute of day from [rated] entries (sorted): median of prompted check-ins only (follow-ups excluded)
 * once there are >= 5; per time-of-day window (morning/afternoon/evening) when that window has >= 3 check-ins, else
 * the overall check-in median. Without enough check-ins: the 25th percentile of non-follow-up ratings (second value
 * false; likely too high because logs skew toward bad moments).
 */
private fun baselineOf(rated: List<Entry>): Pair<BaselineFn, Boolean> {
    val prompted = rated.filter { it.isPrompted() }
    val fromCheckins = prompted.size >= 5
    val base = if (fromCheckins) {
        val pts = ArrayList<Pair<Double, Double>>()
        for ((w, m) in listOf(0 to 600.0, 1 to 900.0, 2 to 1200.0)) {
            val v = prompted.filter { e -> e.dateTime.hour.let { if (it < 13) 0 else if (it < 18) 1 else 2 } == w }
                .map { it.anxiety!!.toDouble() }
            if (v.size >= 3) pts += m to median(v)
        }
        if (pts.isEmpty()) constant(median(prompted.map { it.anxiety!!.toDouble() }))
        else BaselineFn(DoubleArray(pts.size) { pts[it].first }, DoubleArray(pts.size) { pts[it].second })
    } else rated.filter { !it.isFollowup() }.let { l -> constant(if (l.isEmpty()) 2.0 else percentile(l.map { it.anxiety!! }, 25.0)) }
    return base to fromCheckins
}

/** The baseline the curve model would use for [entries] (prompted-only; see [buildCurve]). */
fun currentBaseline(entries: List<Entry>): BaselineFn = baselineOf(entries.filter { it.anxiety != null }.sortedBy { it.ts }).first

/**
 * Half-life fits (hours) with the date of the later rating, from consecutive same-day pairs that look like a fade back
 * toward baseline. Only pairs whose SECOND rating is a check-in or follow-up count: when the user picks the moment
 * (manual to manual) the pair is censored, since people stop logging once calm.
 */
private fun fadeFits(rated: List<Entry>, base: BaselineFn): List<Pair<LocalDate, Double>> {
    val fits = ArrayList<Pair<LocalDate, Double>>()
    for (i in 1 until rated.size) {
        val e0 = rated[i - 1]
        val e1 = rated[i]
        if (e0.dateTime.toLocalDate() != e1.dateTime.toLocalDate()) continue
        val gap = (e1.ts - e0.ts) / 60000.0
        if (gap < 10.0 || gap > 360.0) continue
        if (!e1.isPrompted() && !e1.isFollowup()) continue
        val b0 = base.at(e0.minuteOfDay().toDouble())
        val b1 = base.at(e1.minuteOfDay().toDouble())
        val a0 = e0.anxiety!!
        val a1 = e1.anxiety!!
        if (a0 < b0 + 2.0 || a1 <= b1 || a1 >= a0) continue
        val ratio = (a0 - b0) / (a1 - b1)
        if (ratio <= 1.0001) continue
        fits += e1.dateTime.toLocalDate() to gap / 60.0 * ln(2.0) / ln(ratio)
    }
    return fits
}

/** Per ISO week (Monday start, oldest first) with >= 3 fits: median half-life in hours, clamped like the model. */
fun weeklyHalfLives(entries: List<Entry>, base: BaselineFn): List<Pair<LocalDate, Double>> =
    fadeFits(entries.filter { it.anxiety != null }.sortedBy { it.ts }, base)
        .groupBy { it.first.minusDays(it.first.dayOfWeek.value - 1L) }
        .filter { it.value.size >= 3 }.entries.sortedBy { it.key }
        .map { (w, l) -> w to median(l.map { it.second }).coerceIn(0.5, 6.0) }

/** Grid point of a day curve: value [v]; [k] = index of the rating it is attributed to: latest at or before it, before the next rating and < 2 h after it (-1 none). */
private class GPt(val v: Double, val k: Int)

/**
 * Builds the modeled curve. Ratings only shape it; all statistics come from the 15-minute grid.
 * Days: every local date with a rating, plus every date with a random check-in prompt (window < 3, not pending),
 * which is all-baseline when it has no rating (pass [prompts] already limited to the range).
 * Span of a day: from its wake time (else 07:00; never later than the first rating) to max(23:00, last rating + 30 min),
 * capped at 24:00. Before the first rating and after fading the curve sits at baseline B(t).
 * Between consecutive ratings k, k+1 of a day, gap <= 6 h:
 *  - falling or level (a_k+1 <= plain decay at t_k+1): pinned decay B(t) + (a_k - B_k) * r^((t-t_k)/gap), r = (a_k+1 - B_k+1) /
 *    (a_k - B_k) when both differences are > 0.05 and r is in (0,1]; otherwise a straight line between the ratings;
 *  - rising: plain decay from a_k until t_k+1 - min(30 min, gap), then a linear ramp up to a_k+1.
 * Gap > 6 h or last rating of the day: plain decay B(t) + (a_k - B_k) * 2^(-(t-t_k)/H) toward baseline. Clamped to 1..10.
 * [CurveModel.patterns]: each grid point inherits causes/flags of the latest rating at or before it, while inside that
 * rating's segment (until the next rating) and less than 2 h after it, whatever the curve height there.
 */
fun buildCurve(entries: List<Entry>, prompts: List<PromptRec> = emptyList()): CurveModel {
    val rated = entries.filter { it.anxiety != null }.sortedBy { it.ts }
    val (base, fromCheckins) = baselineOf(rated)
    val fits = fadeFits(rated, base).map { it.second }
    val half = if (fits.size >= 5) median(fits).coerceIn(0.5, 6.0) else DEFAULT_HALF_LIFE_H
    val nFits = if (fits.size >= 5) fits.size else 0

    val ratedByDay = rated.groupBy { it.dateTime.toLocalDate() }
    val zone = java.time.ZoneId.systemDefault()
    val promptDays = prompts.filter { it.window < 3 && it.status != PROMPT_PENDING }
        .map { java.time.Instant.ofEpochMilli(it.scheduledTs).atZone(zone).toLocalDate() }
    val allDays = java.util.TreeSet<LocalDate>(ratedByDay.keys).apply { addAll(promptDays) }

    val days = java.util.TreeMap<LocalDate, List<Pair<Int, Double>>>()
    val gpts = HashMap<LocalDate, List<GPt>>()
    val wakes = HashMap<LocalDate, Int>()
    val above = java.util.TreeMap<LocalDate, Double>()
    for (d in allDays) {
        val es = ratedByDay[d].orEmpty()
        val ms = es.map { it.minuteOfDay() }
        val av = es.map { it.anxiety!!.toDouble() }
        val wake = es.firstNotNullOfOrNull { it.wakeMin }
        if (wake != null) wakes[d] = wake
        val first = ms.firstOrNull() ?: 1440
        val start = (wake?.let { minOf(it, first) } ?: minOf(420, first)) / CURVE_STEP * CURVE_STEP
        val end = minOf(1440, maxOf(1380, (ms.lastOrNull() ?: 0) + 30))
        val pts = ArrayList<Pair<Int, Double>>()
        val gp = ArrayList<GPt>()
        var k = -1
        var m = start
        while (m <= end && m < 1440) {
            while (k + 1 < ms.size && ms[k + 1] <= m) k++
            val b = base.at(m.toDouble())
            var v = b
            var seg = -1
            if (k >= 0) {
                val tk = ms[k]
                val d0 = av[k] - base.at(tk.toDouble())
                fun plain(t: Int) = base.at(t.toDouble()) + d0 * 2.0.pow(-(t - tk) / (60.0 * half))
                v = plain(m)
                val hasNext = k + 1 < ms.size
                val nx = if (hasNext) ms[k + 1] else 0
                val gap = nx - tk
                if (hasNext && gap in 1..360) {
                    val a1 = av[k + 1]
                    if (a1 <= plain(nx)) {
                        val d1 = a1 - base.at(nx.toDouble())
                        val r = d1 / d0
                        v = if (d0 > 0.05 && d1 > 0.05 && r > 0.0 && r <= 1.0) b + d0 * r.pow((m - tk).toDouble() / gap)
                        else av[k] + (a1 - av[k]) * (m - tk) / gap
                    } else {
                        val ts = nx - minOf(30, gap)
                        if (m > ts) { val p0 = plain(ts); v = p0 + (a1 - p0) * (m - ts) / (nx - ts) }
                    }
                }
                if (m < (if (hasNext) minOf(nx, tk + 120) else tk + 120)) seg = k
            }
            v = v.coerceIn(1.0, 10.0)
            pts += m to v
            gp += GPt(v, seg)
            m += CURVE_STEP
        }
        days[d] = pts
        gpts[d] = gp
        above[d] = pts.count { it.second > 5.0 } * 0.25
    }
    val hourP = profileOf(24, days, wakes, base, false)
    val wakeP = profileOf(20, days, wakes, base, true)
    return CurveModel(base, half, nFits, fromCheckins, days, hourP, wakeP, above,
        hardestOf(hourP, 3), hardestOf(wakeP, 2), curvePatterns(ratedByDay, gpts, entries))
}

/** Causes and factors read off the curve grid (see [buildCurve]); each day counts once, no rating is used directly. */
private fun curvePatterns(es: Map<LocalDate, List<Entry>>, gp: Map<LocalDate, List<GPt>>, all: List<Entry>): CurvePatterns {
    val dm = gp.mapValues { (_, l) -> l.map { it.v }.average() }
    val causeSet = es.values.flatten().flatMap { it.causes }.toSet()
    val rows = ArrayList<CurveCause>()
    for (c in causeSet) {
        val perDay = ArrayList<Double>()
        var n = 0
        for ((d, l) in gp) {
            val de = es[d] ?: continue
            val sel = l.filter { it.k >= 0 && c in de[it.k].causes }
            if (sel.isEmpty()) continue
            n += sel.size
            perDay += sel.map { it.v }.average() - dm.getValue(d)
        }
        if (perDay.size >= 3) rows += CurveCause(c, n * CURVE_STEP / 60.0, perDay.size, perDay.average())
    }

    val byDay = all.groupBy { it.dateTime.toLocalDate() }
    fun dayFlag(pick: (Entry) -> Boolean?): Map<LocalDate, Boolean> =
        byDay.mapNotNull { (d, l) -> l.sortedBy { it.ts }.lastOrNull { pick(it) != null }?.let { d to pick(it)!! } }.toMap()
    val alc = dayFlag { it.alcohol }
    val can = dayFlag { it.cannabis }
    val exercise = byDay.mapNotNull { (d, l) ->
        val v = l.mapNotNull { it.exercise }
        if (v.isEmpty()) null else d to v.any { it }
    }.toMap()
    val sleep = byDay.mapNotNull { (d, l) ->
        l.sortedBy { it.ts }.lastOrNull { it.sleep != null }?.sleep?.let { s -> if (s <= 2) d to true else if (s >= 4) d to false else null }
    }.toMap()

    fun cmp(label: String, flag: (LocalDate, List<Entry>, Int) -> Boolean?): DiffRow? {
        val yes = ArrayList<Double>()
        val no = ArrayList<Double>()
        for ((d, l) in gp) {
            val de = es[d].orEmpty()
            val a = ArrayList<Double>()
            val b = ArrayList<Double>()
            for (p in l) {
                val f = flag(d, de, p.k)
                if (f == true) a += p.v else if (f == false) b += p.v
            }
            if (a.isNotEmpty()) yes += a.average()
            if (b.isNotEmpty()) no += b.average()
        }
        return if (yes.size >= 3 && no.size >= 3) DiffRow(label, yes.size + no.size, yes.average() - no.average()) else null
    }
    fun entryFlag(pick: (Entry) -> Boolean?): (LocalDate, List<Entry>, Int) -> Boolean? = { _, de, k -> if (k < 0) null else pick(de[k]) }
    fun dayLevel(m: Map<LocalDate, Boolean>, shift: Long = 0): (LocalDate, List<Entry>, Int) -> Boolean? = { d, _, _ -> m[d.minusDays(shift)] }

    val factors = listOfNotNull(
        cmp("After caffeine", entryFlag { it.caffeine }),
        cmp("Food in last 3h", entryFlag { it.food }),
        cmp("Exercised that day", dayLevel(exercise)),
        cmp("Alcohol days", dayLevel(alc)),
        cmp("Day after alcohol", dayLevel(alc, 1)),
        cmp("Cannabis days", dayLevel(can)),
        cmp("Day after cannabis", dayLevel(can, 1)),
        cmp("Partner tense vs good", entryFlag { when (it.partner) { 2 -> true; 0 -> false; else -> null } }),
        cmp("Poor sleep (\u22642 vs \u22654)", dayLevel(sleep)),
    )
    return CurvePatterns(rows.sortedByDescending { it.diff }, causeSet.size - rows.size, factors)
}

/** One rating after a spike: [hours] since it, anxiety [value], and whether it came from a follow-up. */
class RecoveryObs(val hours: Float, val value: Int, val followup: Boolean)

/**
 * Recovery from hard moments. [episodes] = manual spikes (>= 5); [observed] of them got back within baseline + 1
 * ([censored] never did). [medianMin] = median minutes to get there (null when fewer than 3 observed); [spikeMedian] /
 * [baseMedian] = typical spike height and baseline at spike time; [withTool]/[without] = median minutes with / without
 * a coping tool (null when fewer than 3 observed episodes on either side); [weekly] = half-life by week (>= 2 weeks, else empty).
 */
class Recovery(
    val episodes: Int, val observed: Int, val censored: Int, val medianMin: Double?,
    val spikeMedian: Double, val baseMedian: Double, val obs: List<RecoveryObs>,
    val withTool: Double?, val without: Double?, val weekly: List<Pair<LocalDate, Double>>,
)

fun computeRecovery(entries: List<Entry>, model: CurveModel): Recovery {
    val rated = entries.filter { it.anxiety != null }.sortedBy { it.ts }
    val spikes = rated.filter { it.isManual() && it.anxiety!! >= 5 }
    val obs = ArrayList<RecoveryObs>()
    val times = ArrayList<Pair<Entry, Double?>>()   // spike, minutes to recovery (null = censored)
    for ((i, s) in spikes.withIndex()) {
        val next = spikes.getOrNull(i + 1)?.ts ?: Long.MAX_VALUE
        val day = s.dateTime.toLocalDate()
        val after = rated.filter { it.ts > s.ts && it.ts < next && it.ts - s.ts <= 6 * 3_600_000L && it.dateTime.toLocalDate() == day }
        after.forEach { obs += RecoveryObs((it.ts - s.ts) / 3_600_000f, it.anxiety!!, it.isFollowup()) }
        val back = after.firstOrNull { it.anxiety!! <= model.baseline.at(it.minuteOfDay().toDouble()) + 1.0 }
        times += s to back?.let { (it.ts - s.ts) / 60000.0 }
    }
    val done = times.filter { it.second != null }
    fun med(l: List<Pair<Entry, Double?>>) = if (l.size >= 3) median(l.map { it.second!! }) else null
    val tool = done.filter { it.first.tools.isNotEmpty() || it.first.after != null }
    val noTool = done.filter { it.first.tools.isEmpty() && it.first.after == null }
    val weekly = weeklyHalfLives(entries, model.baseline).let { if (it.size >= 2) it.takeLast(4) else emptyList() }
    return Recovery(spikes.size, done.size, times.size - done.size, med(done),
        if (spikes.isEmpty()) 0.0 else median(spikes.map { it.anxiety!!.toDouble() }),
        if (spikes.isEmpty()) 0.0 else median(spikes.map { model.baseline.at(it.minuteOfDay().toDouble()) }), obs,
        if (tool.size >= 3 && noTool.size >= 3) med(tool) else null,
        if (tool.size >= 3 && noTool.size >= 3) med(noTool) else null, weekly)
}
