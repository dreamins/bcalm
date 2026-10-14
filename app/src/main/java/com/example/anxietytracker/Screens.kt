@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.example.anxietytracker

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

private val SmallShape = RoundedCornerShape(6.dp)

// ---------------- App shell ----------------

@Composable
fun AppScreen(vm: MainViewModel, onExport: () -> Unit, initialTab: Int = 0) {
    var tab by remember { mutableIntStateOf(initialTab.coerceIn(0, 3)) }
    val snack = remember { SnackbarHostState() }
    val cs = MaterialTheme.colorScheme
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            Column {
                HorizontalDivider(color = cs.outlineVariant)
                NavigationBar(containerColor = cs.background, tonalElevation = 0.dp) {
                    listOf("Log", "Chart", "History", "Settings").forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = tab == i, onClick = { tab = i },
                            icon = { TabIcon(i) }, label = { Text(t) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = cs.primary, selectedTextColor = cs.primary,
                                indicatorColor = Color.Transparent,
                                unselectedIconColor = cs.onSurfaceVariant,
                                unselectedTextColor = cs.onSurfaceVariant))
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> LogScreen(vm, snack)
                1 -> ChartScreen(vm)
                2 -> HistoryScreen(vm, snack)
                else -> SettingsScreen(vm, onExport)
            }
        }
    }
}

@Composable
private fun TabIcon(i: Int) {
    when (i) {
        0 -> Icon(Icons.Filled.Edit, contentDescription = null)
        1 -> ChartGlyph()
        2 -> Icon(Icons.Filled.DateRange, contentDescription = null)
        else -> Icon(Icons.Filled.Settings, contentDescription = null)
    }
}

/** Three simple bars; the core icon set has no chart icon. */
@Composable
private fun ChartGlyph() {
    val c = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24f
        fun bar(x: Float, top: Float) = drawRoundRect(c, Offset(x * u, top * u),
            Size(4 * u, (20 - top) * u), CornerRadius(1 * u, 1 * u))
        bar(4f, 11f); bar(10f, 5f); bar(16f, 14f)
    }
}

// ---------------- Shared building blocks ----------------

@Composable
private fun ScreenTitle(t: String) =
    Text(t, style = MaterialTheme.typography.titleLarge)

/** Small grey caption used as a section header. */
@Composable
private fun SectionHeader(t: String, modifier: Modifier = Modifier) =
    Text(t.uppercase(), modifier, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Caption(t: String, modifier: Modifier = Modifier) =
    Text(t, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/**
 * The one control for choosing among a few options: a hairline-outlined segmented row.
 * Selected = solid accent with white text. Tapping the selected option clears it when [allowClear].
 * [hint] marks a carried-over (not yet confirmed) value with bold accent text.
 */
@Composable
fun Segmented(
    options: List<String>, selected: Int?, modifier: Modifier = Modifier,
    height: Dp = 44.dp, allowClear: Boolean = true, hint: Int? = null,
    textSize: Int = 15, onPick: (Int?) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(modifier.height(height).clip(SmallShape).border(1.dp, cs.outline, SmallShape).selectableGroup()) {
        options.forEachIndexed { i, label ->
            val sel = selected == i
            if (i > 0) {
                val joined = sel || selected == i - 1
                Box(Modifier.fillMaxHeight().width(1.dp).background(if (joined) cs.primary else cs.outline))
            }
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .background(if (sel) cs.primary else Color.Transparent)
                    .selectable(selected = sel, role = Role.RadioButton,
                        onClick = { onPick(if (sel && allowClear) null else i) }),
                contentAlignment = Alignment.Center,
            ) {
                val isHint = !sel && hint == i
                Text(label, maxLines = 1, fontSize = textSize.sp,
                    fontWeight = if (isHint) FontWeight.Bold else FontWeight.Normal,
                    color = if (sel) cs.onPrimary else if (isHint) cs.primary else cs.onSurface)
            }
        }
    }
}

/** Label on the left, control on the right. */
@Composable
private fun LabeledRow(label: String, control: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        control()
    }
}

/** A text row with a check on the right when selected; hairline underneath. */
@Composable
private fun CheckRow(label: String, selected: Boolean, modifier: Modifier = Modifier,
                     onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(modifier) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .combinedClickable(role = Role.Checkbox, onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) cs.primary else cs.onSurface)
            if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected",
                modifier = Modifier.size(18.dp), tint = cs.primary)
        }
        HorizontalDivider(color = cs.outlineVariant)
    }
}

private fun boolToIdx(b: Boolean?): Int? = b?.let { if (it) 0 else 1 }
private fun idxToBool(i: Int?): Boolean? = i?.let { it == 0 }
private val YES_NO = listOf("Yes", "No")

// ---------------- Log ----------------

@Composable
fun LogScreen(vm: MainViewModel, snack: SnackbarHostState) {
    val d by vm.draft.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val causes by vm.causeList.collectAsStateWithLifecycle()
    val sel by vm.selectedCauses.collectAsStateWithLifecycle()
    val activeEntry by vm.activeEntry.collectAsStateWithLifecycle()
    val todayOpen by vm.todayOpen.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(30_000) } }
    val timeFmt = remember { java.time.format.DateTimeFormatter.ofPattern("HH:mm") }

    val active = activeEntry
    val last = entries.lastOrNull()
    val status = when {
        active != null -> "Logged ${active.anxiety} at ${active.dateTime.format(timeFmt)} — changes update this entry"
        last != null -> {
            val m = ((now - last.ts) / 60000).coerceAtLeast(0)
            "Last logged " + when {
                m < 1 -> "just now"; m < 60 -> "$m min ago"
                m < 1440 -> "${m / 60} h ${m % 60} min ago"; else -> "${m / 1440} d ago"
            }
        }
        else -> "Nothing logged yet"
    }

    val anyDailyUnset = d.wakeMin == null || d.sleep == null || d.exercise == null
    val todayExpanded = todayOpen ?: anyDailyUnset

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(8.dp))
        Text("How anxious are you right now?", style = MaterialTheme.typography.titleMedium)
        Column {
            Segmented(
                options = (1..10).map { it.toString() },
                selected = active?.anxiety?.minus(1),
                hint = if (active == null) d.anxiety?.minus(1) else null,
                height = 52.dp, allowClear = false, textSize = 17,
            ) { i ->
                if (i != null) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val r = vm.tapAnxiety(i + 1)
                    if (r != null) scope.launch {
                        snack.currentSnackbarData?.dismiss()
                        val res = snack.showSnackbar(r.message, actionLabel = "Undo",
                            duration = SnackbarDuration.Short)
                        if (res == SnackbarResult.ActionPerformed) r.undo()
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Caption("Calm"); Caption("Overwhelmed")
            }
        }
        Caption(status)

        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What's going on?", style = MaterialTheme.typography.titleSmall)
            Caption("optional")
        }
        CausesList(causes, sel, vm)

        Spacer(Modifier.height(2.dp))
        LabeledRow("Caffeine") {
            Segmented(YES_NO, boolToIdx(d.caffeine), Modifier.width(140.dp)) { vm.setCaffeine(idxToBool(it)) }
        }
        LabeledRow("Food in the last 3h") {
            Segmented(YES_NO, boolToIdx(d.food), Modifier.width(140.dp)) { vm.setFood(idxToBool(it)) }
        }
        LabeledRow("Partner") {
            Segmented(PARTNER_DISPLAY, d.partner, Modifier.width(240.dp)) { vm.setPartner(it) }
        }

        TodayCard(d, todayExpanded, onToggle = { vm.setTodayOpen(!todayExpanded) }, vm = vm)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TodayCard(d: Draft, expanded: Boolean, onToggle: () -> Unit, vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    var showTime by remember { mutableStateOf(false) }
    val summary = listOf(
        d.wakeMin?.let { "Woke ${fmtMin(it)}" } ?: "Woke —",
        d.sleep?.let { "Slept $it/5" } ?: "Slept —",
        d.exercise?.let { if (it) "Exercised" else "No exercise" } ?: "Exercise —",
    ).joinToString(" · ")
    Column(Modifier.fillMaxWidth().border(1.dp, cs.outlineVariant, SmallShape)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onToggle)
            .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionHeader("Today")
                Text(summary, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = cs.onSurfaceVariant)
        }
        if (expanded) {
            HorizontalDivider(color = cs.outlineVariant)
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                LabeledRow("Woke at") {
                    TextButton(onClick = { showTime = true }) { Text(d.wakeMin?.let { fmtMin(it) } ?: "Set") }
                    TextButton(onClick = {
                        val t = java.time.LocalTime.now(); vm.setWake(t.hour * 60 + t.minute)
                    }) { Text("Now") }
                    if (d.wakeMin != null) TextButton(onClick = { vm.setWake(null) }) { Text("Clear") }
                }
                Text("Sleep quality last night", style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
                Segmented((1..5).map { it.toString() }, d.sleep?.minus(1)) { vm.setSleep(it?.plus(1)) }
                Spacer(Modifier.height(4.dp))
                LabeledRow("Exercised today") {
                    Segmented(YES_NO, boolToIdx(d.exercise), Modifier.width(140.dp)) { vm.setExercise(idxToBool(it)) }
                }
            }
        }
    }
    if (showTime) TimeDialog(d.wakeMin, { showTime = false }) { vm.setWake(it); showTime = false }
}

@Composable
fun CausesList(causes: List<String>, sel: Set<String>, vm: MainViewModel) {
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        causes.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                pair.forEach { c ->
                    CheckRow(c, c in sel, Modifier.weight(1f), onLongClick = { removing = c }) { vm.toggleCause(c) }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        TextButton(onClick = { adding = true }) { Text("Add cause…") }
    }
    if (adding) {
        var text by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { adding = false },
            title = { Text("New cause") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it.take(30) }, singleLine = true,
                shape = SmallShape) },
            confirmButton = { TextButton(onClick = { vm.addCause(text); adding = false }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } })
    }
    removing?.let { c ->
        AlertDialog(onDismissRequest = { removing = null },
            title = { Text("Remove \"$c\" from the list?") },
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

// ---------------- Chart ----------------

@Composable
fun ChartScreen(vm: MainViewModel) {
    val all by vm.entries.collectAsStateWithLifecycle()
    var range by remember { mutableIntStateOf(30) }  // 7, 30, 0 = all
    val cutoff = if (range == 0) 0L else
        LocalDate.now().minusDays(range - 1L).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val data = all.filter { it.anxiety != null && it.ts >= cutoff }
    val ranges = listOf(7, 30, 0)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Chart")
        Segmented(listOf("7 days", "30 days", "All"), ranges.indexOf(range), allowClear = false) {
            if (it != null) range = ranges[it]
        }
        AnxietyChart(data, Modifier.fillMaxWidth().height(300.dp))
        Caption("${data.size} entries. Dots are entries; the line is the hourly average.")
        val stats = data.flatMap { e -> e.causes.map { it to e.anxiety!! } }
            .groupBy({ it.first }, { it.second })
            .map { Triple(it.key, it.value.size, it.value.average()) }
            .sortedByDescending { it.second }
        if (stats.isNotEmpty()) {
            SectionHeader("Common causes")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            stats.forEach { (c, n, avg) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(c)
                    Text("%d×  ·  avg %.1f".format(n, avg), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
fun AnxietyChart(data: List<Entry>, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val axis = MaterialTheme.colorScheme.outline
    val dot = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val line = MaterialTheme.colorScheme.primary
    val txt = MaterialTheme.colorScheme.onSurfaceVariant
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
        drawRect(axis, Offset(l, t), Size(w, h), style = Stroke(1.dp.toPx()))
        data.forEach { e -> drawCircle(dot, 4.dp.toPx(), Offset(x(e.hourOfDay), y(e.anxiety!!.coerceIn(1, 10).toFloat()))) }
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
    val cs = MaterialTheme.colorScheme
    val fmt = remember { java.time.format.DateTimeFormatter.ofPattern("EEE d MMM HH:mm") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item { ScreenTitle("History"); Spacer(Modifier.height(8.dp)) }
        if (entries.isEmpty()) {
            item { Caption("Nothing here yet. Entries show up once you log.") }
        }
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
                Box(Modifier.fillMaxSize().background(cs.error).padding(16.dp), Alignment.CenterEnd) {
                    Text("Delete", color = cs.onError)
                }
            }) {
                Column(Modifier.fillMaxWidth().background(cs.background)) {
                    Column(Modifier.padding(vertical = 10.dp)) {
                        Text(e.dateTime.format(fmt), style = MaterialTheme.typography.titleSmall)
                        Text(e.summary(), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    HorizontalDivider(color = cs.outlineVariant)
                }
            }
        }
    }
}

// ---------------- Settings ----------------

@Composable
private fun SettingsSection(title: String, hint: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title, Modifier.padding(bottom = 6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        content()
        if (hint != null) Caption(hint, Modifier.padding(top = 6.dp))
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel, onExport: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var hours by remember { mutableIntStateOf(Store.intervalHours) }
    val doses by vm.doses.collectAsStateWithLifecycle()
    var medName by remember { mutableStateOf(Store.medName) }
    var curDose by remember { mutableStateOf(Store.medCurrentDose) }
    var usualTime by remember { mutableStateOf(Store.medUsualTime) }
    var showTime by remember { mutableStateOf(false) }
    var addDose by remember { mutableStateOf(false) }
    var rmDose by remember { mutableStateOf<String?>(null) }
    val syncUri by Store.syncUri.collectAsStateWithLifecycle()
    val syncState by Store.syncState.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) CsvSync.setTarget(ctx, uri)
    }
    val intervals = listOf(0, 2, 3, 4)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        ScreenTitle("Settings")

        SettingsSection("Reminders",
            "A gentle nudge between 08:00 and 22:00. It clears itself after 30 minutes.") {
            LabeledRow("Remind me every") {
                Segmented(listOf("Off", "2h", "3h", "4h"), intervals.indexOf(hours),
                    Modifier.width(200.dp), allowClear = false) { i ->
                    if (i != null) {
                        val v = intervals[i]
                        hours = v; Store.intervalHours = v; Reminders.schedule(ctx, v, replace = true)
                    }
                }
            }
        }

        SettingsSection("Medication",
            "Set once. It is recorded with every entry. Long-press a dose to remove it.") {
            OutlinedTextField(value = medName, onValueChange = { medName = it.take(30); Store.medName = medName },
                label = { Text("Name (optional)") }, singleLine = true, shape = SmallShape,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            doses.forEach { dz ->
                CheckRow(dz, curDose == dz, onLongClick = { rmDose = dz }) {
                    curDose = if (curDose == dz) null else dz; Store.medCurrentDose = curDose
                }
            }
            TextButton(onClick = { addDose = true }) { Text("Add dose…") }
            HorizontalDivider(color = cs.outlineVariant)
            LabeledRow("Usual time") {
                TextButton(onClick = { showTime = true }) { Text(usualTime?.let { fmtMin(it) } ?: "Set") }
                if (usualTime != null) TextButton(onClick = { usualTime = null; Store.medUsualTime = null }) {
                    Text("Clear")
                }
            }
        }

        SettingsSection("Sync to Google Drive") {
            val target = syncUri
            if (target == null) {
                Caption("Choose a file in Google Drive using the system picker. It is rewritten after every change.",
                    Modifier.padding(vertical = 8.dp))
                TextButton(onClick = { picker.launch("bcalm.csv") }) { Text("Choose file…") }
            } else {
                val name by produceState("selected file", target) {
                    value = withContext(Dispatchers.IO) { CsvSync.displayName(ctx, target) }
                }
                val fmt = remember { java.time.format.DateTimeFormatter.ofPattern("EEE d MMM HH:mm") }
                LabeledRow("File") { Text(name, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Text(
                    if (syncState.message.isEmpty()) "Not synced yet" else {
                        val at = java.time.LocalDateTime.ofInstant(
                            java.time.Instant.ofEpochMilli(syncState.time), ZoneId.systemDefault())
                        "Last sync: ${at.format(fmt)} — ${syncState.message}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (syncState.message.startsWith("Failed")) cs.error else cs.onSurfaceVariant)
                Row {
                    TextButton(onClick = { CsvSync.enqueue(ctx) }) { Text("Sync now") }
                    TextButton(onClick = { CsvSync.stop(ctx) }) { Text("Stop syncing") }
                }
            }
        }

        SettingsSection("Data") {
            TextButton(onClick = onExport) { Text("Export CSV") }
        }
    }

    if (showTime) TimeDialog(usualTime ?: 480, { showTime = false }) {
        usualTime = it; Store.medUsualTime = it; showTime = false
    }
    if (addDose) {
        var text by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { addDose = false },
            title = { Text("New dose") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it.take(30) }, singleLine = true,
                shape = SmallShape, placeholder = { Text("e.g. 10 mg") }) },
            confirmButton = { TextButton(onClick = { Store.addDose(text); addDose = false }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { addDose = false }) { Text("Cancel") } })
    }
    rmDose?.let { dz ->
        AlertDialog(onDismissRequest = { rmDose = null },
            title = { Text("Remove \"$dz\"?") },
            confirmButton = { TextButton(onClick = { Store.removeDose(dz); if (curDose == dz) curDose = null; rmDose = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { rmDose = null }) { Text("Keep") } })
    }
}
