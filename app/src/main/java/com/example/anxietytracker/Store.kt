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
        ).normalized()
    }

    fun saveDraft(d: Draft) {
        val e = prefs.edit()
        fun put(k: String, v: Int?) { if (v == null) e.remove(k) else e.putInt(k, v) }
        put("anxiety", d.anxiety); put("caffeine", d.caffeine?.let { if (it) 1 else 0 })
        put("food", d.food?.let { if (it) 1 else 0 })
        put("exercise", d.exercise?.let { if (it) 1 else 0 })
        put("partner", d.partner); put("wakeMin", d.wakeMin); put("sleep", d.sleep)
        e.putLong("exerciseDay", d.exerciseDay).putLong("wakeDay", d.wakeDay)
            .putLong("sleepDay", d.sleepDay).apply()
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

    var chartView: Int  // 0 = by hour of day, 1 = over time
        get() = prefs.getInt("chartView", 0)
        set(v) { prefs.edit().putInt("chartView", v).apply() }

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
    fun removeCause(name: String) = saveCauses(_causeList.value - name)

    // ---- entries ----
    fun entryFrom(d: Draft, causes: List<String> = emptyList(), ts: Long = System.currentTimeMillis()): Entry {
        val n = d.normalized()
        return Entry(ts, n.anxiety, n.caffeine, n.food, n.exercise, n.partner, n.wakeMin, n.sleep, causes,
            _medDose.value, medUsualTime, _medDose.value?.let { medUnit.ifBlank { null } },
            _medDose.value?.let { medName.ifBlank { null } })
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
                e.medDose?.let { fmtNum(it) } ?: "", n(e.medTime), e.medUnit ?: "", e.medName ?: "").joinToString("|")
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
                p.getOrNull(12)?.takeIf { it.isNotBlank() })
        }.sortedBy { it.ts }
    }
}
