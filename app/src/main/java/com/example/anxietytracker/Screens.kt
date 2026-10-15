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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
 */
@Composable
fun Segmented(
    options: List<String>, selected: Int?, modifier: Modifier = Modifier,
    height: Dp = 44.dp, allowClear: Boolean = true,
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
                Text(label, maxLines = 1, fontSize = textSize.sp,
                    color = if (sel) cs.onPrimary else cs.onSurface)
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
    LaunchedEffect(Unit) {
        vm.logEvents.collect { r ->
            scope.launch {
                snack.currentSnackbarData?.dismiss()
                val res = snack.showSnackbar(r.message, actionLabel = "Undo", duration = SnackbarDuration.Short)
                if (res == SnackbarResult.ActionPerformed) r.undo()
            }
        }
    }
    val status = when {
        active != null -> "Logged ${active.anxiety?.let { "$it " } ?: ""}at ${active.dateTime.format(timeFmt)} — changes update this entry"
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

        TodayCard(d, entries, todayExpanded, onToggle = { vm.setTodayOpen(!todayExpanded) }, vm = vm)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TodayCard(d: Draft, entries: List<Entry>, expanded: Boolean, onToggle: () -> Unit, vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    var showTime by remember { mutableStateOf(false) }
    var otherDose by remember { mutableStateOf(false) }
    val dose by vm.medDose.collectAsStateWithLifecycle()
    val unit = Store.medUnit
    // Quick picks: the current dose plus the most recently used ones, smallest first.
    val doseOptions = remember(entries, dose) {
        (listOfNotNull(dose) + entries.asReversed().mapNotNull { it.medDose }).distinct().take(4).sorted()
    }
    val summary = listOfNotNull(
        d.wakeMin?.let { "Woke ${fmtMin(it)}" } ?: "Woke —",
        d.sleep?.let { "Slept $it/5" } ?: "Slept —",
        d.exercise?.let { if (it) "Exercised" else "No exercise" } ?: "Exercise —",
        dose?.let { "Med " + fmtDose(it, unit) },
        if (d.nightSweats == true) "Night sweats" else null,
        if (d.alcohol == true) "Alcohol" else null,
        if (d.cannabis == true) "Cannabis" else null,
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
                Text("Sleep quality", style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
                Segmented((1..5).map { it.toString() }, d.sleep?.minus(1)) { vm.setSleep(it?.plus(1)) }
                Spacer(Modifier.height(4.dp))
                LabeledRow("Exercise") {
                    Segmented(YES_NO, boolToIdx(d.exercise), Modifier.width(140.dp)) { vm.setExercise(idxToBool(it)) }
                }
                LabeledRow("Night sweats") {
                    Segmented(YES_NO, boolToIdx(d.nightSweats), Modifier.width(140.dp)) { vm.setNightSweats(idxToBool(it)) }
                }
                LabeledRow("Alcohol") {
                    Segmented(YES_NO, boolToIdx(d.alcohol), Modifier.width(140.dp)) { vm.setAlcohol(idxToBool(it)) }
                }
                LabeledRow("Cannabis") {
                    Segmented(YES_NO, boolToIdx(d.cannabis), Modifier.width(140.dp)) { vm.setCannabis(idxToBool(it)) }
                }
                LabeledRow(if (unit.isBlank()) "Med dose" else "Med dose ($unit)") {
                    if (doseOptions.isNotEmpty()) {
                        Segmented(doseOptions.map { fmtNum(it) }, doseOptions.indexOf(dose).takeIf { it >= 0 },
                            Modifier.width((52 * doseOptions.size).dp)) { i -> vm.setMedDose(i?.let { doseOptions[it] }) }
                    }
                    TextButton(onClick = { otherDose = true }) { Text(if (doseOptions.isEmpty()) "Set" else "Other") }
                }
            }
        }
    }
    if (showTime) TimeDialog(d.wakeMin, { showTime = false }) { vm.setWake(it); showTime = false }
    if (otherDose) DoseDialog(dose, unit, { otherDose = false }) { vm.setMedDose(it); otherDose = false }
}

@Composable
private fun DoseDialog(initial: Double?, unit: String, onDismiss: () -> Unit, onOk: (Double) -> Unit) {
    var text by remember { mutableStateOf(initial?.let { fmtNum(it) } ?: "") }
    val value = text.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Dose") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(8) },
                singleLine = true, shape = SmallShape, suffix = { if (unit.isNotBlank()) Text(unit) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        },
        confirmButton = { TextButton(onClick = { value?.let(onOk) }, enabled = value != null) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
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
    val data = remember(all, range) {
        val cutoff = if (range == 0) 0L else
            LocalDate.now().minusDays(range - 1L).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        all.filter { it.anxiety != null && it.ts >= cutoff }
    }
    val ins = remember(all, range) {
        val cutoff = if (range == 0) 0L else
            LocalDate.now().minusDays(range - 1L).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        computeInsights(all.filter { it.ts >= cutoff })
    }
    val cs = MaterialTheme.colorScheme
    val ranges = listOf(7, 30, 0)
    var view by remember { mutableIntStateOf(Store.chartView) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Chart")
        Segmented(listOf("7 days", "30 days", "All"), ranges.indexOf(range), allowClear = false) {
            if (it != null) range = ranges[it]
        }
        Segmented(listOf("By hour of day", "Over time"), view, allowClear = false) {
            if (it != null) { view = it; Store.chartView = it }
        }
        Text(ins.sentence, style = MaterialTheme.typography.bodyLarge)
        if (ins.count > 0) Caption("${ins.count} entries · average ${"%.1f".format(ins.avg)}")
        SectionHeader(if (view == 0) "Anxiety by hour of day" else "Anxiety over time")
        if (view == 0) {
            HourOfDayChart(data, Modifier.fillMaxWidth().height(220.dp))
            Caption("Hour of day. Bars are the hourly average; dots are single entries.")
        } else {
            TimeSeriesChart(data, range, Modifier.fillMaxWidth().height(220.dp))
            Caption("Date. Line is the daily average; dots are single entries.")
        }

        if (ins.causes.isNotEmpty() || ins.factors.isNotEmpty() || ins.otherCauses > 0) {
            Spacer(Modifier.height(4.dp))
            SectionHeader("Patterns")
            Column {
                HorizontalDivider(color = cs.outlineVariant)
                ins.causes.forEach { PatternRow(it.label, "${it.n}×", it.diff) }
                ins.factors.forEach { PatternRow(it.label, null, it.diff) }
            }
            if (ins.otherCauses > 0) {
                Caption(if (ins.otherCauses == 1) "1 other cause with too few entries"
                        else "${ins.otherCauses} other causes with too few entries")
            }
            Caption("Differences are in average anxiety, compared with your overall average (causes) or with the opposite case (factors).")
        }

        val sweat = remember(all, range) {
            val cutoff = if (range == 0) 0L else
                LocalDate.now().minusDays(range - 1L).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            computeSweatInsights(all, cutoff)
        }
        if (sweat != null) {
            Spacer(Modifier.height(4.dp))
            SectionHeader("Night sweats")
            Column {
                HorizontalDivider(color = cs.outlineVariant)
                SweatRowView("Night sweats on ${sweat.sweats} of ${sweat.nights} nights", null)
                sweat.lines.forEach { SweatRowView(it.label, it.value) }
            }
            Caption("Compared with the evening before. Counts per calendar day, using the latest answer that day. Comparisons need at least 2 nights on each side. These are plain counts, not an explanation.")
        }
    }
}

@Composable
private fun SweatRowView(label: String, value: String?) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(if (value == null) 1f else 0.4f), style = MaterialTheme.typography.bodyLarge)
        if (value != null) Text(value, Modifier.weight(0.6f), textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    }
    HorizontalDivider(color = cs.outlineVariant)
}

@Composable
private fun PatternRow(label: String, count: String?, diff: Double) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count != null) Text(count, Modifier.padding(end = 16.dp),
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        Text(fmtDiff(diff), Modifier.widthIn(min = 44.dp), textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider(color = cs.outlineVariant)
}

/** Hourly mean bars (thin, accent at low alpha), faint dots for single entries, hairline baseline. */
@Composable
fun HourOfDayChart(data: List<Entry>, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val cs = MaterialTheme.colorScheme
    val grid = cs.outlineVariant
    val baseline = cs.outline
    val dot = cs.onSurfaceVariant.copy(alpha = 0.5f)
    val bar = cs.primary.copy(alpha = 0.45f)
    val txt = cs.onSurfaceVariant
    Canvas(modifier) {
        val l = 28.dp.toPx(); val b = 24.dp.toPx(); val t = 8.dp.toPx(); val r = 8.dp.toPx()
        val w = size.width - l - r; val h = size.height - t - b
        val hours = data.map { it.dateTime.hour }
        val lo = (minOf(6, hours.minOrNull() ?: 6) / 3) * 3
        val hi = minOf(24, ((maxOf(21, (hours.maxOrNull() ?: 21) + 1) + 2) / 3) * 3)
        val span = (hi - lo).toFloat()
        fun x(hr: Float) = l + w * (hr - lo) / span
        fun y(a: Float) = t + h * (1 - a / 10f)   // baseline = 0, top = 10
        val style = TextStyle(color = txt, fontSize = 10.sp)
        for (a in listOf(1, 5, 10)) {
            if (a > 1) drawLine(grid, Offset(l, y(a.toFloat())), Offset(l + w, y(a.toFloat())), 1.dp.toPx())
            val m = measurer.measure("$a", style)
            drawText(m, topLeft = Offset(l - m.size.width - 6.dp.toPx(), y(a.toFloat()) - m.size.height / 2f))
        }
        var hr = lo
        while (hr < hi) {
            val m = measurer.measure("$hr", style)
            drawText(m, topLeft = Offset(x(hr + 0.5f) - m.size.width / 2f, t + h + 6.dp.toPx()))
            hr += 3
        }
        val slot = w / span
        val barW = maxOf(3.dp.toPx(), slot * 0.45f)
        for (hh in lo until hi) {
            val xs = data.filter { it.dateTime.hour == hh }.map { it.anxiety!! }
            if (xs.isEmpty()) continue
            val top = y(xs.average().toFloat())
            drawRect(bar, Offset(x(hh + 0.5f) - barW / 2f, top), Size(barW, y(0f) - top))
        }
        data.forEach { e ->
            drawCircle(dot, 2.5.dp.toPx(), Offset(x(e.hourOfDay), y(e.anxiety!!.coerceIn(1, 10).toFloat())))
        }
        drawLine(baseline, Offset(l, y(0f)), Offset(l + w, y(0f)), 1.dp.toPx())
    }
}

/** Daily-average line (accent) over faint dots for single entries, on a date axis. */
@Composable
fun TimeSeriesChart(data: List<Entry>, range: Int, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val cs = MaterialTheme.colorScheme
    val grid = cs.outlineVariant
    val baseline = cs.outline
    val dot = cs.onSurfaceVariant.copy(alpha = 0.5f)
    val line = cs.primary
    val txt = cs.onSurfaceVariant
    val fmt = remember { DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()) }
    Canvas(modifier) {
        val l = 28.dp.toPx(); val b = 24.dp.toPx(); val t = 8.dp.toPx(); val r = 8.dp.toPx()
        val w = size.width - l - r; val h = size.height - t - b
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val first = when {
            range > 0 -> today.minusDays(range - 1L)
            data.isEmpty() -> today
            else -> minOf(today, data.minOf { it.dateTime.toLocalDate() })
        }
        val t0 = first.atStartOfDay(zone).toInstant().toEpochMilli()
        val t1 = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val span = (t1 - t0).toFloat()
        fun x(ts: Long) = l + w * (ts - t0) / span
        fun y(a: Float) = t + h * (1 - a / 10f)   // baseline = 0, top = 10
        val style = TextStyle(color = txt, fontSize = 10.sp)
        for (a in listOf(1, 5, 10)) {
            if (a > 1) drawLine(grid, Offset(l, y(a.toFloat())), Offset(l + w, y(a.toFloat())), 1.dp.toPx())
            val m = measurer.measure("$a", style)
            drawText(m, topLeft = Offset(l - m.size.width - 6.dp.toPx(), y(a.toFloat()) - m.size.height / 2f))
        }
        val days = (today.toEpochDay() - first.toEpochDay() + 1).toInt()
        val step = (days + 4) / 5
        var d = 0
        while (d < days) {
            val day = first.plusDays(d.toLong())
            val m = measurer.measure(day.format(fmt), style)
            val noon = x(day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli())
            val lx = (noon - m.size.width / 2f).coerceIn(0f, size.width - m.size.width)
            drawText(m, topLeft = Offset(lx, t + h + 6.dp.toPx()))
            d += step
        }
        data.forEach { e ->
            drawCircle(dot, 2.5.dp.toPx(), Offset(x(e.ts), y(e.anxiety!!.coerceIn(1, 10).toFloat())))
        }
        val pts = data.groupBy { it.dateTime.toLocalDate() }.toSortedMap().map { (day, es) ->
            Offset(x(day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()),
                y(es.map { it.anxiety!!.coerceIn(1, 10) }.average().toFloat()))
        }
        if (pts.size > 1) {
            val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
            drawPath(path, line, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        pts.forEach { drawCircle(line, 3.dp.toPx(), it) }
        drawLine(baseline, Offset(l, y(0f)), Offset(l + w, y(0f)), 1.dp.toPx())
    }
}

// ---------------- History ----------------

private class DayGroup(val day: LocalDate, val rows: List<Entry>)

/** Only the notable details: causes, then context that is not the unremarkable default. */
private fun detailLine(e: Entry): String {
    val parts = ArrayList<String>(e.causes)
    if (e.caffeine == true) parts.add("Caffeine")
    if (e.food == false) parts.add("No food")
    if (e.partner == 2) parts.add("Partner tense")
    return parts.joinToString(" · ")
}

@Composable
fun HistoryScreen(vm: MainViewModel, snack: SnackbarHostState) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val timeFmt = remember { java.time.format.DateTimeFormatter.ofPattern("HH:mm") }
    val dayFmt = remember { java.time.format.DateTimeFormatter.ofPattern("EEE d MMM") }
    val groups = remember(entries) {
        entries.groupBy { it.dateTime.toLocalDate() }
            .map { (d, l) -> DayGroup(d, l.sortedByDescending { it.ts }) }
            .sortedByDescending { it.day }
    }
    val today = LocalDate.now()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
        item { ScreenTitle("History"); Spacer(Modifier.height(8.dp)) }
        if (entries.isEmpty()) {
            item { Caption("Nothing here yet. Entries show up once you log.") }
        }
        groups.forEach { g ->
            stickyHeader(key = "day-" + g.day.toEpochDay()) {
                val label = when (g.day) {
                    today -> "Today"
                    today.minusDays(1) -> "Yesterday"
                    else -> g.day.format(dayFmt)
                }
                val wake = g.rows.firstNotNullOfOrNull { it.wakeMin }
                val sleep = g.rows.firstNotNullOfOrNull { it.sleep }
                // Medication is a background setting, so it describes the day rather than any one entry.
                val med = g.rows.asReversed()
                    .mapNotNull { e -> e.medLabel?.let { listOfNotNull(e.medName, it).joinToString(" ") } }.distinct()
                    .takeIf { it.isNotEmpty() }?.joinToString(" → ", prefix = "Med ")
                val sweats = g.rows.firstNotNullOfOrNull { it.nightSweats }
                val alcohol = g.rows.firstNotNullOfOrNull { it.alcohol }
                val cannabis = g.rows.firstNotNullOfOrNull { it.cannabis }
                val info = listOfNotNull(wake?.let { "Woke ${fmtMin(it)}" }, sleep?.let { "Slept $it/5" }, med,
                    if (sweats == true) "Night sweats" else null, if (alcohol == true) "Alcohol" else null,
                    if (cannabis == true) "Cannabis" else null)
                    .joinToString(" · ")
                Column(Modifier.fillMaxWidth().background(cs.background)) {
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.Bottom) {
                        SectionHeader(label, Modifier.weight(1f))
                        if (info.isNotEmpty()) Text(info, style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant)
                    }
                    HorizontalDivider(color = cs.outlineVariant)
                }
            }
            items(g.rows, key = { it.ts }) { e ->
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
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                            Text(e.anxiety?.toString() ?: "–", Modifier.width(40.dp),
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Column(Modifier.weight(1f)) {
                                Text(e.dateTime.format(timeFmt), style = MaterialTheme.typography.bodyLarge)
                                val detail = detailLine(e)
                                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider(color = cs.outlineVariant)
                    }
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
    var medName by remember { mutableStateOf(Store.medName) }
    val causeList by vm.causeList.collectAsStateWithLifecycle()
    var addingCause by remember { mutableStateOf(false) }
    var removingCause by remember { mutableStateOf<String?>(null) }
    var unit by remember { mutableStateOf(Store.medUnit) }
    var unitMenu by remember { mutableStateOf(false) }
    var doseText by remember { mutableStateOf(Store.medDose.value?.let { fmtNum(it) } ?: "") }
    var usualTime by remember { mutableStateOf(Store.medUsualTime) }
    var showTime by remember { mutableStateOf(false) }
    val syncUri by Store.syncUri.collectAsStateWithLifecycle()
    val syncState by Store.syncState.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) CsvSync.setTarget(ctx, uri)
    }
    val scope = rememberCoroutineScope()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                    val list = Csv.parse(text)
                    if (list == null) "Not a valid CSV (no timestamp column)"
                    else Store.importEntries(list).let { n ->
                        val k = Store.restoreCauses(list)
                        "Imported $n entries (${list.size - n} skipped)" + if (k > 0) ", $k causes restored" else ""
                    }
                } catch (e: Exception) { "Import failed: ${e.message ?: e.javaClass.simpleName}" }
            }
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
        }
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
            "Recorded with every entry. Change the dose here or under Today on the Log screen.") {
            OutlinedTextField(value = medName, onValueChange = { medName = it.take(30); Store.medName = medName },
                label = { Text("Name (optional)") }, singleLine = true, shape = SmallShape,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = doseText, onValueChange = { v ->
                    val t = v.replace(',', '.').filter { it.isDigit() || it == '.' }
                    if (t.count { it == '.' } <= 1) { doseText = t.take(8); Store.setMedDose(doseText.toDoubleOrNull()) }
                }, label = { Text("Dose") }, singleLine = true, shape = SmallShape,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f))
                Box {
                    OutlinedButton(onClick = { unitMenu = true }, shape = SmallShape,
                        modifier = Modifier.height(56.dp).width(120.dp)) { Text("$unit ▾", maxLines = 1) }
                    DropdownMenu(unitMenu, { unitMenu = false }) {
                        MED_UNITS.forEach { u ->
                            DropdownMenuItem(text = { Text(u) }, onClick = {
                                unit = u; Store.medUnit = u; unitMenu = false
                            })
                        }
                    }
                }
            }
            LabeledRow("Usual time") {
                TextButton(onClick = { showTime = true }) { Text(usualTime?.let { fmtMin(it) } ?: "Set") }
                if (usualTime != null) TextButton(onClick = { usualTime = null; Store.medUsualTime = null }) {
                    Text("Clear")
                }
            }
        }

        SettingsSection("Causes", "The \"What's going on?\" list on the Log screen. Past entries keep their causes.") {
            causeList.forEach { c ->
                LabeledRow(c) {
                    IconButton(onClick = { removingCause = c }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove $c")
                    }
                }
            }
            TextButton(onClick = { addingCause = true }) { Text("Add cause…") }
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
            Row {
                TextButton(onClick = onExport) { Text("Export CSV") }
                TextButton(onClick = {
                    importer.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream", "*/*"))
                }) { Text("Import CSV…") }
            }
        }

        val info = remember { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }
        val built = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(info.lastUpdateTime) }
        Caption("Version ${info.versionName} · installed $built", Modifier.padding(top = 8.dp))
        UpdateCheckRow(ctx, PackageInfoCompat.getLongVersionCode(info))
        Caption("Updates are only checked when you tap the button; the app makes no other network calls.",
            Modifier.padding(bottom = 48.dp))
    }

    if (addingCause) {
        var text by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { addingCause = false },
            title = { Text("New cause") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it.take(30) }, singleLine = true,
                shape = SmallShape) },
            confirmButton = { TextButton(onClick = { vm.addCause(text); addingCause = false }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { addingCause = false }) { Text("Cancel") } })
    }
    removingCause?.let { c ->
        AlertDialog(onDismissRequest = { removingCause = null },
            title = { Text("Remove \"$c\" from the list?") },
            confirmButton = { TextButton(onClick = { vm.removeCause(c); removingCause = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removingCause = null }) { Text("Keep") } })
    }

    if (showTime) TimeDialog(usualTime ?: 480, { showTime = false }) {
        usualTime = it; Store.medUsualTime = it; showTime = false
    }
}

private const val UPDATE_JSON_URL = "https://github.com/dreamins/bcalm/releases/download/latest/version.json"

private sealed interface UpdateState {
    object Idle : UpdateState
    object Checking : UpdateState
    object UpToDate : UpdateState
    class Available(val versionName: String, val apk: String) : UpdateState
    class Failed(val reason: String) : UpdateState
}

/** Fetches [url] as text, following up to 5 redirects manually (GitHub redirects across hosts). */
private fun httpGetText(url: String): String {
    var current = url
    repeat(6) {
        val conn = URL(current).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val code = conn.responseCode
            if (code in intArrayOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location") ?: throw java.io.IOException("redirect without location")
                current = URL(URL(current), loc).toString()
            } else if (code == 200) {
                return conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                throw java.io.IOException("HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }
    throw java.io.IOException("too many redirects")
}

private fun checkForUpdate(installed: Long): UpdateState = try {
    val json = JSONObject(httpGetText(UPDATE_JSON_URL))
    val code = json.getLong("versionCode")
    if (code > installed) UpdateState.Available(json.optString("versionName", code.toString()), json.getString("apk"))
    else UpdateState.UpToDate
} catch (e: Exception) {
    UpdateState.Failed(e.message?.take(60) ?: e.javaClass.simpleName)
}

@Composable
private fun UpdateCheckRow(ctx: android.content.Context, installed: Long) {
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = {
            if (state !is UpdateState.Checking) {
                state = UpdateState.Checking
                scope.launch { state = withContext(Dispatchers.IO) { checkForUpdate(installed) } }
            }
        }) { Text("Check for updates") }
        when (val st = state) {
            UpdateState.Idle -> {}
            UpdateState.Checking -> Caption("Checking…")
            UpdateState.UpToDate -> Caption("You're up to date")
            is UpdateState.Available -> {
                Caption("Update available: ${st.versionName}")
                TextButton(onClick = {
                    try {
                        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(st.apk)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                        state = UpdateState.Failed("no app to open the link")
                    }
                }) { Text("Download") }
            }
            is UpdateState.Failed -> Text("Couldn't check: ${st.reason}",
                style = MaterialTheme.typography.bodySmall, color = cs.error)
        }
    }
}
