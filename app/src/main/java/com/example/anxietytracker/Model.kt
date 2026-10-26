package com.example.anxietytracker

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
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
    val nightSweats: Boolean? = null,   // describes last night; logged in the morning
    val nightSweatsDay: Long = 0,
    val alcohol: Boolean? = null,       // describes today (the record day)
    val alcoholDay: Long = 0,
    val cannabis: Boolean? = null,      // describes today (the record day)
    val cannabisDay: Long = 0,
) {
    /** Daily fields whose last-set date isn't today show as unset. */
    fun normalized(today: Long = LocalDate.now().toEpochDay()): Draft = copy(
        exercise = if (exerciseDay == today) exercise else null,
        wakeMin = if (wakeDay == today) wakeMin else null,
        sleep = if (sleepDay == today) sleep else null,
        nightSweats = if (nightSweatsDay == today) nightSweats else null,
        alcohol = if (alcoholDay == today) alcohol else null,
        cannabis = if (cannabisDay == today) cannabis else null,
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
    val nightSweats: Boolean? = null,
    val alcohol: Boolean? = null,
    val cannabis: Boolean? = null,
    val offsetMin: Int? = null,   // UTC offset (minutes) where it was logged; null = device zone
    val joy: Int? = null,         // 1..5, optional: how much a positive moment landed (no carry-over)
    val after: Int? = null,       // 1..10, optional: anxiety re-rated after a coping tool
    val tools: List<String> = emptyList(),   // coping tools used: "card", "breathe", "ground"
) {
    /** e.g. "5 mg"; null when no dose was recorded. */
    val medLabel: String? get() = medDose?.let { fmtDose(it, medUnit) } ?: medUnit
    val dateTime: LocalDateTime
        get() = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts),
            offsetMin?.let { ZoneOffset.ofTotalSeconds(it * 60) } ?: ZoneId.systemDefault())

    val hourOfDay: Float get() = dateTime.let { it.hour + it.minute / 60f }

    /** Hours after waking (0..20); null without a wake time or when later than 20 h. */
    val hoursSinceWake: Float?
        get() {
            val w = wakeMin ?: return null
            val dt = dateTime
            var m = dt.hour * 60 + dt.minute - w
            if (m < 0) m += 1440
            return (m / 60f).takeIf { it <= 20f }
        }
}

/** Per-cause calm card: a thought, one small step, and whether to offer support options. */
data class CauseCard(val thought: String = "", val todo: String = "", val heavy: Boolean = false)

/** Support settings keys, in CSV order. */
val SUPPORT_KEYS = listOf("crisisLabel", "crisisNumber", "personLabel", "personNumber", "customStep")

/** Calm cards and support contacts, carried in the CSV as '#' rows after the entries. */
data class CsvSettings(val cards: Map<String, CauseCard> = emptyMap(), val support: Map<String, String> = emptyMap())

/** UTC offset in minutes of the device zone at [ts]. */
fun localOffsetMin(ts: Long): Int =
    ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(ts)).totalSeconds / 60

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

val MED_UNITS = listOf("mg", "mcg", "g", "mL", "IU", "units", "drops", "tablets")

/** Maps any stored/typed unit text ("5 mg", "MG", "ug", "IU") to one of [MED_UNITS]; unknown -> "mg". */
fun normalizeMedUnit(s: String?): String {
    val u = (parseDose(s).second ?: s ?: "").trim().lowercase()
    return when (u) {
        "mg" -> "mg"
        "mcg", "µg", "μg", "ug" -> "mcg"
        "g", "gram", "grams" -> "g"
        "ml" -> "mL"
        "iu" -> "IU"
        "unit", "units" -> "units"
        "drop", "drops" -> "drops"
        "tab", "tabs", "tablet", "tablets" -> "tablets"
        else -> "mg"
    }
}

fun Entry.summary(): String {
    fun yn(b: Boolean?) = when (b) { true -> "Yes"; false -> "No"; null -> "-" }
    return buildString {
        append("Anxiety ${anxiety ?: "-"}")
        if (joy != null) append("  Joy $joy")
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
        "timestamp,date,time,anxiety,caffeine,food_3h,exercise_today,partner,wake_time,sleep_quality,med_dose,med_time,causes,med_unit,med_name,night_sweats,alcohol,cannabis,utc_offset,joy,after,tools"

    private fun b(v: Boolean?) = when (v) { true -> "1"; false -> "0"; null -> "" }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = try { java.net.URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }

    fun build(entries: List<Entry>, settings: CsvSettings = CsvSettings()): String = buildString {
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
                b(e.nightSweats), b(e.alcohol), b(e.cannabis),
                e.offsetMin?.toString() ?: "",
                e.joy?.toString() ?: "",
                e.after?.toString() ?: "",
                e.tools.joinToString(";"),
            ).joinToString(","))
            append('\n')
        }
        for ((name, c) in settings.cards)
            append("#card,${enc(name)},${enc(c.thought)},${enc(c.todo)},${if (c.heavy) "1" else "0"}\n")
        for (k in SUPPORT_KEYS) settings.support[k]?.let { append("#support,$k,${enc(it)}\n") }
    }

    private val PCT = Regex("%[0-9A-Fa-f]{2}")

    /**
     * Text of an unquoted '#' field: percent-decoded when it has %XX sequences or looks fully encoded
     * (no spaces, '+' between words); otherwise taken literally, so hand-typed text with '+' or '%' survives.
     */
    private fun smartDec(s: String): String {
        val encoded = PCT.containsMatchIn(s) || (s.none { it.isWhitespace() } && s.drop(1).dropLast(1).contains('+'))
        return if (encoded) dec(s) else s
    }

    /** Strips one wrapping pair of double quotes, or a lone leading/trailing one left by quote-then-encode tools. */
    private fun unquote(s: String): String {
        var t = s.trim()
        t = when {
            t.length >= 2 && t.startsWith("\"") && t.endsWith("\"") -> t.substring(1, t.length - 1)
            t.startsWith("\"") -> t.substring(1)
            t.endsWith("\"") -> t.dropLast(1)
            else -> t
        }
        return t.trim()
    }

    /** Splits a '#' row on commas, honouring double quotes ("" = literal quote); returns (text, wasQuoted). */
    private fun splitHash(line: String): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        val sb = StringBuilder()
        var quoted = false
        var inQ = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                inQ && ch == '"' && line.getOrNull(i + 1) == '"' -> { sb.append('"'); i++ }
                inQ && ch == '"' -> inQ = false
                inQ -> sb.append(ch)
                ch == '"' && sb.isBlank() -> { inQ = true; quoted = true; sb.clear() }
                ch == ',' -> { out += (if (quoted) sb.toString() else sb.toString().trim()) to quoted; sb.clear(); quoted = false }
                quoted -> {}   // stray text after a closing quote is ignored
                else -> sb.append(ch)
            }
            i++
        }
        out += (if (quoted) sb.toString() else sb.toString().trim()) to quoted
        return out
    }

    /** Reads the '#card' / '#support' rows of [build] output, or of a hand-edited file; empty when there are none. */
    fun parseSettings(text: String): CsvSettings {
        val cards = LinkedHashMap<String, CauseCard>()
        val support = LinkedHashMap<String, String>()
        for (line in text.removePrefix("\uFEFF").lines().map { it.trim() }) {
            if (!line.startsWith("#")) continue
            val f = splitHash(line)
            fun v(i: Int) = f[i].let { (x, q) -> unquote(if (q) x else smartDec(x)) }
            when (f[0].first) {
                "#card" -> if (f.size >= 4) {
                    val name = v(1).trim()
                    if (name.isNotEmpty()) cards[name] = CauseCard(v(2), v(3), f.getOrNull(4)?.first?.trim() == "1")
                }
                "#support" -> if (f.size >= 3 && f[1].first in SUPPORT_KEYS) support[f[1].first] = v(2)
            }
        }
        return CsvSettings(cards, support)
    }

    /** Parses [build] output (any historical column set, matched by header name); null if no timestamp column. */
    fun parse(text: String): List<Entry>? {
        val lines = text.removePrefix("\uFEFF").lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val head = lines.firstOrNull()?.split(",")?.map { it.trim().lowercase() } ?: return null
        if ("timestamp" !in head) return null
        val out = ArrayList<Entry>()
        for (line in lines.drop(1)) {
            val f = line.split(",").map { it.trim() }
            fun col(n: String) = head.indexOf(n).let { if (it >= 0) f.getOrNull(it) else null }?.ifEmpty { null }
            fun bool(n: String) = when (col(n)) { "1" -> true; "0" -> false; else -> null }
            fun min(n: String) = col(n)?.split(":")?.let { (it.getOrNull(0)?.toIntOrNull() ?: return@let null) * 60 + (it.getOrNull(1)?.toIntOrNull() ?: 0) }
            val off = col("utc_offset")?.toIntOrNull()
            val ts = try {
                val ldt = LocalDateTime.parse(col("timestamp") ?: continue, dt)
                if (off != null) ldt.toInstant(ZoneOffset.ofTotalSeconds(off * 60)).toEpochMilli()
                else ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (e: Exception) { continue }
            val (dose, legacyUnit) = parseDose(col("med_dose"))
            out += Entry(
                ts, col("anxiety")?.toIntOrNull(), bool("caffeine"), bool("food_3h"), bool("exercise_today"),
                col("partner")?.lowercase()?.let { PARTNER_LABELS.indexOf(it) }?.takeIf { it >= 0 },
                min("wake_time"), col("sleep_quality")?.toIntOrNull(),
                col("causes")?.split(";")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                dose, min("med_time"), col("med_unit") ?: legacyUnit, col("med_name"),
                bool("night_sweats"), bool("alcohol"), bool("cannabis"), off ?: localOffsetMin(ts),
                col("joy")?.toIntOrNull()?.takeIf { it in 1..5 },
                col("after")?.toIntOrNull()?.takeIf { it in 1..10 },
                col("tools")?.split(";")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            )
        }
        return out
    }
}
