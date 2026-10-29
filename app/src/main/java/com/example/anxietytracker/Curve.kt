package com.example.anxietytracker

import java.time.LocalDate
import kotlin.math.ln
import kotlin.math.pow

/** Grid step of the day curves, in minutes. */
const val CURVE_STEP = 15
const val DEFAULT_HALF_LIFE = 1.8

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

/**
 * Modeled anxiety: [baseline] plus logged spikes that fade with half-life [halfLife] hours ([halfLifeFits] = number
 * of measured fades, 0 = default). [days] = per date a list of (minuteOfDay, value) on a 15-minute grid.
 * [hourProfile] has 24 slots (hour of day), [wakeProfile] 20 (hours since waking); null = fewer than 3 days.
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
)

private fun Entry.minuteOfDay(): Int = dateTime.let { it.hour * 60 + it.minute }

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

fun buildCurve(entries: List<Entry>): CurveModel {
    val rated = entries.filter { it.anxiety != null }.sortedBy { it.ts }
    val prompted = rated.filter { it.isPrompted() }
    val fromCheckins = prompted.size >= 10
    val base = if (fromCheckins) {
        val pts = ArrayList<Pair<Double, Double>>()
        for ((w, m) in listOf(0 to 600.0, 1 to 900.0, 2 to 1200.0)) {
            val v = prompted.filter { e -> e.dateTime.hour.let { if (it < 13) 0 else if (it < 18) 1 else 2 } == w }
                .map { it.anxiety!!.toDouble() }
            if (v.size >= 3) pts += m to median(v)
        }
        if (pts.isEmpty()) constant(median(prompted.map { it.anxiety!!.toDouble() }))
        else BaselineFn(DoubleArray(pts.size) { pts[it].first }, DoubleArray(pts.size) { pts[it].second })
    } else constant(if (rated.isEmpty()) 2.0 else percentile(rated.map { it.anxiety!! }, 25.0))

    // Half-life from consecutive same-day pairs that look like a fade back toward baseline.
    val fits = ArrayList<Double>()
    for (i in 1 until rated.size) {
        val e0 = rated[i - 1]
        val e1 = rated[i]
        if (e0.dateTime.toLocalDate() != e1.dateTime.toLocalDate()) continue
        val gap = (e1.ts - e0.ts) / 60000.0
        if (gap < 10.0 || gap > 360.0) continue
        val b0 = base.at(e0.minuteOfDay().toDouble())
        val b1 = base.at(e1.minuteOfDay().toDouble())
        val a0 = e0.anxiety!!
        val a1 = e1.anxiety!!
        if (a0 < b0 + 2.0 || a1 <= b1 || a1 >= a0) continue
        val ratio = (a0 - b0) / (a1 - b1)
        if (ratio <= 1.0001) continue
        fits += gap / 60.0 * ln(2.0) / ln(ratio)
    }
    val half = if (fits.size >= 5) median(fits).coerceIn(0.5, 6.0) else DEFAULT_HALF_LIFE
    val nFits = if (fits.size >= 5) fits.size else 0

    val days = java.util.TreeMap<LocalDate, List<Pair<Int, Double>>>()
    val wakes = HashMap<LocalDate, Int>()
    val above = java.util.TreeMap<LocalDate, Double>()
    for ((d, es) in rated.groupBy { it.dateTime.toLocalDate() }) {
        val ms = es.map { it.minuteOfDay() }
        val av = es.map { it.anxiety!!.toDouble() }
        val wake = es.firstNotNullOfOrNull { it.wakeMin }
        if (wake != null) wakes[d] = wake
        val start = minOf(wake ?: ms[0], ms[0]) / CURVE_STEP * CURVE_STEP
        val end = minOf(1440, ms.last() + 120)
        val pts = ArrayList<Pair<Int, Double>>()
        var k = -1
        var m = start
        while (m <= end && m < 1440) {
            while (k + 1 < ms.size && ms[k + 1] <= m) k++
            val b = base.at(m.toDouble())
            val v = if (k < 0) b else b + (av[k] - base.at(ms[k].toDouble())) * 2.0.pow(-(m - ms[k]) / (60.0 * half))
            pts += m to v.coerceIn(1.0, 10.0)
            m += CURVE_STEP
        }
        days[d] = pts
        above[d] = pts.count { it.second > 5.0 } * 0.25
    }
    val hourP = profileOf(24, days, wakes, base, false)
    val wakeP = profileOf(20, days, wakes, base, true)
    return CurveModel(base, half, nFits, fromCheckins, days, hourP, wakeP, above,
        hardestOf(hourP, 3), hardestOf(wakeP, 2))
}
