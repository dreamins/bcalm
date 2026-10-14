package com.example.anxietytracker

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    val doses = Store.doses

    /** The entry just logged and still open for edits (null = next number tap creates a new entry). */
    private val _active = MutableStateFlow<Entry?>(null)
    val activeEntry: StateFlow<Entry?> = _active
    private var expireJob: Job? = null

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
        val d = f(_draft.value)
        _draft.value = d
        Store.saveDraft(d)
        syncActive()
    }
    fun setCaffeine(v: Boolean?) = update { it.copy(caffeine = v) }
    fun setFood(v: Boolean?) = update { it.copy(food = v) }
    fun setExercise(v: Boolean?) = update { it.copy(exercise = v, exerciseDay = Store.today()) }
    fun setPartner(v: Int?) = update { it.copy(partner = v) }
    fun setWake(v: Int?) = update { it.copy(wakeMin = v, wakeDay = Store.today()) }
    fun setSleep(v: Int?) = update { it.copy(sleep = v, sleepDay = Store.today()) }

    fun toggleCause(c: String) {
        _causes.value = _causes.value.let { if (c in it) it - c else it + c }
        syncActive()
    }
    fun addCause(c: String) {
        Store.addCause(c)
        _causes.value = _causes.value + c.trim().replace(Regex("[;|,\n]"), " ").take(30)
        syncActive()
    }
    fun removeCause(c: String) {
        Store.removeCause(c)
        _causes.value = _causes.value - c
        syncActive()
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
        if (Store.entries.value.none { it.ts == a.ts }) { closeWindow(); return null }
        return a
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

    /** Re-saves the open entry with the current draft + causes (keeps its timestamp). */
    private fun syncActive() {
        val a = liveActive() ?: return
        commit(Store.entryFrom(_draft.value, orderedCauses(), a.ts))
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
        val e = Store.entryFrom(d, orderedCauses(), a?.ts ?: newTs())
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
        return LogResult("Updated to $n") {
            if (_active.value?.ts == a.ts) commit(a)
            restoreDraft(prevDraft)
        }
    }

    fun delete(e: Entry) = Store.delete(e)
    fun restore(e: Entry) = Store.add(e)
}
