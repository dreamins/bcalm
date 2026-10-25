package com.example.anxietytracker

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.LocalDate

data class SyncState(val time: Long, val message: String)

/** Draft + settings in SharedPreferences; entries in a simple line file. */
object Store {
    private lateinit var prefs: SharedPreferences
    private lateinit var file: File
    private lateinit var appCtx: Context
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    @Synchronized
    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        val app = ctx.applicationContext
        appCtx = app
        prefs = app.getSharedPreferences("draft", Context.MODE_PRIVATE)
        file = File(app.filesDir, "entries.txt")
        _entries.value = readFile()
        loadCauses()
        loadMedDose()
        loadCards()
        _syncUri.value = prefs.getString("syncUri", null)
        _syncState.value = SyncState(prefs.getLong("syncTime", 0L), prefs.getString("syncMsg", "") ?: "")
    }

    // ---- draft ----
    fun loadDraft(): Draft {
        fun i(k: String) = if (prefs.contains(k)) prefs.getInt(k, 0) else null
        fun b(k: String) = i(k)?.let { it == 1 }
        return Draft(
            anxiety = i("anxiety"), caffeine = b("caffeine"), food = b("food"),
            exercise = b("exercise"), exerciseDay = prefs.getLong("exerciseDay", 0),
            partner = i("partner"),
            wakeMin = i("wakeMin"), wakeDay = prefs.getLong("wakeDay", 0),
            sleep = i("sleep"), sleepDay = prefs.getLong("sleepDay", 0),
            nightSweats = b("nightSweats"), nightSweatsDay = prefs.getLong("nightSweatsDay", 0),
            alcohol = b("alcohol"), alcoholDay = prefs.getLong("alcoholDay", 0),
            cannabis = b("cannabis"), cannabisDay = prefs.getLong("cannabisDay", 0),
        ).normalized()
    }

    fun saveDraft(d: Draft) {
        val e = prefs.edit()
        fun put(k: String, v: Int?) { if (v == null) e.remove(k) else e.putInt(k, v) }
        put("anxiety", d.anxiety); put("caffeine", d.caffeine?.let { if (it) 1 else 0 })
        put("food", d.food?.let { if (it) 1 else 0 })
        put("exercise", d.exercise?.let { if (it) 1 else 0 })
        put("nightSweats", d.nightSweats?.let { if (it) 1 else 0 })
        put("alcohol", d.alcohol?.let { if (it) 1 else 0 })
        put("cannabis", d.cannabis?.let { if (it) 1 else 0 })
        put("partner", d.partner); put("wakeMin", d.wakeMin); put("sleep", d.sleep)
        e.putLong("exerciseDay", d.exerciseDay).putLong("wakeDay", d.wakeDay)
            .putLong("sleepDay", d.sleepDay).putLong("nightSweatsDay", d.nightSweatsDay)
            .putLong("alcoholDay", d.alcoholDay).putLong("cannabisDay", d.cannabisDay).apply()
    }

    // ---- "Today" card open/closed choice (valid for the day it was made) ----
    fun loadTodayOpen(): Boolean? =
        if (prefs.getLong("todayOpenDay", -1L) == today() && prefs.contains("todayOpen"))
            prefs.getBoolean("todayOpen", false) else null

    fun saveTodayOpen(open: Boolean) {
        prefs.edit().putLong("todayOpenDay", today()).putBoolean("todayOpen", open).apply()
    }

    // ---- settings ----
    var intervalHours: Int
        get() = prefs.getInt("intervalHours", 3)
        set(v) { prefs.edit().putInt("intervalHours", v).apply() }

    var chartView: Int  // 0 = time of day, 1 = since waking, 2 = over time
        get() = prefs.getInt("chartView3", 0)
        set(v) { prefs.edit().putInt("chartView3", v).apply() }

    // ---- Drive/CSV sync target (a SAF document uri) and last result ----
    private val _syncUri = MutableStateFlow<String?>(null)
    val syncUri: StateFlow<String?> = _syncUri
    private val _syncState = MutableStateFlow(SyncState(0L, ""))
    val syncState: StateFlow<SyncState> = _syncState

    fun setSyncUri(uri: String?) {
        _syncUri.value = uri
        val e = prefs.edit()
        if (uri == null) e.remove("syncUri") else e.putString("syncUri", uri)
        e.apply()
        setSyncResult("")
    }

    /** Records the outcome of the latest sync attempt (empty message = nothing synced yet). */
    fun setSyncResult(msg: String) {
        val st = SyncState(if (msg.isEmpty()) 0L else System.currentTimeMillis(), msg)
        _syncState.value = st
        prefs.edit().putLong("syncTime", st.time).putString("syncMsg", st.message).apply()
    }

    // ---- medication: name/unit/usual time set in Settings; dose picked on the Log screen ----
    var medName: String
        get() = prefs.getString("medName", "") ?: ""
        set(v) { prefs.edit().putString("medName", clean(v)).apply() }
    var medUnit: String
        get() = normalizeMedUnit(prefs.getString("medUnit", null))
        set(v) { prefs.edit().putString("medUnit", normalizeMedUnit(v)).apply() }
    /** Usual time taken, minutes since midnight (null = none). */
    var medUsualTime: Int?
        get() = if (prefs.contains("medUsualTime")) prefs.getInt("medUsualTime", 0) else null
        set(v) { prefs.edit().apply { if (v == null) remove("medUsualTime") else putInt("medUsualTime", v) }.apply() }

    /** Current dose (null = none). Carries over day to day until changed; recorded with every entry. */
    private val _medDose = MutableStateFlow<Double?>(null)
    val medDose: StateFlow<Double?> = _medDose
    fun setMedDose(v: Double?) {
        _medDose.value = v
        prefs.edit().apply { if (v == null) remove("medDoseNum") else putString("medDoseNum", fmtNum(v)) }.apply()
    }

    /** Earlier versions kept free-text doses ("10 mg") in Settings; turn the current one into number + unit. */
    private fun loadMedDose() {
        if (prefs.contains("medCurrentDose") || prefs.contains("doses")) {
            val (n, curUnit) = parseDose(prefs.getString("medCurrentDose", null))
            val unit = curUnit ?: prefs.getString("doses", "")!!.split("\n")
                .map { parseDose(it) }.firstOrNull { it.first != null && it.second != null }?.second
            val e = prefs.edit().remove("medCurrentDose").remove("doses")
            if (n != null && !prefs.contains("medDoseNum")) e.putString("medDoseNum", fmtNum(n))
            if (unit != null && !prefs.contains("medUnit")) e.putString("medUnit", normalizeMedUnit(unit))
            e.apply()
        }
        // Unit used to be free text; "5 mg" typed there means a dose of 5 mg.
        prefs.getString("medUnit", null)?.let { raw ->
            val n = parseDose(raw).first
            if (n != null && !prefs.contains("medDoseNum")) prefs.edit().putString("medDoseNum", fmtNum(n)).apply()
            if (raw != normalizeMedUnit(raw)) prefs.edit().putString("medUnit", normalizeMedUnit(raw)).apply()
        }
        _medDose.value = prefs.getString("medDoseNum", null)?.toDoubleOrNull()
    }

    private fun clean(s: String) = s.trim().replace(Regex("[;|,\n]"), " ").take(30)

    // ---- causes list (presets + custom; user can remove any) ----
    val PRESET_CAUSES = listOf("Wife texted", "Work", "Money", "Health worry", "Conflict",
        "Social", "News/phone", "Deadline", "No clear reason")
    private val _causeList = MutableStateFlow<List<String>>(emptyList())
    val causeList: StateFlow<List<String>> = _causeList

    private fun loadCauses() {
        val raw = prefs.getString("causeList", null)
        _causeList.value = raw?.split("\n")?.filter { it.isNotBlank() } ?: PRESET_CAUSES
    }
    private fun saveCauses(l: List<String>) {
        _causeList.value = l
        prefs.edit().putString("causeList", l.joinToString("\n")).apply()
    }
    fun addCause(name: String) {
        val n = name.trim().replace(Regex("[;|,\n]"), " ").take(30)
        if (n.isNotEmpty() && _causeList.value.none { it.equals(n, true) }) saveCauses(_causeList.value + n)
    }
    /** Adds causes found in imported entries that the list lacks (one write); returns how many. */
    fun restoreCauses(list: List<Entry>): Int {
        val have = _causeList.value.mapTo(HashSet()) { it.lowercase() }
        val add = list.flatMap { it.causes }.map { it.trim() }.filter { it.isNotEmpty() && have.add(it.lowercase()) }
        if (add.isNotEmpty()) saveCauses(_causeList.value + add)
        return add.size
    }
    fun removeCause(name: String) {
        saveCauses(_causeList.value - name)
        setCard(name, CauseCard())
    }

    // ---- calm cards (per cause) and support contacts: prefs; not in entries.txt, but carried in the CSV as '#' rows ----
    private val _cards = MutableStateFlow<Map<String, CauseCard>>(emptyMap())
    val cards: StateFlow<Map<String, CauseCard>> = _cards

    private fun loadCards() {
        _cards.value = _causeList.value.mapNotNull { c ->
            val card = CauseCard(prefs.getString("card.thought.$c", "") ?: "",
                prefs.getString("card.todo.$c", "") ?: "", prefs.getBoolean("card.heavy.$c", false))
            if (card == CauseCard()) null else c to card
        }.toMap()
    }

    /** Saves the calm card for [name]; a blank card removes it. */
    fun setCard(name: String, card: CauseCard) {
        val c = card.copy(thought = card.thought.trim(), todo = card.todo.trim())
        val e = prefs.edit().remove("card.thought.$name").remove("card.todo.$name").remove("card.heavy.$name")
        if (c == CauseCard()) {
            _cards.value = _cards.value - name
        } else {
            if (c.thought.isNotEmpty()) e.putString("card.thought.$name", c.thought)
            if (c.todo.isNotEmpty()) e.putString("card.todo.$name", c.todo)
            if (c.heavy) e.putBoolean("card.heavy.$name", true)
            _cards.value = _cards.value + (name to c)
        }
        e.apply()
        CsvSync.enqueueIfEnabled(appCtx)
    }

    // ---- support contacts (Settings); label + number, plus a free-text step ----
    var crisisLabel: String
        get() = prefs.getString("crisisLabel", "Crisis line") ?: ""
        set(v) { prefs.edit().putString("crisisLabel", v.trim().take(30)).apply(); CsvSync.enqueueIfEnabled(appCtx) }
    var crisisNumber: String
        get() = prefs.getString("crisisNumber", "988") ?: ""
        set(v) { prefs.edit().putString("crisisNumber", v.trim().take(30)).apply(); CsvSync.enqueueIfEnabled(appCtx) }
    var personLabel: String
        get() = prefs.getString("personLabel", "") ?: ""
        set(v) { prefs.edit().putString("personLabel", v.trim().take(30)).apply(); CsvSync.enqueueIfEnabled(appCtx) }
    var personNumber: String
        get() = prefs.getString("personNumber", "") ?: ""
        set(v) { prefs.edit().putString("personNumber", v.trim().take(30)).apply(); CsvSync.enqueueIfEnabled(appCtx) }
    var customStep: String
        get() = prefs.getString("customStep", "") ?: ""
        set(v) { prefs.edit().putString("customStep", v.trim().take(80)).apply(); CsvSync.enqueueIfEnabled(appCtx) }

    /** Cards and support values for the CSV; defaults (and blanks) are included so a cleared field restores as cleared. */
    fun csvSettings() = CsvSettings(_cards.value, mapOf("crisisLabel" to crisisLabel, "crisisNumber" to crisisNumber,
        "personLabel" to personLabel, "personNumber" to personNumber, "customStep" to customStep))

    /**
     * Restores cards and support values from an imported CSV without overwriting local edits:
     * a card only where the cause has none, a support field only where it is blank. Returns cards restored.
     */
    fun restoreSettings(s: CsvSettings): Int {
        var n = 0
        for ((name, c) in s.cards) {
            if (name in _cards.value || c == CauseCard()) continue
            addCause(name)
            setCard(name, c); n++
        }
        s.support["crisisLabel"]?.takeIf { crisisLabel.isBlank() }?.let { crisisLabel = it }
        s.support["crisisNumber"]?.takeIf { crisisNumber.isBlank() }?.let { crisisNumber = it }
        s.support["personLabel"]?.takeIf { personLabel.isBlank() }?.let { personLabel = it }
        s.support["personNumber"]?.takeIf { personNumber.isBlank() }?.let { personNumber = it }
        s.support["customStep"]?.takeIf { customStep.isBlank() }?.let { customStep = it }
        return n
    }

    // ---- calm card dismissals: entry timestamps, trimmed to the last day ----
    fun dismissedCalm(): Set<Long> {
        val cutoff = System.currentTimeMillis() - 24 * 3_600_000L
        return (prefs.getString("calmDismissed", "") ?: "").split(",").mapNotNull { it.toLongOrNull() }
            .filter { it > cutoff }.toSet()
    }
    fun dismissCalm(ts: Long) {
        prefs.edit().putString("calmDismissed", (dismissedCalm() + ts).joinToString(",")).apply()
    }

    // ---- entries ----
    fun entryFrom(d: Draft, causes: List<String> = emptyList(), ts: Long = System.currentTimeMillis(), offsetMin: Int? = localOffsetMin(ts), joy: Int? = null): Entry {
        val n = d.normalized()
        return Entry(ts, n.anxiety, n.caffeine, n.food, n.exercise, n.partner, n.wakeMin, n.sleep, causes,
            _medDose.value, medUsualTime, _medDose.value?.let { medUnit.ifBlank { null } },
            _medDose.value?.let { medName.ifBlank { null } }, n.nightSweats, n.alcohol, n.cannabis, offsetMin, joy)
    }

    @Synchronized
    fun add(e: Entry) {
        _entries.value = (_entries.value + e).sortedBy { it.ts }
        writeFile()
        CsvSync.enqueueIfEnabled(appCtx)
        BcalmWidget.refresh(appCtx)
    }

    /** Adds entries whose timestamp is not present yet; returns how many were added. */
    @Synchronized
    fun importEntries(list: List<Entry>): Int {
        val have = _entries.value.mapTo(HashSet()) { it.ts }
        val fresh = list.filter { have.add(it.ts) }
        if (fresh.isEmpty()) return 0
        _entries.value = (_entries.value + fresh).sortedBy { it.ts }
        writeFile()
        CsvSync.enqueueIfEnabled(appCtx)
        BcalmWidget.refresh(appCtx)
        return fresh.size
    }

    /** Re-rates anxiety on the entry at [ts] (any age) and notes the coping [tool] used, if any. */
    @Synchronized
    fun setAfter(ts: Long, after: Int?, tool: String?) {
        val e = _entries.value.firstOrNull { it.ts == ts } ?: return
        update(e.copy(after = after, tools = if (tool == null || tool in e.tools) e.tools else e.tools + tool))
    }

    /** Replaces the entry with the same timestamp (no-op if it no longer exists). */
    @Synchronized
    fun update(e: Entry) {
        if (_entries.value.none { it.ts == e.ts }) return
        _entries.value = _entries.value.map { if (it.ts == e.ts) e else it }
        writeFile()
        CsvSync.enqueueIfEnabled(appCtx)
        BcalmWidget.refresh(appCtx)
    }

    @Synchronized
    fun delete(e: Entry) {
        _entries.value = _entries.value.filter { it.ts != e.ts }
        writeFile()
        CsvSync.enqueueIfEnabled(appCtx)
        BcalmWidget.refresh(appCtx)
    }

    /** Saves the carried-over draft as a new entry and clears any reminder. */
    fun logDraft(ctx: Context): Entry {
        val e = entryFrom(loadDraft())
        add(e)
        Reminders.cancelNotification(ctx)
        return e
    }

    /** Widget tap: log [n] with all other values carried over (no causes); same path as "Same as last". */
    fun quickLog(ctx: Context, n: Int): Entry {
        val d = loadDraft().copy(anxiety = n)
        saveDraft(d)
        val ts = maxOf(System.currentTimeMillis(), (_entries.value.lastOrNull()?.ts ?: 0L) + 1)
        val e = entryFrom(d, emptyList(), ts)
        add(e)   // persists, enqueues CsvSync and refreshes the widget
        Reminders.cancelNotification(ctx)
        return e
    }

    fun today() = LocalDate.now().toEpochDay()

    private fun writeFile() {
        val tmp = File(file.parentFile, "entries.tmp")
        tmp.writeText(_entries.value.joinToString("\n") { e ->
            fun n(v: Int?) = v?.toString() ?: ""
            fun bb(v: Boolean?) = v?.let { if (it) "1" else "0" } ?: ""
            listOf(e.ts.toString(), n(e.anxiety), bb(e.caffeine), bb(e.food), bb(e.exercise),
                n(e.partner), n(e.wakeMin), n(e.sleep), e.causes.joinToString(";"),
                e.medDose?.let { fmtNum(it) } ?: "", n(e.medTime), e.medUnit ?: "", e.medName ?: "", bb(e.nightSweats), bb(e.alcohol), bb(e.cannabis), n(e.offsetMin), n(e.joy),
                n(e.after), e.tools.joinToString(";")).joinToString("|")
        })
        tmp.renameTo(file)
    }

    private fun readFile(): List<Entry> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            val p = line.split("|")
            if (p.size < 8) return@mapNotNull null
            val ts = p[0].toLongOrNull() ?: return@mapNotNull null
            fun i(s: String) = s.toIntOrNull()
            // Older lines have a free-text dose ("10 mg") in field 9 and no field 11.
            val (dose, legacyUnit) = parseDose(p.getOrNull(9))
            Entry(ts, i(p[1]), i(p[2])?.let { it == 1 }, i(p[3])?.let { it == 1 },
                i(p[4])?.let { it == 1 }, i(p[5]), i(p[6]), i(p[7]),
                p.getOrNull(8)?.split(";")?.filter { it.isNotBlank() } ?: emptyList(),
                dose, p.getOrNull(10)?.let { i(it) },
                p.getOrNull(11)?.takeIf { it.isNotBlank() } ?: legacyUnit,
                p.getOrNull(12)?.takeIf { it.isNotBlank() },
                p.getOrNull(13)?.let { i(it) }?.let { it == 1 },
                p.getOrNull(14)?.let { i(it) }?.let { it == 1 },
                p.getOrNull(15)?.let { i(it) }?.let { it == 1 },
                p.getOrNull(16)?.let { i(it) } ?: localOffsetMin(ts),
                p.getOrNull(17)?.let { i(it) }?.takeIf { it in 1..5 },
                p.getOrNull(18)?.let { i(it) }?.takeIf { it in 1..10 },
                p.getOrNull(19)?.split(";")?.filter { it.isNotBlank() } ?: emptyList())
        }.sortedBy { it.ts }
    }
}
