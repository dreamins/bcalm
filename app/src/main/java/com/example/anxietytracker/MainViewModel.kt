package com.example.anxietytracker

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** Outcome of tapping a number: a message for the snackbar and a way to undo it. */
class LogResult(val message: String, val undo: () -> Unit)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    init { Store.init(app) }

    companion object {
        /** After logging, further edits on the Log screen update that entry for this long. */
        const val EDIT_WINDOW_MS = 2 * 60 * 1000L
    }

    private val _draft = MutableStateFlow(Store.loadDraft())
    val draft: StateFlow<Draft> = _draft
    private val _causes = MutableStateFlow<Set<String>>(emptySet())
    val selectedCauses: StateFlow<Set<String>> = _causes
    val entries = Store.entries
    val causeList = Store.causeList
    val medDose = Store.medDose
    val cards = Store.cards

    /** The entry just logged and still open for edits (null = next number tap creates a new entry). */
    private val _active = MutableStateFlow<Entry?>(null)
    val activeEntry: StateFlow<Entry?> = _active
    private var expireJob: Job? = null

    /** Snackbar messages for entries logged by a non-anxiety change (shown by the Log screen). */
    private val _logEvents = MutableSharedFlow<LogResult>(extraBufferCapacity = 8)
    val logEvents: SharedFlow<LogResult> = _logEvents.asSharedFlow()

    /** User's explicit open/closed choice for the "Today" card today (null = automatic). */
    private val _todayOpen = MutableStateFlow(Store.loadTodayOpen())
    val todayOpen: StateFlow<Boolean?> = _todayOpen

    /** Re-read draft (applies the midnight reset) e.g. on resume. */
    fun refresh() {
        _draft.value = Store.loadDraft()
        _todayOpen.value = Store.loadTodayOpen()
    }

    fun setTodayOpen(open: Boolean) {
        Store.saveTodayOpen(open)
        _todayOpen.value = open
    }

    fun update(f: (Draft) -> Draft) {
        liveActive()
        val prevDraft = _draft.value
        val prevCauses = _causes.value
        val d = f(prevDraft)
        _draft.value = d
        Store.saveDraft(d)
        afterChange(prevDraft, prevCauses)
    }
    fun setCaffeine(v: Boolean?) = update { it.copy(caffeine = v) }
    fun setFood(v: Boolean?) = update { it.copy(food = v) }
    fun setExercise(v: Boolean?) = update { it.copy(exercise = v, exerciseDay = Store.today()) }
    fun setPartner(v: Int?) = update { it.copy(partner = v) }
    fun setWake(v: Int?) = update { it.copy(wakeMin = v, wakeDay = Store.today()) }
    fun setSleep(v: Int?) = update { it.copy(sleep = v, sleepDay = Store.today()) }
    fun setNightSweats(v: Boolean?) = update { it.copy(nightSweats = v, nightSweatsDay = Store.today()) }
    fun setAlcohol(v: Boolean?) = update { it.copy(alcohol = v, alcoholDay = Store.today()) }
    fun setCannabis(v: Boolean?) = update { it.copy(cannabis = v, cannabisDay = Store.today()) }
    fun setMedDose(v: Double?) {
        Store.setMedDose(v)   // a setting only: neither logs an entry nor touches an open one
    }

    fun toggleCause(c: String) {
        liveActive()
        val prevCauses = _causes.value
        _causes.value = prevCauses.let { if (c in it) it - c else it + c }
        afterChange(_draft.value, prevCauses)
    }
    fun addCause(c: String) {
        liveActive()
        val prevCauses = _causes.value
        Store.addCause(c)
        _causes.value = prevCauses + c.trim().replace(Regex("[;|,\n]"), " ").take(30)
        afterChange(_draft.value, prevCauses)
    }
    fun removeCause(c: String) {
        Store.removeCause(c)
        _causes.value = _causes.value - c
        syncActive()   // list management only: updates an open entry but never logs a new one
    }

    // ---- tap-to-log ----

    private fun orderedCauses(): List<String> = Store.causeList.value.filter { it in _causes.value }

    private fun newTs(): Long {
        val last = Store.entries.value.lastOrNull()?.ts ?: 0L
        return maxOf(System.currentTimeMillis(), last + 1)
    }

    /** The open entry, or null if the window is closed or the entry was deleted meanwhile. */
    private fun liveActive(): Entry? {
        val a = _active.value ?: return null
        // Re-read from the store so after/tools set meanwhile (setAfter) are never lost when the entry is rebuilt.
        val cur = Store.entries.value.firstOrNull { it.ts == a.ts } ?: run { closeWindow(); return null }
        return cur
    }

    private fun arm(e: Entry) {
        _active.value = e
        expireJob?.cancel()
        expireJob = viewModelScope.launch {
            delay(EDIT_WINDOW_MS)
            finishWindow()
        }
    }

    private fun finishWindow() {
        _active.value = null
        _causes.value = emptySet()   // causes describe that moment; they do not carry over
    }

    /** Closes the edit window now (e.g. when the app goes to the background). */
    fun closeWindow() {
        expireJob?.cancel()
        expireJob = null
        finishWindow()
    }

    fun onAppStopped() = closeWindow()

    private fun commit(e: Entry) {
        Store.update(e)
        Reminders.cancelNotification(getApplication())
        arm(e)
    }

    /** Re-saves the open entry with the current draft + causes (keeps its timestamp and anxiety value). */
    private fun syncActive() {
        val a = liveActive() ?: return
        commit(Store.entryFrom(_draft.value.copy(anxiety = a.anxiety), orderedCauses(), a.ts, a.offsetMin, a.joy).copy(after = a.after, tools = a.tools, source = a.source, promptId = a.promptId))
    }

    /** A non-anxiety change was made: update the open entry, or log a new one without anxiety. */
    private fun afterChange(prevDraft: Draft, prevCauses: Set<String>) {
        if (liveActive() != null) { syncActive(); return }
        val e = Store.entryFrom(_draft.value.copy(anxiety = null), orderedCauses(), newTs())
        Store.add(e)
        Reminders.cancelNotification(getApplication())
        arm(e)
        _logEvents.tryEmit(LogResult("Logged without anxiety level") {
            Store.delete(e)
            if (_active.value?.ts == e.ts) {
                expireJob?.cancel()
                expireJob = null
                _active.value = null
            }
            _causes.value = prevCauses
            restoreDraft(prevDraft)
        })
    }

    private fun restoreDraft(d: Draft) {
        _draft.value = d
        Store.saveDraft(d)
    }

    /** A number was tapped: log a new entry, or update the one logged moments ago. */
    fun tapAnxiety(n: Int): LogResult? {
        val prevDraft = _draft.value
        val a = liveActive()
        if (a != null && a.anxiety == n) {
            arm(a)   // same number again: nothing to change, just keep the window open
            return null
        }
        val d = prevDraft.copy(anxiety = n)
        _draft.value = d
        Store.saveDraft(d)
        val ts = a?.ts ?: newTs()
        val e = Store.entryFrom(d, orderedCauses(), ts, if (a != null) a.offsetMin else localOffsetMin(ts), a?.joy)
            .copy(after = a?.after, tools = a?.tools ?: emptyList(), source = a?.source ?: SOURCE_MANUAL, promptId = a?.promptId)
        if (a == null) {
            Store.add(e)
            Reminders.cancelNotification(getApplication())
            arm(e)
            return LogResult("Logged $n") {
                Store.delete(e)
                expireJob?.cancel()
                expireJob = null
                _active.value = null
                restoreDraft(prevDraft)
            }
        }
        commit(e)
        return LogResult(if (a.anxiety == null) "Logged $n" else "Updated to $n") {
            if (_active.value?.ts == a.ts) commit(a)
            restoreDraft(prevDraft)
        }
    }

    /**
     * A joy number was tapped (1..5). With an open entry it sets that entry's joy (the same number again clears it);
     * otherwise it logs a new entry with joy and no anxiety. Joy is never part of the carried-over draft.
     */
    fun tapJoy(n: Int): LogResult? {
        val a = liveActive()
        if (a == null) {
            val e = Store.entryFrom(_draft.value.copy(anxiety = null), orderedCauses(), newTs(), joy = n)
            Store.add(e)
            Reminders.cancelNotification(getApplication())
            arm(e)
            return LogResult("Logged joy $n") {
                Store.delete(e)
                if (_active.value?.ts == e.ts) {
                    expireJob?.cancel()
                    expireJob = null
                    _active.value = null
                }
            }
        }
        val newJoy = if (a.joy == n) null else n
        commit(Store.entryFrom(_draft.value.copy(anxiety = a.anxiety), orderedCauses(), a.ts, a.offsetMin, newJoy).copy(after = a.after, tools = a.tools, source = a.source, promptId = a.promptId))
        return LogResult(if (newJoy == null) "Joy cleared" else "Joy set to $newJoy") {
            if (_active.value?.ts == a.ts) commit(a)
        }
    }

    /** Records the re-rated anxiety (and coping tool) on the entry at [ts]; keeps the open entry in sync. */
    fun setAfter(ts: Long, after: Int?, tool: String?) {
        Store.setAfter(ts, after, tool)
        if (_active.value?.ts == ts) _active.value = Store.entries.value.firstOrNull { it.ts == ts }
    }

    fun delete(e: Entry) = Store.delete(e)
    fun restore(e: Entry) = Store.add(e)
}
