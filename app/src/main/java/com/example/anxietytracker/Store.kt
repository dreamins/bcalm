package com.example.anxietytracker

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.LocalDate

/** Draft + settings in SharedPreferences; entries in a simple line file. */
object Store {
    private lateinit var prefs: SharedPreferences
    private lateinit var file: File
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    @Synchronized
    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        val app = ctx.applicationContext
        prefs = app.getSharedPreferences("draft", Context.MODE_PRIVATE)
        file = File(app.filesDir, "entries.txt")
        _entries.value = readFile()
        loadCauses()
        loadDoses()
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
            medDose = prefs.getString("medDose", null), medTime = i("medTime"),
            medDay = prefs.getLong("medDay", 0),
        ).normalized()
    }

    fun saveDraft(d: Draft) {
        val e = prefs.edit()
        fun put(k: String, v: Int?) { if (v == null) e.remove(k) else e.putInt(k, v) }
        put("anxiety", d.anxiety); put("caffeine", d.caffeine?.let { if (it) 1 else 0 })
        put("food", d.food?.let { if (it) 1 else 0 })
        put("exercise", d.exercise?.let { if (it) 1 else 0 })
        put("partner", d.partner); put("wakeMin", d.wakeMin); put("sleep", d.sleep)
        put("medTime", d.medTime)
        e.putLong("exerciseDay", d.exerciseDay).putLong("wakeDay", d.wakeDay)
            .putLong("sleepDay", d.sleepDay).putLong("medDay", d.medDay)
            .putString("medDose", d.medDose).apply()
    }

    // ---- settings ----
    var intervalHours: Int
        get() = prefs.getInt("intervalHours", 3)
        set(v) { prefs.edit().putInt("intervalHours", v).apply() }

    // ---- medication settings (set once in Settings) ----
    var medName: String
        get() = prefs.getString("medName", "") ?: ""
        set(v) { prefs.edit().putString("medName", clean(v)).apply() }

    private val _doses = MutableStateFlow<List<String>>(emptyList())
    val doses: StateFlow<List<String>> = _doses
    private fun loadDoses() {
        _doses.value = prefs.getString("doses", "")!!.split("\n").filter { it.isNotBlank() }
    }
    private fun saveDoses(l: List<String>) {
        _doses.value = l
        prefs.edit().putString("doses", l.joinToString("\n")).apply()
    }
    fun addDose(name: String) {
        val n = clean(name)
        if (n.isNotEmpty() && _doses.value.none { it.equals(n, true) }) saveDoses(_doses.value + n)
    }
    fun removeDose(name: String) = saveDoses(_doses.value - name)
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
    fun removeCause(name: String) = saveCauses(_causeList.value - name)

    // ---- entries ----
    fun entryFrom(d: Draft, causes: List<String> = emptyList(), ts: Long = System.currentTimeMillis()): Entry {
        val n = d.normalized()
        return Entry(ts, n.anxiety, n.caffeine, n.food, n.exercise, n.partner, n.wakeMin, n.sleep, causes, n.medDose, n.medTime)
    }

    @Synchronized
    fun add(e: Entry) {
        _entries.value = (_entries.value + e).sortedBy { it.ts }
        writeFile()
    }

    @Synchronized
    fun delete(e: Entry) {
        _entries.value = _entries.value.filter { it.ts != e.ts }
        writeFile()
    }

    /** Saves the carried-over draft as a new entry and clears any reminder. */
    fun logDraft(ctx: Context): Entry {
        val e = entryFrom(loadDraft())
        add(e)
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
                e.medDose ?: "", n(e.medTime)).joinToString("|")
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
            Entry(ts, i(p[1]), i(p[2])?.let { it == 1 }, i(p[3])?.let { it == 1 },
                i(p[4])?.let { it == 1 }, i(p[5]), i(p[6]), i(p[7]),
                p.getOrNull(8)?.split(";")?.filter { it.isNotBlank() } ?: emptyList(),
                p.getOrNull(9)?.takeIf { it.isNotBlank() }, p.getOrNull(10)?.let { i(it) })
        }.sortedBy { it.ts }
    }
}
