package com.example.anxietytracker

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Partner: 0 = good, 1 = neutral, 2 = tense. Booleans/ints are null when unset. */
data class Draft(
    val anxiety: Int? = null,
    val caffeine: Boolean? = null,
    val food: Boolean? = null,
    val exercise: Boolean? = null,
    val exerciseDay: Long = 0,
    val partner: Int? = null,
    val wakeMin: Int? = null,   // minutes since midnight
    val wakeDay: Long = 0,
    val sleep: Int? = null,
    val sleepDay: Long = 0,
) {
    /** Daily fields whose last-set date isn't today show as unset. */
    fun normalized(today: Long = LocalDate.now().toEpochDay()): Draft = copy(
        exercise = if (exerciseDay == today) exercise else null,
        wakeMin = if (wakeDay == today) wakeMin else null,
        sleep = if (sleepDay == today) sleep else null,
    )
}

data class Entry(
    val ts: Long,
    val anxiety: Int?,
    val caffeine: Boolean?,
    val food: Boolean?,
    val exercise: Boolean?,
    val partner: Int?,
    val wakeMin: Int?,
    val sleep: Int?,
    val causes: List<String> = emptyList(),
    val medDose: Double? = null,
    val medTime: Int? = null,
    val medUnit: String? = null,
    val medName: String? = null,
) {
    /** e.g. "5 mg"; null when no dose was recorded. */
    val medLabel: String? get() = medDose?.let { fmtDose(it, medUnit) } ?: medUnit
    val dateTime: LocalDateTime
        get() = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault())

    val hourOfDay: Float get() = dateTime.let { it.hour + it.minute / 60f }
}

/** Stored/CSV form (lowercase, keep stable). */
val PARTNER_LABELS = listOf("good", "neutral", "tense")

/** Display form used in the UI. */
val PARTNER_DISPLAY = listOf("Good", "Neutral", "Tense")

fun fmtMin(m: Int) = "%02d:%02d".format(m / 60, m % 60)

/** 5.0 -> "5", 7.5 -> "7.5". */
fun fmtNum(d: Double): String = java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()

fun fmtDose(d: Double, unit: String?) = fmtNum(d) + (unit?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "")

private val DOSE_RE = Regex("""^\s*(\d+(?:[.,]\d+)?)\s*(.*?)\s*$""")

/**
 * Splits a dose label into number and unit: "5mg" -> (5.0, "mg"), "7,5 mg" -> (7.5, "mg"), "10" -> (10.0, null).
 * Older versions stored free text; anything without a leading number is kept whole as the unit.
 */
fun parseDose(s: String?): Pair<Double?, String?> {
    if (s.isNullOrBlank()) return null to null
    val m = DOSE_RE.matchEntire(s) ?: return null to s.trim()
    val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null to s.trim()
    return n to m.groupValues[2].ifBlank { null }
}

fun Entry.summary(): String {
    fun yn(b: Boolean?) = when (b) { true -> "Yes"; false -> "No"; null -> "-" }
    return buildString {
        append("Anxiety ${anxiety ?: "-"}")
        append("  Caf ${yn(caffeine)}  Food ${yn(food)}  Ex ${yn(exercise)}")
        append("\nPartner ${partner?.let { PARTNER_DISPLAY[it] } ?: "-"}")
        append("  Wake ${wakeMin?.let { fmtMin(it) } ?: "-"}  Sleep ${sleep ?: "-"}")
        if (medLabel != null || medTime != null)
            append("\nMed ${listOfNotNull(medName, medLabel ?: "-").joinToString(" ")} ${medTime?.let { fmtMin(it) } ?: ""}")
        if (causes.isNotEmpty()) append("\n" + causes.joinToString(", "))
    }
}

object Csv {
    private val dt = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    private val d = DateTimeFormatter.ISO_LOCAL_DATE
    private val t = DateTimeFormatter.ofPattern("HH:mm:ss")
    const val HEADER =
        "timestamp,date,time,anxiety,caffeine,food_3h,exercise_today,partner,wake_time,sleep_quality,med_dose,med_time,causes,med_unit,med_name"

    private fun b(v: Boolean?) = when (v) { true -> "1"; false -> "0"; null -> "" }

    fun build(entries: List<Entry>): String = buildString {
        append(HEADER).append('\n')
        for (e in entries.sortedBy { it.ts }) {
            val x = e.dateTime.withNano(0)
            append(listOf(
                x.format(dt), x.format(d), x.format(t),
                e.anxiety?.toString() ?: "", b(e.caffeine), b(e.food), b(e.exercise),
                e.partner?.let { PARTNER_LABELS[it] } ?: "",
                e.wakeMin?.let { fmtMin(it) } ?: "",
                e.sleep?.toString() ?: "",
                e.medDose?.let { fmtNum(it) } ?: "",
                e.medTime?.let { fmtMin(it) } ?: "",
                e.causes.joinToString(";").replace(",", " "),
                e.medUnit?.replace(",", " ") ?: "",
                e.medName?.replace(",", " ") ?: "",
            ).joinToString(","))
            append('\n')
        }
    }

    /** Parses [build] output (any historical column set, matched by header name); null if no timestamp column. */
    fun parse(text: String): List<Entry>? {
        val lines = text.removePrefix("\uFEFF").lines().map { it.trim() }.filter { it.isNotEmpty() }
        val head = lines.firstOrNull()?.split(",")?.map { it.trim().lowercase() } ?: return null
        if ("timestamp" !in head) return null
        val out = ArrayList<Entry>()
        for (line in lines.drop(1)) {
            val f = line.split(",").map { it.trim() }
            fun col(n: String) = head.indexOf(n).let { if (it >= 0) f.getOrNull(it) else null }?.ifEmpty { null }
            fun bool(n: String) = when (col(n)) { "1" -> true; "0" -> false; else -> null }
            fun min(n: String) = col(n)?.split(":")?.let { (it.getOrNull(0)?.toIntOrNull() ?: return@let null) * 60 + (it.getOrNull(1)?.toIntOrNull() ?: 0) }
            val ts = try {
                LocalDateTime.parse(col("timestamp") ?: continue, dt).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (e: Exception) { continue }
            val (dose, legacyUnit) = parseDose(col("med_dose"))
            out += Entry(
                ts, col("anxiety")?.toIntOrNull(), bool("caffeine"), bool("food_3h"), bool("exercise_today"),
                col("partner")?.lowercase()?.let { PARTNER_LABELS.indexOf(it) }?.takeIf { it >= 0 },
                min("wake_time"), col("sleep_quality")?.toIntOrNull(),
                col("causes")?.split(";")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                dose, min("med_time"), col("med_unit") ?: legacyUnit, col("med_name"),
            )
        }
        return out
    }
}
