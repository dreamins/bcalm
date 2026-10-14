@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.example.anxietytracker

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun AppScreen(vm: MainViewModel, onExport: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val snack = remember { SnackbarHostState() }
    Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("Log", "Chart", "History", "Settings").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> LogScreen(vm, snack)
                1 -> ChartScreen(vm)
                2 -> HistoryScreen(vm, snack)
                else -> SettingsScreen(vm, onExport)
            }
        }
    }
}

// ---------------- Log ----------------

@Composable
fun LogScreen(vm: MainViewModel, snack: SnackbarHostState) {
    val d by vm.draft.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val causes by vm.causeList.collectAsStateWithLifecycle()
    val sel by vm.selectedCauses.collectAsStateWithLifecycle()
    val doses by vm.doses.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(30_000) } }
    val last = entries.lastOrNull()
    val ago = last?.let {
        val m = ((now - it.ts) / 60000).coerceAtLeast(0)
        when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 1440 -> "${m / 60} h ${m % 60} min ago"; else -> "${m / 1440} d ago" }
    } ?: "never"

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.height(4.dp))
            Label("Anxiety")
            NumberChips(1..10, d.anxiety, perRow = 5) { vm.setAnxiety(it) }
            Label("Possible causes")
            CausesRow(causes, sel, vm)
            YesNoRow("Caffeine", d.caffeine) { vm.setCaffeine(it) }
            YesNoRow("Food (3h)", d.food) { vm.setFood(it) }
            YesNoRow("Exercise today", d.exercise) { vm.setExercise(it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Partner", Modifier.width(110.dp))
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    PARTNER_LABELS.forEachIndexed { i, l ->
                        SegmentedButton(selected = d.partner == i,
                            onClick = { vm.setPartner(if (d.partner == i) null else i) },
                            shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(l, maxLines = 1) }
                    }
                }
            }
            WakeRow(d.wakeMin) { vm.setWake(it) }
            MedRow(doses, d.medDose, d.medTime, vm)
            Label("Sleep quality (today)")
            NumberChips(1..5, d.sleep, perRow = 5) { vm.setSleep(it) }
        }
        Text("Last saved: $ago", Modifier.padding(vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = {
                vm.save()
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                scope.launch { snack.currentSnackbarData?.dismiss(); snack.showSnackbar("Saved", duration = SnackbarDuration.Short) }
            },
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(16.dp),
        ) { Text("SAVE", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable private fun Label(t: String) =
    Text(t, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

@Composable
fun NumberChips(range: IntRange, selected: Int?, perRow: Int, onPick: (Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        range.toList().chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { n ->
                    FilterChip(selected = selected == n, onClick = { onPick(if (selected == n) null else n) },
                        label = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text("$n", fontSize = 18.sp) } },
                        modifier = Modifier.weight(1f).height(44.dp))
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun YesNoRow(label: String, value: Boolean?, onSet: (Boolean?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(110.dp))
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            SegmentedButton(selected = value == true, onClick = { onSet(if (value == true) null else true) },
                shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Yes") }
            SegmentedButton(selected = value == false, onClick = { onSet(if (value == false) null else false) },
                shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("No") }
        }
    }
}

@Composable
fun CausesRow(causes: List<String>, sel: Set<String>, vm: MainViewModel) {
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        causes.forEach { c ->
            // long-press removes; FilterChip has no long-click, so use a clickable wrapper
            Box(Modifier.combinedClickable(onClick = { vm.toggleCause(c) }, onLongClick = { removing = c })) {
                FilterChip(selected = c in sel, onClick = { vm.toggleCause(c) }, label = { Text(c) },
                    modifier = Modifier.combinedClickable(onClick = { vm.toggleCause(c) }, onLongClick = { removing = c }))
            }
        }
        AssistChip(onClick = { adding = true }, label = { Text("+") })
    }
    if (adding) {
        var text by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { adding = false },
            title = { Text("New cause") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it.take(30) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.addCause(text); adding = false }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } })
    }
    removing?.let { c ->
        AlertDialog(onDismissRequest = { removing = null },
            title = { Text("Remove \"$c\" from list?") },
            confirmButton = { TextButton(onClick = { vm.removeCause(c); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } })
    }
}

@Composable
fun TimeDialog(initial: Int?, onDismiss: () -> Unit, onOk: (Int) -> Unit) {
    val st = rememberTimePickerState(initialHour = (initial ?: 420) / 60, initialMinute = (initial ?: 420) % 60, is24Hour = true)
    AlertDialog(onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onOk(st.hour * 60 + st.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = st) })
}

@Composable
fun MedRow(doses: List<String>, dose: String?, time: Int?, vm: MainViewModel) {
    val label = Store.medName.ifBlank { "Medication" }
    var show by remember { mutableStateOf(false) }
    if (doses.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.width(110.dp))
            Text("Add your dose in Settings", style = MaterialTheme.typography.bodySmall)
        }
    } else {
        FlowRow(verticalArrangement = Arrangement.spacedBy(0.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label)
            doses.forEach { dz ->
                FilterChip(selected = dose == dz, onClick = { vm.setMedDose(if (dose == dz) null else dz) },
                    label = { Text(dz) })
            }
            AssistChip(onClick = { show = true }, label = { Text(time?.let { fmtMin(it) } ?: "Time") })
            AssistChip(onClick = { val t = java.time.LocalTime.now(); vm.setMedTime(t.hour * 60 + t.minute) },
                label = { Text("Now") })
            if (time != null) AssistChip(onClick = { vm.setMedTime(null) }, label = { Text("Clear") })
        }
    }
    if (show) TimeDialog(time, { show = false }) { vm.setMedTime(it); show = false }
}

@Composable
fun WakeRow(wake: Int?, onSet: (Int?) -> Unit) {
    var show by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Wake time", Modifier.width(102.dp))
        FilledTonalButton(onClick = { show = true }) { Text(wake?.let { fmtMin(it) } ?: "Set") }
        AssistChip(onClick = {
            val t = java.time.LocalTime.now(); onSet(t.hour * 60 + t.minute)
        }, label = { Text("Now") })
        if (wake != null) AssistChip(onClick = { onSet(null) }, label = { Text("Clear") })
    }
    if (show) TimeDialog(wake, { show = false }) { onSet(it); show = false }
}

// ---------------- Chart ----------------

@Composable
fun ChartScreen(vm: MainViewModel) {
    val all by vm.entries.collectAsStateWithLifecycle()
    var range by remember { mutableIntStateOf(30) }  // 7, 30, 0 = all
    val cutoff = if (range == 0) 0L else
        LocalDate.now().minusDays(range - 1L).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val data = all.filter { it.anxiety != null && it.ts >= cutoff }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7 to "7 days", 30 to "30 days", 0 to "All").forEach { (v, l) ->
                FilterChip(selected = range == v, onClick = { range = v }, label = { Text(l) })
            }
        }
        AnxietyChart(data, Modifier.fillMaxWidth().height(300.dp))
        Text("${data.size} entries. Dots: entries; line: hourly average.",
            style = MaterialTheme.typography.bodySmall)
        // causes stats
        val stats = data.flatMap { e -> e.causes.map { it to e.anxiety!! } }
            .groupBy({ it.first }, { it.second })
            .map { Triple(it.key, it.value.size, it.value.average()) }
            .sortedByDescending { it.second }
        if (stats.isNotEmpty()) {
            Text("Causes", style = MaterialTheme.typography.titleSmall)
            stats.forEach { (c, n, avg) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(c); Text("$n x   avg %.1f".format(avg))
                }
            }
        }
    }
}

@Composable
fun AnxietyChart(data: List<Entry>, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val axis = MaterialTheme.colorScheme.outline
    val dot = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    val line = MaterialTheme.colorScheme.tertiary
    val txt = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val l = 36.dp.toPx(); val b = 24.dp.toPx(); val t = 8.dp.toPx(); val r = 8.dp.toPx()
        val w = size.width - l - r; val h = size.height - t - b
        fun x(hr: Float) = l + w * hr / 24f
        fun y(a: Float) = t + h * (1 - (a - 1f) / 9f)
        val style = TextStyle(color = txt, fontSize = 10.sp)
        for (a in 1..10) {
            drawLine(axis.copy(alpha = 0.3f), Offset(l, y(a.toFloat())), Offset(l + w, y(a.toFloat())))
            val m = measurer.measure("$a", style)
            drawText(m, topLeft = Offset(l - m.size.width - 4.dp.toPx(), y(a.toFloat()) - m.size.height / 2f))
        }
        for (hr in 0..24 step 4) {
            drawLine(axis.copy(alpha = 0.3f), Offset(x(hr.toFloat()), t), Offset(x(hr.toFloat()), t + h))
            val m = measurer.measure("$hr", style)
            drawText(m, topLeft = Offset(x(hr.toFloat()) - m.size.width / 2f, t + h + 4.dp.toPx()))
        }
        drawRect(axis, Offset(l, t), androidx.compose.ui.geometry.Size(w, h), style = Stroke(1.dp.toPx()))
        data.forEach { e -> drawCircle(dot, 4.dp.toPx(), Offset(x(e.hourOfDay), y(e.anxiety!!.toFloat()))) }
        val avgs = (0..23).mapNotNull { hr ->
            val xs = data.filter { it.dateTime.hour == hr }.map { it.anxiety!! }
            if (xs.isEmpty()) null else Offset(x(hr + 0.5f), y(xs.average().toFloat()))
        }
        if (avgs.size > 1) {
            val p = Path().apply { moveTo(avgs[0].x, avgs[0].y); avgs.drop(1).forEach { lineTo(it.x, it.y) } }
            drawPath(p, line, style = Stroke(2.5.dp.toPx()))
        }
        avgs.forEach { drawCircle(line, 3.dp.toPx(), it) }
    }
}

// ---------------- History ----------------

@Composable
fun HistoryScreen(vm: MainViewModel, snack: SnackbarHostState) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val fmt = remember { java.time.format.DateTimeFormatter.ofPattern("EEE d MMM HH:mm") }
    if (entries.isEmpty()) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No entries yet") }; return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(entries.reversed(), key = { it.ts }) { e ->
            val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
                if (v != SwipeToDismissBoxValue.Settled) {
                    vm.delete(e)
                    scope.launch {
                        snack.currentSnackbarData?.dismiss()
                        if (snack.showSnackbar("Deleted", "Undo", duration = SnackbarDuration.Short) ==
                            SnackbarResult.ActionPerformed) vm.restore(e)
                    }
                    true
                } else false
            })
            SwipeToDismissBox(state, backgroundContent = {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer)
                    .padding(16.dp), Alignment.CenterEnd) { Text("Delete") }
            }) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(e.dateTime.format(fmt), fontWeight = FontWeight.Bold)
                        Text(e.summary(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

// ---------------- Settings ----------------

@Composable
fun SettingsScreen(vm: MainViewModel, onExport: () -> Unit) {
    val ctx = LocalContext.current
    var hours by remember { mutableIntStateOf(Store.intervalHours) }
    val doses by vm.doses.collectAsStateWithLifecycle()
    var medName by remember { mutableStateOf(Store.medName) }
    var addDose by remember { mutableStateOf(false) }
    var rmDose by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Reminder interval (08:00-22:00)", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Off", 2 to "2h", 3 to "3h", 4 to "4h").forEach { (v, l) ->
                FilterChip(selected = hours == v, onClick = {
                    hours = v; Store.intervalHours = v; Reminders.schedule(ctx, v, replace = true)
                }, label = { Text(l) })
            }
        }
        Text("Reminders disappear after 30 minutes if ignored.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Medication", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(value = medName, onValueChange = { medName = it.take(30); Store.medName = medName },
            label = { Text("Name (optional, used as row label)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Text("Doses (long-press to remove)", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            doses.forEach { dz ->
                AssistChip(onClick = {}, label = { Text(dz) },
                    modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { rmDose = dz }))
            }
            AssistChip(onClick = { addDose = true }, label = { Text("+ Add dose") })
        }
        HorizontalDivider()
        Button(onClick = onExport, Modifier.fillMaxWidth()) { Text("Export CSV") }
    }

    if (addDose) {
        var text by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { addDose = false },
            title = { Text("New dose") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it.take(30) }, singleLine = true,
                placeholder = { Text("e.g. 10 mg") }) },
            confirmButton = { TextButton(onClick = { Store.addDose(text); addDose = false }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { addDose = false }) { Text("Cancel") } })
    }
    rmDose?.let { dz ->
        AlertDialog(onDismissRequest = { rmDose = null },
            title = { Text("Remove \"$dz\"?") },
            confirmButton = { TextButton(onClick = { Store.removeDose(dz); rmDose = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { rmDose = null }) { Text("Keep") } })
    }
}
