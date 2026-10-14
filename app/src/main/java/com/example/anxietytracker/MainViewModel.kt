package com.example.anxietytracker

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(app: Application) : AndroidViewModel(app) {
    init { Store.init(app) }
    private val _draft = MutableStateFlow(Store.loadDraft())
    val draft: StateFlow<Draft> = _draft
    private val _causes = MutableStateFlow<Set<String>>(emptySet())
    val selectedCauses: StateFlow<Set<String>> = _causes
    val entries = Store.entries
    val causeList = Store.causeList
    val doses = Store.doses

    /** Re-read draft (applies the midnight reset) e.g. on resume. */
    fun refresh() { _draft.value = Store.loadDraft() }

    fun update(f: (Draft) -> Draft) {
        val d = f(_draft.value)
        _draft.value = d
        Store.saveDraft(d)
    }
    fun setAnxiety(v: Int?) = update { it.copy(anxiety = v) }
    fun setCaffeine(v: Boolean?) = update { it.copy(caffeine = v) }
    fun setFood(v: Boolean?) = update { it.copy(food = v) }
    fun setExercise(v: Boolean?) = update { it.copy(exercise = v, exerciseDay = Store.today()) }
    fun setPartner(v: Int?) = update { it.copy(partner = v) }
    fun setWake(v: Int?) = update { it.copy(wakeMin = v, wakeDay = Store.today()) }
    fun setSleep(v: Int?) = update { it.copy(sleep = v, sleepDay = Store.today()) }

    fun setMedDose(v: String?) = update {
        // one-tap logging: choosing a dose with no time yet fills in the current time
        val t = java.time.LocalTime.now()
        it.copy(medDose = v, medDay = Store.today(),
            medTime = if (v != null && it.medTime == null) t.hour * 60 + t.minute else it.medTime)
    }
    fun setMedTime(v: Int?) = update { it.copy(medTime = v, medDay = Store.today()) }

    fun toggleCause(c: String) { _causes.value = _causes.value.let { if (c in it) it - c else it + c } }
    fun addCause(c: String) { Store.addCause(c); _causes.value = _causes.value + c.trim().replace(Regex("[;|,\n]"), " ").take(30) }
    fun removeCause(c: String) { Store.removeCause(c); _causes.value = _causes.value - c }

    fun save() {
        refresh()
        val order = Store.causeList.value
        val e = Store.entryFrom(_draft.value, order.filter { it in _causes.value })
        Store.add(e)
        Reminders.cancelNotification(getApplication())
        _causes.value = emptySet()   // causes do not carry over
    }
    fun delete(e: Entry) = Store.delete(e)
    fun restore(e: Entry) = Store.add(e)
}
