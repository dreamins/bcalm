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
    val medDose: String? = null,
    val medTime: Int? = null,   // minutes since midnight
    val medDay: Long = 0,
) {
    /** Daily fields whose last-set date isn't today show as unset. */
    fun normalized(today: Long = LocalDate.now().toEpochDay()): Draft = copy(
        exercise = if (exerciseDay == today) exercise else null,
        wakeMin = if (wakeDay == today) wakeMin else null,
        sleep = if (sleepDay == today) sleep else null,
        medDose = if (medDay == today) medDose else null,
        medTime = if (medDay == today) medTime else null,
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
    val medDose: String? = null,
    val medTime: Int? = null,
) {
    val dateTime: LocalDateTime
        get() = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault())

    val hourOfDay: Float get() = dateTime.let { it.hour + it.minute / 60f }
}

val PARTNER_LABELS = listOf("good", "neutral", "tense")

fun fmtMin(m: Int) = "%02d:%02d".format(m / 60, m % 60)

fun Entry.summary(): String {
    fun yn(b: Boolean?) = when (b) { true -> "yes"; false -> "no"; null -> "-" }
    return buildString {
        append("Anxiety ${anxiety ?: "-"}")
        append("  Caf ${yn(caffeine)}  Food ${yn(food)}  Ex ${yn(exercise)}")
        append("\nPartner ${partner?.let { PARTNER_LABELS[it] } ?: "-"}")
        append("  Wake ${wakeMin?.let { fmtMin(it) } ?: "-"}  Sleep ${sleep ?: "-"}")
        if (medDose != null || medTime != null)
            append("\nMed ${medDose ?: "-"} ${medTime?.let { fmtMin(it) } ?: ""}")
        if (causes.isNotEmpty()) append("\n" + causes.joinToString(", "))
    }
}

object Csv {
    private val dt = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    private val d = DateTimeFormatter.ISO_LOCAL_DATE
    private val t = DateTimeFormatter.ofPattern("HH:mm:ss")
    const val HEADER =
        "timestamp,date,time,anxiety,caffeine,food_3h,exercise_today,partner,wake_time,sleep_quality,med_dose,med_time,causes"

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
                e.medDose?.replace(",", " ") ?: "",
                e.medTime?.let { fmtMin(it) } ?: "",
                e.causes.joinToString(";").replace(",", " "),
            ).joinToString(","))
            append('\n')
        }
    }
}
