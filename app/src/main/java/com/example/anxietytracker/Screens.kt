@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.example.anxietytracker

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.Intent
import android.os.Build
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
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

private val SmallShape = RoundedCornerShape(10.dp)
private val CardShape = RoundedCornerShape(16.dp)
private val CellShape = RoundedCornerShape(10.dp)

// ---------------- App shell ----------------

@Composable
fun AppScreen(vm: MainViewModel, onExport: () -> Unit, initialTab: Int = 0, promptId: String? = null, onPromptDone: () -> Unit = {}) {
    var tab by remember { mutableIntStateOf(initialTab.coerceIn(0, 3)) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var promptOpen by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(promptId) {
        val id = promptId ?: return@LaunchedEffect
        val p = Store.prompts.value.firstOrNull { it.id == id }
        if (p != null && p.status == PROMPT_PENDING && System.currentTimeMillis() - p.scheduledTs < Prompts.VALID_MS) promptOpen = id
        else scope.launch { snack.showSnackbar("That check-in has expired") }
        onPromptDone()
    }
    promptOpen?.let { id ->
        PromptSheet(vm, onClose = { promptOpen = null }, title = if (id.startsWith("f")) "How is it now?" else "How's your anxiety right now?") { n, details ->
            val e = vm.answerPrompt(id, n)
            if (e != null) {
                if (details) tab = 0
                scope.launch { snack.currentSnackbarData?.dismiss(); snack.showSnackbar("Saved") }
            }
            // After a drop from a logged spike the sheet stays open for a second step: what helped?
            if (e != null && !details && vm.spikeBefore(e) != null) HelpInfo(e.ts, vm.calmingCauses()) else null
        }
    }
    val t = MaterialTheme.bcalm
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            Column {
                HorizontalDivider(color = t.cardBorder)
                NavigationBar(containerColor = t.card, tonalElevation = 0.dp) {
                    listOf("Log", "Chart", "History", "Settings").forEachIndexed { i, label ->
                        NavigationBarItem(
                            selected = tab == i, onClick = { tab = i },
                            icon = { TabIcon(i) },
                            label = {
                                Text(label, fontSize = 12.sp,
                                    fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Medium)
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = t.accent, selectedTextColor = t.accent,
                                indicatorColor = t.accentTint,
                                unselectedIconColor = t.secondary,
                                unselectedTextColor = t.secondary))
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
    Text(t.uppercase(), modifier,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Caption(t: String, modifier: Modifier = Modifier) =
    Text(t, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** A white rounded card with a hairline border; the unit every screen section is built from. */
@Composable
private fun SectionCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    spacing: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = MaterialTheme.bcalm
    Column(
        modifier.fillMaxWidth().clip(CardShape).background(t.card)
            .border(1.dp, t.cardBorder, CardShape).padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing), content = content)
}

/** Card title on the left, optional small secondary note on the right. */
@Composable
private fun CardHeader(title: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(title, Modifier.alignByBaseline(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            Text(trailing, Modifier.alignByBaseline().padding(start = 12.dp), fontSize = 12.sp,
                color = MaterialTheme.bcalm.secondary, textAlign = TextAlign.End)
        }
    }
}

/**
 * The one control for choosing among a few options: a hairline-outlined segmented row.
 * Selected = solid accent with on-accent text. Tapping the selected option clears it when [allowClear].
 */
@Composable
fun Segmented(
    options: List<String>, selected: Int?, modifier: Modifier = Modifier,
    height: Dp = 36.dp, allowClear: Boolean = true,
    textSize: Int = 14, onPick: (Int?) -> Unit,
) {
    val t = MaterialTheme.bcalm
    Row(modifier.height(height).clip(SmallShape).border(1.dp, t.cardBorder, SmallShape).selectableGroup()) {
        options.forEachIndexed { i, label ->
            val sel = selected == i
            if (i > 0) {
                val joined = sel || selected == i - 1
                Box(Modifier.fillMaxHeight().width(1.dp).background(if (joined) t.accent else t.cardBorder))
            }
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .background(if (sel) t.accent else Color.Transparent)
                    .selectable(selected = sel, role = Role.RadioButton,
                        onClick = { onPick(if (sel && allowClear) null else i) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, maxLines = 1, fontSize = textSize.sp,
                    fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                    color = if (sel) t.onAccent else t.ink)
            }
        }
    }
}

/** A row of separate bordered number cells (1..[count]); selected = accent fill. */
@Composable
private fun ScaleRow(
    count: Int, selected: Int?, height: Dp, gap: Dp,
    allowClear: Boolean = false, onPick: (Int?) -> Unit,
) {
    val t = MaterialTheme.bcalm
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(gap)) {
        for (i in 0 until count) {
            val sel = selected == i
            Box(
                Modifier.weight(1f).height(height).clip(CellShape)
                    .background(if (sel) t.accent else t.card)
                    .border(1.dp, if (sel) t.accent else t.cardBorder, CellShape)
                    .selectable(selected = sel, role = Role.RadioButton,
                        onClick = { onPick(if (sel && allowClear) null else i) }),
                contentAlignment = Alignment.Center,
            ) {
                Text("${i + 1}", maxLines = 1, fontSize = if (i >= 9) 14.sp else 15.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (sel) t.onAccent else t.ink)
            }
        }
    }
}

/** Label on the left, control on the right. */
@Composable
private fun LabeledRow(label: String, control: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 15.sp)
        control()
    }
}

/** [LabeledRow] with a small outline icon on the left and an optional hairline underneath. */
@Composable
private fun ContextRow(icon: ImageVector, label: String, divider: Boolean, control: @Composable RowScope.() -> Unit) {
    val t = MaterialTheme.bcalm
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = t.secondary)
            Spacer(Modifier.width(12.dp))
            Text(label, Modifier.weight(1f), fontSize = 15.sp)
            control()
        }
        if (divider) HorizontalDivider(color = t.innerDivider)
    }
}

// Simple 20dp outline icons, 1.5 stroke (core icon set has none of these).
private fun outlineIcon(name: String, build: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 20.dp, 20.dp, 20f, 20f).path(
        fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.5f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = build).build()

private val CupIcon: ImageVector by lazy {
    outlineIcon("Cup") {
        moveTo(4f, 7f); lineTo(14f, 7f); lineTo(14f, 12f)
        arcTo(4f, 4f, 0f, false, true, 10f, 16f)
        lineTo(8f, 16f)
        arcTo(4f, 4f, 0f, false, true, 4f, 12f)
        close()
        moveTo(14f, 8.5f); lineTo(15.5f, 8.5f)
        arcTo(2f, 2f, 0f, false, true, 15.5f, 12.5f)
        lineTo(14f, 12.5f)
    }
}

private val ForkIcon: ImageVector by lazy {
    outlineIcon("Fork") {
        moveTo(6f, 3f); lineTo(6f, 8f)
        arcTo(2f, 2f, 0f, false, false, 10f, 8f)
        lineTo(10f, 3f)
        moveTo(8f, 8f); lineTo(8f, 17f)
        moveTo(14f, 3f)
        curveTo(12.5f, 4f, 12f, 6f, 12f, 8f)
        curveTo(12f, 10f, 13f, 11f, 14f, 11f)
        lineTo(14f, 17f)
    }
}

private val PeopleIcon: ImageVector by lazy {
    outlineIcon("People") {
        moveTo(5f, 7f)
        arcTo(2.5f, 2.5f, 0f, true, true, 10f, 7f)
        arcTo(2.5f, 2.5f, 0f, true, true, 5f, 7f)
        moveTo(11f, 7.5f)
        arcTo(2f, 2f, 0f, true, true, 15f, 7.5f)
        arcTo(2f, 2f, 0f, true, true, 11f, 7.5f)
        moveTo(3f, 16f)
        curveTo(3.5f, 13.5f, 5.3f, 12f, 7.5f, 12f)
        curveTo(9.7f, 12f, 11.5f, 13.5f, 12f, 16f)
        moveTo(12.5f, 12.2f)
        curveTo(14.4f, 12.3f, 15.9f, 13.6f, 16.4f, 16f)
    }
}

private fun boolToIdx(b: Boolean?): Int? = b?.let { if (it) 0 else 1 }
private fun idxToBool(i: Int?): Boolean? = i?.let { it == 0 }
private val YES_NO = listOf("Yes", "No")

/** Second step of the check-in sheet: the saved entry [ts] and the calming-cause [options] to offer. */
private class HelpInfo(val ts: Long, val options: List<String>)

/**
 * One-tap check-in sheet: tapping a number saves it; "Add details" first makes the save open the Log tab on that entry.
 * [onPick] returns a [HelpInfo] to keep the sheet open for "What helped?", or null to close it.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PromptSheet(vm: MainViewModel, onClose: () -> Unit, title: String = "How's your anxiety right now?", onPick: (Int, Boolean) -> HelpInfo?) {
    val t = MaterialTheme.bcalm
    val haptic = LocalHapticFeedback.current
    var details by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf<HelpInfo?>(null) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = t.card) {
        val h = help
        if (h == null) Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            ScaleRow(10, null, height = 46.dp, gap = 4.dp, allowClear = false) { i ->
                if (i != null) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val r = onPick(i + 1, details)
                    if (r != null) help = r else onClose()
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Caption("Calm"); Caption("Overwhelmed")
            }
            TextButton(onClick = { details = !details }) {
                Text(if (details) "Add details after saving \u2713" else "Add details")
            }
        } else {
            var opts by remember { mutableStateOf(h.options) }
            var sel by remember { mutableStateOf(emptySet<String>()) }
            var joy by remember { mutableStateOf<Int?>(null) }
            var adding by remember { mutableStateOf(false) }
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Glad it's easing. What helped?", style = MaterialTheme.typography.titleLarge)
                opts.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { c ->
                            CauseCell(c, c in sel, Modifier.weight(1f), onLongClick = {}) { sel = if (c in sel) sel - c else sel + c }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                TextButton(onClick = { adding = true }) { Text("+ Add", fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                Text("How much did it lift you?", fontSize = 13.sp, color = t.secondary)
                ScaleRow(5, joy?.minus(1), height = 42.dp, gap = 6.dp, allowClear = true) { i -> joy = i?.plus(1) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text("Skip") }
                    TextButton(onClick = {
                        if (sel.isNotEmpty() || joy != null) vm.saveHelped(h.ts, opts.filter { it in sel }, joy)
                        onClose()
                    }) { Text("Done", fontWeight = FontWeight.SemiBold) }
                }
            }
            if (adding) {
                var text by remember { mutableStateOf("") }
                AlertDialog(onDismissRequest = { adding = false },
                    title = { Text("New cause") },
                    text = { OutlinedTextField(value = text, onValueChange = { text = it.take(30) }, singleLine = true, shape = SmallShape) },
                    confirmButton = { TextButton(onClick = {
                        val n = vm.addCauseOnly(text)
                        if (n.isNotEmpty()) { if (opts.none { it.equals(n, true) }) opts = opts + n; sel = sel + n }
                        adding = false
                    }) { Text("Add") } },
                    dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } })
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
    val activeEntry by vm.activeEntry.collectAsStateWithLifecycle()
    val todayOpen by vm.todayOpen.collectAsStateWithLifecycle()
    val recalled by vm.recalledMin.collectAsStateWithLifecycle()
    var showRecall by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(30_000) } }
    val timeFmt = remember { java.time.format.DateTimeFormatter.ofPattern("HH:mm") }
    val dateFmt = remember { DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()) }
    val dateLine = java.time.Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().format(dateFmt)
    val t = MaterialTheme.bcalm

    val active = activeEntry
    val last = entries.lastOrNull()
    val cards by vm.cards.collectAsStateWithLifecycle()
    var dismissed by remember { mutableStateOf(Store.dismissedCalm()) }
    // Calm card target: the latest high log of the last 30 min not yet re-rated or dismissed (survives reopening).
    val calm = entries.lastOrNull { !it.isRecalled() && (it.anxiety ?: 0) >= 6 && it.after == null && it.ts !in dismissed && now - it.ts <= 30 * 60_000L }
    var lastCalm by remember { mutableStateOf<Entry?>(null) }
    if (calm != null && calm != lastCalm) lastCalm = calm   // kept so the card can animate out
    var sheetOpen by remember { mutableStateOf(false) }
    // Entries whose pop-up already opened by itself (seeded with the open one, so returning to this tab doesn't re-pop).
    val autoOpened = remember { mutableSetOf<Long>().apply { vm.activeEntry.value?.let { if ((it.anxiety ?: 0) >= 6) add(it.ts) } } }
    LaunchedEffect(calm?.ts, active?.ts, (active?.anxiety ?: 0) >= 6) {
        val a = active
        if (calm != null && a != null && a.ts == calm.ts && (a.anxiety ?: 0) >= 6 && autoOpened.add(calm.ts)) sheetOpen = true
    }
    var confirm by remember { mutableStateOf<Long?>(null) }
    var confirmText by remember { mutableStateOf("") }
    LaunchedEffect(confirm) { if (confirm != null) { delay(3000); confirm = null } }
    val lastAnxiety = entries.lastOrNull { it.anxiety != null }
        ?.let { "Last: ${it.anxiety} at ${it.dateTime.format(timeFmt)}" }
    val showResult: (LogResult) -> Unit = { r ->
        scope.launch {
            snack.currentSnackbarData?.dismiss()
            val res = snack.showSnackbar(r.message, actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (res == SnackbarResult.ActionPerformed) r.undo()
        }
    }
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

    if (showRecall) TimeDialog(recalled ?: java.time.Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).let { it.hour * 60 + it.minute },
        onDismiss = { showRecall = false }) { m ->
        val ts = LocalDate.now().atTime(m / 60, m % 60).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (ts < System.currentTimeMillis()) { vm.setRecalled(m); showRecall = false }
        else android.widget.Toast.makeText(ctx, "Pick a time earlier today", android.widget.Toast.LENGTH_SHORT).show()
    }

    if (sheetOpen) (calm ?: lastCalm)?.let { e ->
        CalmSheet(e, active?.ts == e.ts, sel, causes, entries, cards, vm,
            onClose = { sheetOpen = false },
            onNotNow = { Store.dismissCalm(e.ts); dismissed = dismissed + e.ts; sheetOpen = false },
            onRated = { n -> confirmText = "${e.anxiety} → $n"; confirm = e.ts })
    }

    val anyDailyUnset = d.wakeMin == null || d.sleep == null || d.exercise == null
    val todayExpanded = todayOpen ?: anyDailyUnset

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(dateLine, fontSize = 13.sp, color = t.secondary)
            Text("How are you right now?", style = MaterialTheme.typography.titleLarge)
        }

        Column {
            SectionCard {
                CardHeader("Anxiety", lastAnxiety)
                ScaleRow(10, active?.anxiety?.minus(1), height = 46.dp, gap = 4.dp, allowClear = false) { i ->
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Caption("Calm"); Caption("Overwhelmed")
                }
                if (recalled != null) {
                    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(t.accentTint)
                        .clickable(role = Role.Button) { vm.setRecalled(null) }
                        .padding(horizontal = 10.dp, vertical = 7.dp)) {
                        Text("At ${fmtMin(recalled ?: 0)} · recalled \u2715", fontSize = 12.sp, color = t.accent)
                    }
                } else {
                    TextButton(onClick = { showRecall = true }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                        Text("Earlier today\u2026", fontSize = 12.sp, color = t.secondary)
                    }
                }
                if (active != null) {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(t.accentTint)
                        .padding(horizontal = 10.dp, vertical = 7.dp)) {
                        Text(status, fontSize = 12.sp, color = t.accent)
                    }
                } else {
                    Caption(status)
                }
            }
            AnimatedVisibility(calm != null && !sheetOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Box(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(t.accentTint)
                    .clickable(role = Role.Button) { sheetOpen = true }
                    .padding(horizontal = 10.dp, vertical = 7.dp)) {
                    Text("A calmer moment ›", fontSize = 12.sp, color = t.accent)
                }
            }
            AnimatedVisibility(confirm != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Box(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(t.accentTint)
                    .padding(horizontal = 10.dp, vertical = 7.dp)) {
                    Text(confirmText, fontSize = 12.sp, color = t.accent)
                }
            }
        }

        SectionCard {
            CardHeader("Joy", "optional — after something good")
            ScaleRow(5, active?.joy?.minus(1), height = 42.dp, gap = 6.dp, allowClear = false) { i ->
                if (i != null) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.tapJoy(i + 1)?.let(showResult)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Caption("Barely"); Caption("Really landed")
            }
        }

        SectionCard(padding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)) {
            CardHeader("What's going on?", "optional")
            CausesList(causes, sel, entries, active, vm)
        }

        SectionCard(padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), spacing = 0.dp) {
            ContextRow(CupIcon, "Caffeine", divider = true) {
                Segmented(YES_NO, boolToIdx(d.caffeine), Modifier.width(112.dp)) { vm.setCaffeine(idxToBool(it)) }
            }
            ContextRow(ForkIcon, "Food in the last 3h", divider = true) {
                Segmented(YES_NO, boolToIdx(d.food), Modifier.width(112.dp)) { vm.setFood(idxToBool(it)) }
            }
            ContextRow(PeopleIcon, "Partner", divider = false) {
                Segmented(PARTNER_DISPLAY, d.partner, Modifier.width(176.dp), textSize = 13) { vm.setPartner(it) }
            }
        }

        TodayCard(d, entries, todayExpanded, onToggle = { vm.setTodayOpen(!todayExpanded) }, vm = vm)
    }
}

@Composable
private fun TodayCard(d: Draft, entries: List<Entry>, expanded: Boolean, onToggle: () -> Unit, vm: MainViewModel) {
    val t = MaterialTheme.bcalm
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
    SectionCard(padding = PaddingValues(0.dp), spacing = 0.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                SectionHeader("Today")
                Text(summary, fontSize = 15.sp)
            }
            Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = t.secondary)
        }
        if (expanded) {
            HorizontalDivider(color = t.innerDivider)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                LabeledRow("Woke at") {
                    TextButton(onClick = { showTime = true }) { Text(d.wakeMin?.let { fmtMin(it) } ?: "Set") }
                    TextButton(onClick = {
                        val now = java.time.LocalTime.now(); vm.setWake(now.hour * 60 + now.minute)
                    }) { Text("Now") }
                    if (d.wakeMin != null) TextButton(onClick = { vm.setWake(null) }) { Text("Clear") }
                }
                Text("Sleep quality", fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                ScaleRow(5, d.sleep?.minus(1), height = 42.dp, gap = 6.dp, allowClear = true) { vm.setSleep(it?.plus(1)) }
                Spacer(Modifier.height(4.dp))
                LabeledRow("Exercise") {
                    Segmented(YES_NO, boolToIdx(d.exercise), Modifier.width(112.dp)) { vm.setExercise(idxToBool(it)) }
                }
                LabeledRow("Night sweats") {
                    Segmented(YES_NO, boolToIdx(d.nightSweats), Modifier.width(112.dp)) { vm.setNightSweats(idxToBool(it)) }
                }
                LabeledRow("Alcohol") {
                    Segmented(YES_NO, boolToIdx(d.alcohol), Modifier.width(112.dp)) { vm.setAlcohol(idxToBool(it)) }
                }
                LabeledRow("Cannabis") {
                    Segmented(YES_NO, boolToIdx(d.cannabis), Modifier.width(112.dp)) { vm.setCannabis(idxToBool(it)) }
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

/** Quiet support options: dial a configured number (no call permission) and/or read a custom step. */
@Composable
private fun SupportRow() {
    val ctx = LocalContext.current
    val t = MaterialTheme.bcalm
    val contacts = listOf(Store.crisisLabel to Store.crisisNumber, Store.personLabel to Store.personNumber)
        .filter { it.second.isNotBlank() }
    val step = Store.customStep
    if (contacts.isEmpty() && step.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Caption("Need someone now?")
        if (contacts.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            contacts.forEach { (label, number) ->
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")))
                }, Modifier.weight(1f), shape = SmallShape, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(listOf(label.ifBlank { "Call" }, number).joinToString(" "), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                }
            }
            if (contacts.size == 1) Spacer(Modifier.weight(1f))
        }
        if (step.isNotBlank()) Text(step, fontSize = 14.sp, color = t.ink)
    }
}

/**
 * Pop-up after a high log: top causes as chips (they update the open entry like the Log grid), the picked
 * causes' calm cards, Breathe/Ground, and a re-rating. Any rating closes it; "Not now" dismisses it for good.
 */
@Composable
private fun CalmSheet(e: Entry, editing: Boolean, sel: Set<String>, causes: List<String>, entries: List<Entry>,
                      cards: Map<String, CauseCard>, vm: MainViewModel, onClose: () -> Unit,
                      onNotNow: () -> Unit, onRated: (Int) -> Unit) {
    val t = MaterialTheme.bcalm
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val hideThen: (() -> Unit) -> Unit = { then -> scope.launch { state.hide() }.invokeOnCompletion { then() } }
    var tool by remember { mutableStateOf<String?>(null) }
    // Same ranking as the Log screen, frozen per entry so chips never jump under the finger.
    val top = remember(e.ts) { rankCauses(entries, causes, false, System.currentTimeMillis()).take(TOP_CAUSES).map { it.name } }
    val picked = if (editing) sel else e.causes.toSet()
    val shown = top + picked.filter { it !in top && it in causes }
    val withText = e.causes.mapNotNull { c ->
        cards[c]?.takeIf { it.thought.isNotBlank() || it.todo.isNotBlank() }?.let { c to it }
    }
    val cardShown = withText.isNotEmpty()
    val support = (e.anxiety ?: 0) >= 8 || e.causes.any { cards[it]?.heavy == true }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = state, containerColor = t.card) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { CardHeader("A calmer moment") }
                TextButton(onClick = { hideThen(onNotNow) }) { Text("Not now", color = t.secondary) }
            }
            if (support) SupportRow()
            if (editing || e.causes.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (editing) {
                        Text("What's it about?", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            shown.forEach { c ->
                                FilterChip(selected = c in picked, onClick = { vm.toggleCause(c) },
                                    label = { Text(c, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = t.accentTint, selectedLabelColor = t.accent))
                            }
                        }
                    }
                    val typingKey = withText.firstOrNull { "${e.ts}|${it.first}" !in typedCards }?.let { "${e.ts}|${it.first}" }
                    Column(Modifier.animateContentSize().then(
                        if (typingKey == null) Modifier else Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            withText.forEach { typedCards.markTyped("${e.ts}|${it.first}") }   // tap = show everything now
                        }), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        withText.forEach { (c, card) ->
                            key(c) {
                                AnimatedVisibility(remember { MutableTransitionState(false).apply { targetState = true } },
                                    enter = expandVertically() + fadeIn()) {
                                    val k = "${e.ts}|$c"
                                    val finished = k in typedCards
                                    val active = k == typingKey
                                    var part by remember(k) { mutableIntStateOf(0) }
                                    val hasTodo = card.todo.isNotBlank()
                                    val hasThought = card.thought.isNotBlank()
                                    // A card with only a step starts straight on the step.
                                    val onThought = hasThought && part == 0
                                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(c, fontSize = 12.sp, color = t.secondary)
                                        if (hasThought) TypewriterText(card.thought, play = active && onThought,
                                            instant = finished || part > 0,
                                            onDone = { if (hasTodo) part = 1 else typedCards.markTyped(k) },
                                            fontSize = 17.sp, lineHeight = 24.sp, color = t.ink)
                                        if (hasTodo) TypewriterText("Do: ${card.todo}", play = active && !onThought,
                                            instant = finished, onDone = { typedCards.markTyped(k) },
                                            fontSize = 14.sp, color = t.secondary, startDelay = if (hasThought) 400L else 0L)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { tool = "breathe" }, Modifier.weight(1f), shape = SmallShape) { Text("Breathe") }
                OutlinedButton(onClick = { tool = "ground" }, Modifier.weight(1f), shape = SmallShape) { Text("Ground") }
            }
            Text("How is it now?", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            ScaleRow(10, null, height = 40.dp, gap = 4.dp, allowClear = false) { i ->
                if (i != null) {
                    vm.setAfter(e.ts, i + 1, if (cardShown) "card" else null)
                    onRated(i + 1)
                    hideThen(onClose)
                }
            }
        }
    }
    tool?.let { which ->
        val finish: (Int?) -> Unit = { n ->
            if (n != null) {
                vm.setAfter(e.ts, n, which)
                if (cardShown) vm.setAfter(e.ts, n, "card")
                onRated(n)
            }
            tool = null
            if (n != null) hideThen(onClose)
        }
        Dialog(onDismissRequest = { finish(null) }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            if (which == "breathe") BreatheScreen(onFinish = finish) else GroundScreen(onFinish = finish)
        }
    }
}

/** Calm cards (entry ts + cause) already typed out this session; they show in full from then on. */
private val typedCards = mutableStateListOf<String>()

private fun MutableList<String>.markTyped(k: String) {
    if (k in this) return
    add(k)
    if (size > 60) removeAt(0)
}

/**
 * Types [text] out character by character while [play] (longer pause after punctuation), with a soft blinking
 * caret at the end; [instant] shows it all. The untyped rest stays in the layout, transparent, so nothing reflows.
 */
@Composable
private fun TypewriterText(text: String, play: Boolean, instant: Boolean, onDone: () -> Unit,
                           fontSize: TextUnit, color: Color, lineHeight: TextUnit = TextUnit.Unspecified,
                           startDelay: Long = 0L) {
    val t = MaterialTheme.bcalm
    var n by remember(text) { mutableIntStateOf(if (instant) text.length else 0) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(text, play, instant) {
        if (instant) { n = text.length; return@LaunchedEffect }
        if (!play) return@LaunchedEffect
        if (startDelay > 0) delay(startDelay)
        while (n < text.length) {
            delay(if (n > 0 && text[n - 1] in ".,;?!") 250L else 28L)
            n++
        }
        done()
    }
    val caret = play && !instant && n < text.length
    val alpha = if (caret) {
        val a by rememberInfiniteTransition(label = "caret").animateFloat(0.1f, 0.55f,
            infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "caretAlpha")
        a
    } else 0f
    val shown = buildAnnotatedString {
        append(text.substring(0, n))
        withStyle(SpanStyle(color = Color.Transparent)) { append(text.substring(n)) }
    }
    Text(shown, color = color, fontSize = fontSize, lineHeight = lineHeight,
        onTextLayout = { layout = it },
        modifier = Modifier.drawBehind {
            val l = layout
            if (alpha > 0f && l != null) {
                val r = l.getCursorRect(n.coerceIn(0, text.length))
                drawRect(t.accent.copy(alpha = alpha), Offset(r.left, r.top), Size(1.5.dp.toPx(), r.height))
            }
        })
}

private const val TOP_CAUSES = 6

/** One cause in the two-column grid: bordered 44dp row; selected = accent border, tint fill, check. */
@Composable
private fun CauseCell(label: String, selected: Boolean, modifier: Modifier,
                      onLongClick: () -> Unit, onClick: () -> Unit) {
    val t = MaterialTheme.bcalm
    Row(
        modifier.height(44.dp).clip(CellShape)
            .background(if (selected) t.accentTint else t.card)
            .border(1.dp, if (selected) t.accent else t.cardBorder, CellShape)
            .combinedClickable(role = Role.Checkbox, onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) t.accent else t.ink)
        if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected",
            modifier = Modifier.size(16.dp), tint = t.accent)
    }
}

@Composable
fun CausesList(causes: List<String>, sel: Set<String>, entries: List<Entry>, active: Entry?, vm: MainViewModel) {
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    var showAll by remember { mutableStateOf(false) }
    // Ranking snapshot: computed on first show, refreshed only once the edit window has closed
    // (and the sheet is not open), so rows never jump under the user's finger.
    var ranking by remember {
        mutableStateOf(rankCauses(entries, causes, active?.joy != null && active.anxiety == null, System.currentTimeMillis()))
    }
    val windowClosed = active == null
    LaunchedEffect(windowClosed, showAll) {
        if (windowClosed && !showAll) {
            ranking = rankCauses(entries, causes, false, System.currentTimeMillis())
        }
    }
    // Removed causes drop out of the snapshot; newly added ones join at the end.
    val ranked = remember(ranking, causes) {
        val known = causes.toHashSet()
        val inSnap = ranking.mapTo(HashSet()) { it.name }
        ranking.filter { it.name in known } + causes.filter { it !in inSnap }.map { RankedCause(it, 0) }
    }
    val top = ranked.take(TOP_CAUSES).map { it.name }
    val shown = top + ranked.map { it.name }.filter { it in sel && it !in top }
    val hidden = ranked.size - shown.size
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { c ->
                    CauseCell(c, c in sel, Modifier.weight(1f), onLongClick = { removing = c }) { vm.toggleCause(c) }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            if (hidden > 0) TextButton(onClick = { showAll = true }) {
                Text("More causes… ($hidden)", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            } else Spacer(Modifier)
            TextButton(onClick = { adding = true }) {
                Text("Add cause…", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
    // Sheet lists causes hidden from the grid when it opened (stable while toggling), A-Z.
    val allSnap = remember(showAll) { ranked.map { it.name }.toSet() }
    val hiddenSnap = remember(showAll) { allSnap - shown.toSet() }
    if (showAll) {
        val t = MaterialTheme.bcalm
        var query by remember { mutableStateOf("") }
        val uses = ranked.associate { it.name to it.uses }
        val base = causes.filter { it in hiddenSnap || it !in allSnap }.sortedBy { it.lowercase() }
        val showSearch = base.size > 10
        val q = query.trim()
        val rows = if (showSearch && q.isNotEmpty()) base.filter { it.contains(q, ignoreCase = true) } else base
        val canAdd = showSearch && q.isNotEmpty() && causes.none { it.equals(q, ignoreCase = true) }
        val listState = rememberLazyListState()
        val focus = remember { FocusRequester() }
        val kb = LocalSoftwareKeyboardController.current
        val fm = LocalFocusManager.current
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress }.filter { it }.collect { kb?.hide(); fm.clearFocus() }
        }
        ModalBottomSheet(onDismissRequest = { showAll = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().imePadding()) {
                if (showSearch) OutlinedTextField(value = query, onValueChange = { query = it.take(30) },
                    singleLine = true, shape = SmallShape, placeholder = { Text("Search causes") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear")
                    } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus))
                if (showSearch) LaunchedEffect(Unit) {
                    withFrameNanos { }
                    runCatching { focus.requestFocus() }
                    kb?.show()
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).padding(horizontal = 16.dp), state = listState) {
                    items(rows, key = { it }) { name ->
                        val selected = name in sel
                        val n = uses[name] ?: 0
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
                            .clickable(role = Role.Checkbox) { vm.toggleCause(name) }
                            .padding(horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) t.accent else t.ink)
                            if (n > 0) Text("$n×", style = MaterialTheme.typography.bodySmall,
                                color = t.secondary, modifier = Modifier.padding(start = 8.dp))
                            Box(Modifier.width(26.dp), contentAlignment = Alignment.CenterEnd) {
                                if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected",
                                    modifier = Modifier.size(18.dp), tint = t.accent)
                            }
                        }
                        HorizontalDivider(color = t.innerDivider)
                    }
                    item {
                        if (canAdd) TextButton(onClick = { vm.addCause(q); query = "" }) { Text("Add \"$q\"") }
                        else if (!showSearch || q.isEmpty()) TextButton(onClick = { adding = true }) { Text("Add cause…") }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
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

/** Small segmented track for the 7d / 30d / All range; the selected thumb is a card with 1dp elevation. */
@Composable
private fun RangeTrack(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val t = MaterialTheme.bcalm
    val outer = RoundedCornerShape(10.dp)
    val inner = RoundedCornerShape(8.dp)
    Row(Modifier.clip(outer).background(t.segmentedTrack).padding(3.dp).selectableGroup()) {
        labels.forEachIndexed { i, l ->
            val sel = i == selected
            Box(
                Modifier.height(30.dp)
                    .then(if (sel) Modifier.shadow(1.dp, inner) else Modifier)
                    .clip(inner)
                    .background(if (sel) t.card else Color.Transparent)
                    .selectable(selected = sel, role = Role.RadioButton, onClick = { onPick(i) })
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(l, fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (sel) t.ink else t.secondary)
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier, sub: String? = null) {
    val t = MaterialTheme.bcalm
    Column(modifier.clip(CellShape).background(t.background).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 12.sp, color = t.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (sub != null) Text(sub, fontSize = 10.sp, color = t.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ChartScreen(vm: MainViewModel) {
    val all by vm.entries.collectAsStateWithLifecycle()
    var range by remember { mutableIntStateOf(30) }  // 7, 30, 0 = all
    val prompts by Store.prompts.collectAsStateWithLifecycle()
    val cutoff = remember(range) {
        if (range == 0) 0L else
            LocalDate.now().minusDays(range - 1L).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    val insPair = remember(all, range) { computeInsightsFor(all.filter { it.ts >= cutoff }) }
    val ins = insPair.first
    val promptedBased = insPair.second
    val data = remember(all, range) {
        all.filter { it.anxiety != null && it.ts >= cutoff && (!promptedBased || it.isPrompted()) }
    }
    val checkins = remember(all, prompts, range) { computeCheckins(all, prompts, cutoff) }
    val t = MaterialTheme.bcalm
    val ranges = listOf(7, 30, 0)
    var view by remember { mutableIntStateOf(Store.chartView) }
    var modeled by remember { mutableStateOf(Store.chartModeled) }
    val curve = remember(all, prompts, range) { buildCurve(all.filter { it.ts >= cutoff }, prompts.filter { it.scheduledTs >= cutoff }) }
    val recovery = remember(all, range) { computeRecovery(all.filter { it.ts >= cutoff }, curve) }
    val rangeRated = remember(all, range) { all.filter { it.anxiety != null && it.ts >= cutoff } }
    val showModeled = modeled
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle("Chart")
            RangeTrack(listOf("7d", "30d", "All"), ranges.indexOf(range)) { range = ranges[it] }
        }
        Segmented(listOf("Time of day", "Since waking", "Over time"), view, Modifier.fillMaxWidth(),
            allowClear = false) {
            if (it != null) { view = it; Store.chartView = it }
        }
        Segmented(listOf("Modeled", "Logged"), if (modeled) 0 else 1, Modifier.fillMaxWidth(),
            height = 32.dp, allowClear = false, textSize = 13) {
            if (it != null) { modeled = it == 0; Store.chartModeled = modeled }
        }

        SectionCard(padding = PaddingValues(horizontal = 16.dp, vertical = 18.dp), spacing = 14.dp) {
            val cw = if (view == 1) curve.wakeHardest else curve.hardest
            val cLabel = cw?.let { if (view == 1) "${it.start}–${it.start + 2} h after you wake up" else windowLabel(it.start) }
            val sentenceText = if (showModeled) {
                if (cLabel != null) "Your anxiety tends to run highest $cLabel."
                else if (view == 1) "No time since waking stands out in the modeled curve yet."
                else "No time of day stands out in the modeled curve yet."
            } else if (view == 1) ins.wakeSentence else ins.sentence
            val hard = if (view == 1) ins.wakeHard else ins.hard
            val hlLabel = if (showModeled) cLabel else hard?.label
            val cut = if (hlLabel != null) sentenceText.indexOf(hlLabel) else -1
            val sentence = if (hlLabel != null && cut >= 0) buildAnnotatedString {
                append(sentenceText.substring(0, cut))
                withStyle(SpanStyle(color = t.accent)) { append(hlLabel) }
                append(".")
            } else AnnotatedString(sentenceText)
            Text(sentence, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
            if (showModeled) {
                if (curve.days.isNotEmpty() && view == 2) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    curve.timeWeightedMean?.let { StatTile("Average", "%.1f".format(it), Modifier.weight(1f), "time-weighted") }
                    StatTile("Bad days", "${curve.hoursAbove5.values.count { it >= 1.0 }} of ${curve.days.size}", Modifier.weight(1f), "\u2265 1 h above 5")
                    curve.dailyBand.maxByOrNull { it.value.second }?.let { (day, b) ->
                        StatTile("Worst day", day.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())), Modifier.weight(1f), "%.1f peak".format(b.second))
                    }
                } else if (curve.days.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    curve.timeWeightedMean?.let { StatTile("Average", "%.1f".format(it), Modifier.weight(1f), "time-weighted") }
                    if (view != 2 && cw != null && cLabel != null) StatTile(cLabel, "%.1f".format(cw.mean), Modifier.weight(1f))
                    StatTile("Hours > 5 / day", "%.1f".format(curve.hoursAbove5.values.average()), Modifier.weight(1f))
                }
            } else if (ins.count > 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("Avg of logs", "%.1f".format(if (view == 1) ins.wakeAvg else ins.avg), Modifier.weight(1f))
                    if (hard != null) StatTile(hard.label, "%.1f".format(hard.avg), Modifier.weight(1f))
                    StatTile("Entries", "${if (view == 1) ins.wakeCount else ins.count}", Modifier.weight(1f))
                }
            }
            SectionHeader(when (view) { 0 -> "Anxiety by hour of day"; 1 -> "Anxiety since waking"; else -> "Anxiety over time" })
            if (showModeled && view == 0) {
                CurveChart(rangeRated, curve.hourProfile, Modifier.fillMaxWidth().height(220.dp),
                    highlight = curve.hardest?.let { it.start until it.start + 3 })
            } else if (showModeled && view == 1) {
                CurveChart(rangeRated.filter { it.hoursSinceWake != null }, curve.wakeProfile,
                    Modifier.fillMaxWidth().height(220.dp), { it.hoursSinceWake }, 0, 20, 4,
                    curve.wakeHardest?.let { it.start until it.start + 2 }) { "${it}h" }
            } else if (view == 0) {
                HourOfDayChart(data, Modifier.fillMaxWidth().height(220.dp),
                    highlight = ins.hard?.let { it.start until it.start + it.width })
                Caption("Hour of day, in local time where logged. Bars are the average of logged moments in each hour; red and green lines are the 90th and 10th percentile; dots are single entries.")
            } else if (view == 1) {
                val wakeData = remember(data) { data.filter { it.hoursSinceWake != null } }
                HourOfDayChart(wakeData, Modifier.fillMaxWidth().height(220.dp), { it.hoursSinceWake }, 0, 20, 4,
                    ins.wakeHard?.let { it.start until it.start + it.width }) { "${it}h" }
                Caption("Hours since waking, for the ${ins.wakeCount} entries with a wake time. Bars are the average of logged moments in each hour; red and green lines are the 90th and 10th percentile; dots are single entries.")
            } else if (showModeled) {
                TimeSeriesChart(data, range, Modifier.fillMaxWidth().height(220.dp), curve.dailyBand)
                Caption("Date. Bold line is each day's worst stretch (the level of its worst 10%); thin line is the day's time-weighted average; dots are your ratings.")
            } else {
                TimeSeriesChart(data, range, Modifier.fillMaxWidth().height(220.dp))
                Caption("Date. Line is the average of logged moments; red and green lines are the daily 90th and 10th percentile; dots are single entries.")
            }
            if (view == 2) {
                SectionHeader("Hours above 5 per day")
                HoursAboveChart(curve.hoursAbove5, data, range, Modifier.fillMaxWidth().height(80.dp))
                Caption("Modeled hours spent above 5 each day.")
            }
            if (showModeled) {
                Caption("Baseline %.1f. ".format(curve.baseline.at(720.0)) + "Modeled: between ratings, anxiety fades back toward your baseline (half-life %.1f h, %s). Band shows how much days differ; dots are your ratings.".format(
                    curve.halfLife, if (curve.halfLifeFits > 0) "measured from ${curve.halfLifeFits} fades" else "estimated") +
                    if (curve.baselineFromCheckins) ""
                    else " Baseline estimated from your logs — likely too high until you have a few check-ins.")
            } else if (promptedBased) Caption("Based on check-ins (your typical day).")
            else if (Store.promptsOn || all.any { it.ts >= cutoff && it.isPrompted() })
                Caption("Based mostly on entries you logged yourself — these show spikes more than your baseline.")
        }

        CheckinsCard(checkins, promptedBased)
        RecoveryCard(recovery, curve.halfLife, curve.halfLifeFits, checkins.followAnswered, checkins.followTotal)

        val cp = curve.patterns
        val pCauses = if (showModeled) cp.causes.map { Triple(it.label, "%.1f h · %s".format(it.hours, if (it.days == 1) "1 day" else "${it.days} days"), it.diff) }
            else ins.causes.map { Triple(it.label, if (it.days == 1) "1 day" else "${it.days} days", it.diff) }
        val pFactors = if (showModeled) cp.factors else ins.factors
        val pOther = if (showModeled) cp.otherCauses else ins.otherCauses
        if (pCauses.isNotEmpty() || pFactors.isNotEmpty() || pOther > 0) {
            SectionCard(spacing = 0.dp) {
                CardHeader("Patterns")
                Spacer(Modifier.height(6.dp))
                val rows: List<Triple<String, String?, Double>> = pCauses.map { Triple(it.first, it.second as String?, it.third) } +
                    pFactors.map { Triple(it.label, null as String?, it.diff) }
                rows.forEachIndexed { i, r -> PatternRow(r.first, r.second, r.third, divider = i < rows.lastIndex) }
                if (pOther > 0) {
                    Caption(if (pOther == 1) "1 other cause on too few days"
                            else "$pOther other causes on too few days",
                        Modifier.padding(top = 8.dp))
                }
                Caption(if (showModeled) "From the modeled curve: time attributed to each cause compared with the rest of that day, each day counted once. Factors: time with vs without."
                        else "Causes: average anxiety compared with the rest of the same day, each day counted once. Factors: compared with the opposite case.",
                    Modifier.padding(top = 6.dp))
            }
        }

        val joy = remember(all, range) {
            val cutoff = if (range == 0) 0L else
                LocalDate.now().minusDays(range - 1L).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            computeJoyInsights(all.filter { it.ts >= cutoff })
        }
        if (joy != null) {
            SectionCard(spacing = 0.dp) {
                CardHeader("Joy")
                Spacer(Modifier.height(6.dp))
                SweatRowView("Joy logged ${joy.count} times · average ${"%.1f".format(joy.avg)}", null,
                    divider = joy.causes.isNotEmpty())
                if (joy.causes.isNotEmpty()) {
                    Text("Top joy causes", Modifier.padding(top = 10.dp, bottom = 4.dp), fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold)
                    HorizontalDivider(color = t.innerDivider)
                    joy.causes.forEachIndexed { i, c ->
                        SweatRowView(c.label, "${c.n}× · avg ${"%.1f".format(c.avg)}", divider = i < joy.causes.lastIndex)
                    }
                }
            }
            if (joy.doses.isNotEmpty()) {
                SectionCard {
                    CardHeader("Calm or flat?", "by medication dose")
                    DoseGrid(joy.doses)
                    Caption("Calm: anxiety down, joy holds at 3–5. Flat: anxiety down, joy drifts to 1–2. Plain averages, not a diagnosis.")
                    Caption(joy.doses.joinToString(" / ") { "${it.anxN}" } + " anxiety and " +
                        joy.doses.joinToString(" / ") { "${it.joyN}" } + " joy entries.")
                }
            }
        }

        val sweat = remember(all, range) {
            val cutoff = if (range == 0) 0L else
                LocalDate.now().minusDays(range - 1L).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            computeSweatInsights(all, cutoff)
        }
        if (sweat != null) {
            SectionCard(spacing = 0.dp) {
                CardHeader("Night sweats")
                Spacer(Modifier.height(6.dp))
                SweatRowView("Night sweats on ${sweat.sweats} of ${sweat.nights} nights", null,
                    divider = sweat.lines.isNotEmpty())
                sweat.lines.forEachIndexed { i, l -> SweatRowView(l.label, l.value, divider = i < sweat.lines.lastIndex) }
                Caption("Compared with the evening before. Counts per calendar day, using the latest answer that day. Comparisons need at least 2 nights on each side. These are plain counts, not an explanation.",
                    Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun CheckinsCard(c: Checkins, promptedBased: Boolean) {
    val t = MaterialTheme.bcalm
    SectionCard {
        CardHeader("Check-ins")
        if (!c.hasAny) {
            Caption("Turn on random check-ins in Settings to see your typical day, not just the hard moments.")
            return@SectionCard
        }
        fun f(m: MeanN) = m.mean?.let { "%.1f".format(it) } ?: "—"
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatTile("Typical day", f(c.typical), Modifier.fillMaxWidth())
                Caption("${c.typical.n} check-ins", Modifier.padding(start = 4.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatTile("Logged spikes", f(c.spikes), Modifier.fillMaxWidth())
                Caption("${c.spikes.n} logged", Modifier.padding(start = 4.dp))
            }
        }
        if (c.typical.n < 5) {
            Caption("A few more check-ins needed for a reliable typical day.")
        } else {
            Column {
                listOf("Morning", "Afternoon", "Evening").forEachIndexed { i, name ->
                    MeanRow(name, c.windows[i], divider = i < 2)
                }
            }
        }
        if (c.daily.count { it != null } >= 3) {
            val bar = t.accent
            Canvas(Modifier.fillMaxWidth().height(48.dp)) {
                val slot = size.width / c.daily.size
                val bw = slot * 0.55f
                c.daily.forEachIndexed { i, v ->
                    if (v == null) return@forEachIndexed
                    val h = (size.height * (v.toFloat() / 10f)).coerceIn(2.dp.toPx(), size.height)
                    drawRoundRect(bar, Offset(slot * i + (slot - bw) / 2f, size.height - h), Size(bw, h),
                        CornerRadius(3.dp.toPx(), 3.dp.toPx()))
                }
            }
            Caption("Typical-day average, last 7 days.")
        }
        if (c.topCauses.isNotEmpty())
            Caption("Top causes when you log: " + c.topCauses.joinToString(", ") { "${it.first} (${it.second})" })
        if (c.total > 0) {
            val pct = Math.round(100.0 * c.answered / c.total).toInt()
            val low = c.total >= 6 && pct < 70
            Text("Answered ${c.answered} of ${c.total} check-ins ($pct%)", fontSize = 14.sp,
                color = if (low) t.high else MaterialTheme.colorScheme.onSurface)
            if (low) Caption("Below 70%: the typical-day numbers may miss harder moments.")
            val sk = c.skippedNearby
            val tm = c.typical.mean
            if (sk.n >= 2 && sk.mean != null && tm != null && sk.mean - tm >= 1.0)
                Caption("Around skipped check-ins you logged %.1f on average — skipped ones may have been harder moments.".format(sk.mean))
        }
    }
}

private fun fmtHours(min: Double) = "%.1f h".format(min / 60.0)

@Composable
private fun RecoveryCard(r: Recovery, half: Double, fits: Int, answered: Int, total: Int) {
    SectionCard {
        CardHeader("Recovery")
        if (r.episodes < 3) {
            Caption("After a few hard moments with follow-ups, this shows how fast they fade.")
            return@SectionCard
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Back near baseline", r.medianMin?.let { fmtHours(it) } ?: "—", Modifier.weight(1f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatTile("Fade half-life", "%.1f h".format(half), Modifier.fillMaxWidth())
                Caption(if (fits > 0) "measured" else "estimated", Modifier.padding(start = 4.dp))
            }
            StatTile("Spikes", "${r.episodes}", Modifier.weight(1f))
        }
        if (r.censored > 0) Caption("${r.censored} of ${r.episodes} spikes were not rated back near baseline within 6 h.")
        RecoveryChart(r, half, Modifier.fillMaxWidth().height(140.dp))
        Caption("Line: modeled fade from a typical spike. Dots: how you actually rated after each spike.")
        if (total > 0) Caption("Answered $answered of $total follow-ups.")
        if (r.weekly.size >= 2) {
            val f = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
            Caption("Fade half-life by week: " + r.weekly.joinToString(" · ") { "${it.first.format(f)} ${"%.1f".format(it.second)} h" })
        }
        if (r.withTool != null && r.without != null)
            Caption("With a coping tool: back near baseline in ${fmtHours(r.withTool)}; without: ${fmtHours(r.without)}.")
    }
}

/** x = hours since the spike (0..6), y = 1..10: modeled fade from the typical spike to the typical baseline, plus every rating after a spike. */
@Composable
private fun RecoveryChart(r: Recovery, half: Double, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val t = MaterialTheme.bcalm
    val grid = t.innerDivider
    val dot = t.secondary.copy(alpha = 0.35f)
    val dotF = t.secondary.copy(alpha = 0.75f)
    val accent = t.accent
    Canvas(modifier) {
        val l = 28.dp.toPx(); val b = 24.dp.toPx(); val top0 = 8.dp.toPx(); val rr = 8.dp.toPx()
        val w = size.width - l - rr; val h = size.height - top0 - b
        fun x(hr: Float) = l + w * hr / 6f
        fun y(a: Float) = top0 + h * (1 - a / 10f)
        val style = TextStyle(color = t.secondary, fontSize = 10.sp)
        for (a in listOf(1, 5, 10)) {
            if (a > 1) drawLine(grid, Offset(l, y(a.toFloat())), Offset(l + w, y(a.toFloat())), 1.dp.toPx())
            val m = measurer.measure("$a", style)
            drawText(m, topLeft = Offset(l - m.size.width - 6.dp.toPx(), y(a.toFloat()) - m.size.height / 2f))
        }
        for (hr in 0..6) {
            val m = measurer.measure("${hr}h", style)
            drawText(m, topLeft = Offset((x(hr.toFloat()) - m.size.width / 2f).coerceIn(l - 4.dp.toPx(), size.width - m.size.width.toFloat()), top0 + h + 6.dp.toPx()))
        }
        val line = Path()
        for (i in 0..60) {
            val hr = i / 10f
            val v = (r.baseMedian + (r.spikeMedian - r.baseMedian) * Math.pow(2.0, -hr / half)).toFloat().coerceIn(1f, 10f)
            if (i == 0) line.moveTo(x(hr), y(v)) else line.lineTo(x(hr), y(v))
        }
        drawPath(line, accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        r.obs.forEach { o -> drawCircle(if (o.followup) dotF else dot, 2.dp.toPx(), Offset(x(o.hours.coerceIn(0f, 6f)), y(o.value.coerceIn(1, 10).toFloat()))) }
        drawLine(t.cardBorder, Offset(l, y(0f)), Offset(l + w, y(0f)), 1.dp.toPx())
    }
}

@Composable
private fun MeanRow(label: String, m: MeanN, divider: Boolean) {
    val t = MaterialTheme.bcalm
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 15.sp)
        Text("n ${m.n}", Modifier.padding(end = 10.dp), fontSize = 12.sp, color = t.secondary)
        Text(m.mean?.let { "%.1f".format(it) } ?: "—", Modifier.widthIn(min = 44.dp), textAlign = TextAlign.End,
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
    if (divider) HorizontalDivider(color = t.innerDivider)
}

/** Dose label | thin anxiety bar (avg/10) + value | thin joy bar (avg/5) + value. */
@Composable
private fun DoseGrid(doses: List<DoseRow>) {
    val t = MaterialTheme.bcalm
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer(Modifier.width(64.dp))
            Text("Anxiety", Modifier.weight(1f), fontSize = 12.sp, color = t.secondary)
            Text("Joy", Modifier.weight(1f), fontSize = 12.sp, color = t.secondary)
        }
        doses.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(r.label, Modifier.width(64.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                DoseBar(r.anxAvg, 10f, t.accent, Modifier.weight(1f))
                DoseBar(r.joyAvg, 5f, t.joy, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DoseBar(avg: Double?, max: Float, color: Color, modifier: Modifier) {
    val t = MaterialTheme.bcalm
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(t.innerDivider)) {
            if (avg != null) Box(Modifier.fillMaxWidth((avg.toFloat() / max).coerceIn(0f, 1f)).height(6.dp)
                .clip(RoundedCornerShape(3.dp)).background(color))
        }
        Text(avg?.let { "%.1f".format(it) } ?: "–", Modifier.width(30.dp), fontSize = 14.sp)
    }
}

@Composable
private fun SweatRowView(label: String, value: String?, divider: Boolean = true) {
    val t = MaterialTheme.bcalm
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(if (value == null) 1f else 0.4f), fontSize = 15.sp)
        if (value != null) Text(value, Modifier.weight(0.6f), textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyMedium, color = t.secondary)
    }
    if (divider) HorizontalDivider(color = t.innerDivider)
}

@Composable
private fun PatternRow(label: String, count: String?, diff: Double, divider: Boolean) {
    val t = MaterialTheme.bcalm
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count != null) Text(count, Modifier.padding(end = 10.dp), fontSize = 12.sp, color = t.secondary)
        Text(fmtDiff(diff), Modifier.widthIn(min = 44.dp), textAlign = TextAlign.End,
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
    if (divider) HorizontalDivider(color = t.innerDivider)
}

/**
 * Hourly mean bars (muted, rounded top corners; the [highlight] hours in accent), small faint dots for
 * single entries, hairline baseline.
 */
@Composable
fun HourOfDayChart(
    data: List<Entry>, modifier: Modifier,
    xOf: (Entry) -> Float? = { it.hourOfDay }, fixedLo: Int? = null, fixedHi: Int? = null,
    labelStep: Int = 3, highlight: IntRange? = null, label: (Int) -> String = { "$it" },
) {
    val measurer = rememberTextMeasurer()
    val t = MaterialTheme.bcalm
    val grid = t.innerDivider
    val baseline = t.cardBorder
    val dot = t.secondary.copy(alpha = 0.45f)
    val bar = t.mutedBar
    val barHi = t.accent
    val hiC = t.high
    val loC = t.low
    val txt = t.secondary
    Canvas(modifier) {
        val l = 28.dp.toPx(); val b = 24.dp.toPx(); val top0 = 8.dp.toPx(); val r = 8.dp.toPx()
        val w = size.width - l - r; val h = size.height - top0 - b
        val hours = data.mapNotNull { xOf(it)?.toInt() }
        val lo = fixedLo ?: ((minOf(6, hours.minOrNull() ?: 6) / 3) * 3)
        val hi = fixedHi ?: minOf(24, ((maxOf(21, (hours.maxOrNull() ?: 21) + 1) + 2) / 3) * 3)
        val span = (hi - lo).toFloat()
        fun x(hr: Float) = l + w * (hr - lo) / span
        fun y(a: Float) = top0 + h * (1 - a / 10f)   // baseline = 0, top = 10
        val style = TextStyle(color = txt, fontSize = 10.sp)
        for (a in listOf(1, 5, 10)) {
            if (a > 1) drawLine(grid, Offset(l, y(a.toFloat())), Offset(l + w, y(a.toFloat())), 1.dp.toPx())
            val m = measurer.measure("$a", style)
            drawText(m, topLeft = Offset(l - m.size.width - 6.dp.toPx(), y(a.toFloat()) - m.size.height / 2f))
        }
        var hr = lo
        while (hr < hi) {
            val m = measurer.measure(label(hr), style)
            drawText(m, topLeft = Offset(x(hr + 0.5f) - m.size.width / 2f, top0 + h + 6.dp.toPx()))
            hr += labelStep
        }
        val slot = w / span
        val barW = maxOf(3.dp.toPx(), slot * 0.7f)
        val corner = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        for (hh in lo until hi) {
            val xs = data.filter { xOf(it)?.toInt() == hh }.map { it.anxiety!! }
            if (xs.isEmpty()) continue
            val top = y(xs.average().toFloat())
            val left = x(hh + 0.5f) - barW / 2f
            val p = Path().apply {
                addRoundRect(RoundRect(left, top, left + barW, y(0f), corner, corner, CornerRadius.Zero, CornerRadius.Zero))
            }
            drawPath(p, if (highlight != null && hh in highlight) barHi else bar)
        }
        data.forEach { e ->
            val ex = xOf(e) ?: return@forEach
            drawCircle(dot, 2.dp.toPx(), Offset(x(ex), y(e.anxiety!!.coerceIn(1, 10).toFloat())))
        }
        val byHour = (lo until hi).map { hh -> data.filter { xOf(it)?.toInt() == hh }.map { it.anxiety!!.coerceIn(1, 10) } }
        for ((p, c) in listOf(90.0 to hiC, 10.0 to loC)) {
            val pts = byHour.map { v -> if (v.isEmpty()) null else percentile(v, p).toFloat() }
            for (i in pts.indices) {
                val v = pts[i] ?: continue
                val o = Offset(x(lo + i + 0.5f), y(v))
                val prev = pts.getOrNull(i - 1)
                if (prev != null) drawLine(c, Offset(x(lo + i - 0.5f), y(prev)), o, 1.5.dp.toPx(), StrokeCap.Round)
                if (prev == null && pts.getOrNull(i + 1) == null) drawCircle(c, 2.5.dp.toPx(), o)
            }
        }
        drawLine(baseline, Offset(l, y(0f)), Offset(l + w, y(0f)), 1.dp.toPx())
    }
}

/**
 * Modeled profile: p10..p90 band (accent, faint), mean line, dashed baseline, and the ratings as dots (check-ins
 * brighter). [profile] slot i is x = i (+0.5 for its centre); null slots break the line and band.
 */
@Composable
fun CurveChart(
    data: List<Entry>, profile: List<ProfilePt?>, modifier: Modifier,
    xOf: (Entry) -> Float? = { it.hourOfDay }, fixedLo: Int? = null, fixedHi: Int? = null,
    labelStep: Int = 3, highlight: IntRange? = null, label: (Int) -> String = { "$it" },
) {
    val measurer = rememberTextMeasurer()
    val t = MaterialTheme.bcalm
    val grid = t.innerDivider
    val baseline = t.cardBorder
    val dot = t.secondary.copy(alpha = 0.45f)
    val dotP = t.secondary.copy(alpha = 0.8f)
    val accent = t.accent
    val txt = t.secondary
    Canvas(modifier) {
        val l = 28.dp.toPx(); val b = 24.dp.toPx(); val top0 = 8.dp.toPx(); val r = 8.dp.toPx()
        val w = size.width - l - r; val h = size.height - top0 - b
        val hours = data.mapNotNull { xOf(it)?.toInt() } + profile.indices.filter { profile[it] != null }
        val lo = fixedLo ?: ((minOf(6, hours.minOrNull() ?: 6) / 3) * 3)
        val hi = fixedHi ?: minOf(24, ((maxOf(21, (hours.maxOrNull() ?: 21) + 1) + 2) / 3) * 3)
        val span = (hi - lo).toFloat()
        fun x(hr: Float) = l + w * (hr - lo) / span
        fun y(a: Float) = top0 + h * (1 - a / 10f)   // baseline = 0, top = 10
        val style = TextStyle(color = txt, fontSize = 10.sp)
        for (a in listOf(1, 5, 10)) {
            if (a > 1) drawLine(grid, Offset(l, y(a.toFloat())), Offset(l + w, y(a.toFloat())), 1.dp.toPx())
            val m = measurer.measure("$a", style)
            drawText(m, topLeft = Offset(l - m.size.width - 6.dp.toPx(), y(a.toFloat()) - m.size.height / 2f))
        }
        var hr = lo
        while (hr < hi) {
            val m = measurer.measure(label(hr), style)
            drawText(m, topLeft = Offset(x(hr + 0.5f) - m.size.width / 2f, top0 + h + 6.dp.toPx()))
            hr += labelStep
        }
        val slot = w / span
        if (highlight != null) for (hh in highlight) if (hh >= lo && hh < hi)
            drawRect(accent.copy(alpha = 0.06f), Offset(x(hh.toFloat()), top0), Size(slot, h))
        // Contiguous runs of non-null slots inside the axis.
        val runs = ArrayList<List<Int>>()
        var cur = ArrayList<Int>()
        for (i in lo until hi) {
            if (profile.getOrNull(i) != null) cur.add(i)
            else if (cur.isNotEmpty()) { runs.add(cur); cur = ArrayList() }
        }
        if (cur.isNotEmpty()) runs.add(cur)
        for (run in runs) {
            val pts = run.map { profile[it]!! }
            val xs = run.map { x(it + 0.5f) }
            if (run.size > 1) {
                val band = Path().apply {
                    moveTo(xs[0], y(pts[0].p90.toFloat()))
                    for (i in 1 until run.size) lineTo(xs[i], y(pts[i].p90.toFloat()))
                    for (i in run.indices.reversed()) lineTo(xs[i], y(pts[i].p10.toFloat()))
                    close()
                }
                drawPath(band, accent.copy(alpha = 0.15f))
                val bl = Path().apply {
                    moveTo(xs[0], y(pts[0].base.toFloat()))
                    for (i in 1 until run.size) lineTo(xs[i], y(pts[i].base.toFloat()))
                }
                drawPath(bl, txt, style = Stroke(1.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
                val line = Path().apply {
                    moveTo(xs[0], y(pts[0].mean.toFloat()))
                    for (i in 1 until run.size) lineTo(xs[i], y(pts[i].mean.toFloat()))
                }
                drawPath(line, accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            } else {
                val p = pts[0]
                drawRect(accent.copy(alpha = 0.15f), Offset(xs[0] - slot * 0.35f, y(p.p90.toFloat())),
                    Size(slot * 0.7f, maxOf(1f, y(p.p10.toFloat()) - y(p.p90.toFloat()))))
                drawCircle(accent, 2.5.dp.toPx(), Offset(xs[0], y(p.mean.toFloat())))
            }
        }
        data.forEach { e ->
            val ex = xOf(e) ?: return@forEach
            drawCircle(if (e.isPrompted()) dotP else dot, 2.dp.toPx(), Offset(x(ex), y(e.anxiety!!.coerceIn(1, 10).toFloat())))
        }
        drawLine(baseline, Offset(l, y(0f)), Offset(l + w, y(0f)), 1.dp.toPx())
    }
}

/** Bars of modeled hours above 5 per day, on the same date axis as [TimeSeriesChart] (same [data] and [range]). */
@Composable
fun HoursAboveChart(hours: Map<LocalDate, Double>, data: List<Entry>, range: Int, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val tk = MaterialTheme.bcalm
    val baseline = tk.cardBorder
    val bar = tk.accent
    val txt = tk.secondary
    Canvas(modifier) {
        val l = 28.dp.toPx(); val b = 4.dp.toPx(); val t = 8.dp.toPx(); val r = 8.dp.toPx()
        val w = size.width - l - r; val h = size.height - t - b
        val today = LocalDate.now()
        val first = when {
            range > 0 -> today.minusDays(range - 1L)
            data.isEmpty() -> today
            else -> minOf(today, data.minOf { it.dateTime.toLocalDate() })
        }
        val days = (today.toEpochDay() - first.toEpochDay() + 1).toInt()
        val slot = w / days
        val mx = maxOf(1, Math.ceil(hours.values.maxOrNull() ?: 0.0).toInt())
        fun y(v: Float) = t + h * (1 - v / mx)
        val style = TextStyle(color = txt, fontSize = 10.sp)
        for (v in listOf(0, mx)) {
            val m = measurer.measure("$v", style)
            drawText(m, topLeft = Offset(l - m.size.width - 6.dp.toPx(), y(v.toFloat()) - m.size.height / 2f))
        }
        val bw = maxOf(2.dp.toPx(), slot * 0.7f)
        for ((d, v) in hours) {
            val i = (d.toEpochDay() - first.toEpochDay()).toInt()
            if (i < 0 || i >= days || v <= 0.0) continue
            val top = y(v.toFloat())
            drawRoundRect(bar, Offset(l + slot * (i + 0.5f) - bw / 2f, top), Size(bw, y(0f) - top),
                CornerRadius(2.dp.toPx(), 2.dp.toPx()))
        }
        drawLine(baseline, Offset(l, y(0f)), Offset(l + w, y(0f)), 1.dp.toPx())
    }
}

/** Daily-average line (accent) over faint dots for single entries, on a date axis. */
@Composable
fun TimeSeriesChart(data: List<Entry>, range: Int, modifier: Modifier, band: Map<LocalDate, Triple<Double, Double, Double>>? = null) {
    val measurer = rememberTextMeasurer()
    val tk = MaterialTheme.bcalm
    val grid = tk.innerDivider
    val baseline = tk.cardBorder
    val dot = tk.secondary.copy(alpha = 0.45f)
    val line = tk.accent
    val txt = tk.secondary
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
            drawCircle(dot, 2.dp.toPx(), Offset(x(e.dateTime.atZone(zone).toInstant().toEpochMilli()), y(e.anxiety!!.coerceIn(1, 10).toFloat())))
        }
        val pts = if (band != null) band.toSortedMap().map { (day, b) ->
            Offset(x(day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()), y(b.second.toFloat()))   // worst stretch = day's p90
        } else data.groupBy { it.dateTime.toLocalDate() }.toSortedMap().map { (day, es) ->
            Offset(x(day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()),
                y(es.map { it.anxiety!!.coerceIn(1, 10) }.average().toFloat()))
        }
        if (pts.size > 1) {
            val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
            drawPath(path, line, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        pts.forEach { drawCircle(line, 3.dp.toPx(), it) }
        if (band != null) {
            val mq = band.toSortedMap().map { (day, b) ->
                Offset(x(day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()), y(b.first.toFloat()))
            }
            if (mq.size > 1) {
                val path = Path().apply { moveTo(mq[0].x, mq[0].y); mq.drop(1).forEach { lineTo(it.x, it.y) } }
                drawPath(path, txt, style = Stroke(1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            val ls = TextStyle(color = line, fontSize = 10.sp)
            val m1 = measurer.measure("worst stretch", ls)
            drawText(m1, topLeft = Offset(l + 6.dp.toPx(), t))
            drawText(measurer.measure("daily average", style), topLeft = Offset(l + 14.dp.toPx() + m1.size.width, t))
            drawLine(baseline, Offset(l, y(0f)), Offset(l + w, y(0f)), 1.dp.toPx())
            return@Canvas
        }
        val dayVals = data.groupBy { it.dateTime.toLocalDate() }.toSortedMap().map { (day, es) ->
            val xx = x(day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli())
            val v = es.map { it.anxiety!!.coerceIn(1, 10) }
            Triple(xx, percentile(v, 90.0).toFloat(), percentile(v, 10.0).toFloat())
        }
        for (hiLine in listOf(true, false)) {
            val c = if (hiLine) tk.high else tk.low
            val q = dayVals.map { Offset(it.first, y(if (hiLine) it.second else it.third)) }
            if (q.size > 1) {
                val path = Path().apply { moveTo(q[0].x, q[0].y); q.drop(1).forEach { lineTo(it.x, it.y) } }
                drawPath(path, c, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            q.forEach { drawCircle(c, 2.dp.toPx(), it) }
        }
        drawLine(baseline, Offset(l, y(0f)), Offset(l + w, y(0f)), 1.dp.toPx())
    }
}

// ---------------- History ----------------

private class DayGroup(val day: LocalDate, val rows: List<Entry>)

private val TOOL_NAMES = mapOf("card" to "card", "breathe" to "breathing", "ground" to "grounding")

/** Only the notable details: causes, then context that is not the unremarkable default. */
private fun detailLine(e: Entry): String {
    val parts = ArrayList<String>(e.causes)
    if (e.after != null) parts.add("→ ${e.after}" + e.tools.mapNotNull { TOOL_NAMES[it] }
        .takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = " (", postfix = ")").orEmpty())
    if (e.caffeine == true) parts.add("Caffeine")
    if (e.food == false) parts.add("No food")
    if (e.partner == 2) parts.add("Partner tense")
    if (e.joy != null) parts.add("Joy ${e.joy}")
    return parts.joinToString(" · ")
}

@Composable
fun HistoryScreen(vm: MainViewModel, snack: SnackbarHostState) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val t = MaterialTheme.bcalm
    val timeFmt = remember { java.time.format.DateTimeFormatter.ofPattern("HH:mm") }
    val dayFmt = remember { java.time.format.DateTimeFormatter.ofPattern("EEE d MMM") }
    val groups = remember(entries) {
        entries.groupBy { it.dateTime.toLocalDate() }
            .map { (d, l) -> DayGroup(d, l.sortedByDescending { it.ts }) }
            .sortedByDescending { it.day }
    }
    val today = LocalDate.now()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Column(Modifier.padding(horizontal = 4.dp)) { ScreenTitle("History") } }
        if (entries.isEmpty()) {
            item { Caption("Nothing here yet. Entries show up once you log.", Modifier.padding(horizontal = 4.dp)) }
        }
        items(groups, key = { it.day.toEpochDay() }) { g ->
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
            SectionCard(padding = PaddingValues(0.dp), spacing = 0.dp) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.Bottom) {
                    SectionHeader(label, Modifier.weight(1f))
                    if (info.isNotEmpty()) Text(info, Modifier.weight(2f, fill = false).padding(start = 8.dp),
                        style = MaterialTheme.typography.labelSmall, color = t.secondary, textAlign = TextAlign.End)
                }
                g.rows.forEach { e ->
                    key(e.ts) {
                        HorizontalDivider(color = t.innerDivider)
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
                            Row(Modifier.fillMaxWidth().background(t.card).padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text(e.anxiety?.toString() ?: "–", Modifier.width(40.dp),
                                    style = MaterialTheme.typography.titleLarge, fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold)
                                Column(Modifier.weight(1f)) {
                                    Text(e.dateTime.format(timeFmt) + if (e.isPrompted()) "  · check-in" else if (e.isFollowup()) "  · follow-up" else if (e.isRecalled()) "  · recalled" else "",
                                        style = MaterialTheme.typography.bodyLarge)
                                    val detail = detailLine(e)
                                    if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall,
                                        color = t.secondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Settings ----------------

@Composable
private fun SettingsSection(title: String, hint: String? = null, content: @Composable ColumnScope.() -> Unit) {
    SectionCard(spacing = 0.dp) {
        Text(title, Modifier.padding(bottom = 6.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        content()
        if (hint != null) Caption(hint, Modifier.padding(top = 6.dp))
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel, onExport: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var promptsOn by remember { mutableStateOf(Store.promptsOn) }
    var followOn by remember { mutableStateOf(Store.followupsOn) }
    var pausedUntil by remember { mutableLongStateOf(Store.promptsPausedUntil) }
    val windows = remember { mutableStateListOf(*Array(Prompts.WINDOWS) { Store.promptWindow(it) }) }
    var editWin by remember { mutableStateOf<Int?>(null) }
    var editStart by remember { mutableStateOf<Int?>(null) }
    var pauseMenu by remember { mutableStateOf(false) }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var medName by remember { mutableStateOf(Store.medName) }
    val causeList by vm.causeList.collectAsStateWithLifecycle()
    var addingCause by remember { mutableStateOf(false) }
    var removingCause by remember { mutableStateOf<String?>(null) }
    var editingCard by remember { mutableStateOf<String?>(null) }
    val cards by vm.cards.collectAsStateWithLifecycle()
    val supportVer by Store.supportVersion.collectAsStateWithLifecycle()   // bumps when a sync merge changes them
    var crisisLabel by remember(supportVer) { mutableStateOf(Store.crisisLabel) }
    var crisisNumber by remember(supportVer) { mutableStateOf(Store.crisisNumber) }
    var personLabel by remember(supportVer) { mutableStateOf(Store.personLabel) }
    var personNumber by remember(supportVer) { mutableStateOf(Store.personNumber) }
    var customStep by remember(supportVer) { mutableStateOf(Store.customStep) }
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
    val existingPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) CsvSync.setExistingTarget(ctx, uri)
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
                        val cs0 = Csv.parseSettings(text)
                        val c = Store.restoreSettings(cs0)
                        Store.mergeFromFile(emptyList(), CsvSettings(prompts = cs0.prompts))   // prompts only: merge like a sync file
                        "Imported $n entries (${list.size - n} skipped)" + (if (k > 0) ", $k causes restored" else "") +
                            if (c > 0) ", $c calm cards restored" else ""
                    }
                } catch (e: Exception) { "Import failed: ${e.message ?: e.javaClass.simpleName}" }
            }
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.padding(horizontal = 4.dp)) { ScreenTitle("Settings") }

        SettingsSection("Check-ins",
            "One notification at a random time in each window. Rate in one tap; these samples show your typical day, not just the hard moments.") {
            LabeledRow("Random check-ins (3 a day)") {
                Switch(checked = promptsOn, onCheckedChange = { v ->
                    promptsOn = v; Store.promptsOn = v
                    if (v && Build.VERSION.SDK_INT >= 33 && !Prompts.canNotify(ctx)) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                    Prompts.plan(ctx)
                })
            }
            LabeledRow("Follow up after hard moments") {
                Switch(checked = followOn, onCheckedChange = { v ->
                    followOn = v; Store.followupsOn = v
                    if (v && Build.VERSION.SDK_INT >= 33 && !Prompts.canNotify(ctx)) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                    Prompts.plan(ctx)
                })
            }
            Caption("After you log 5 or more, a few quick check-backs (30 min, 1.5 h, 4 h) to see how it fades. Stops once you're back near baseline.")
            if (promptsOn) {
                for (w in 0 until Prompts.WINDOWS) {
                    val (ws, we) = windows[w]
                    LabeledRow(Prompts.NAMES[w]) {
                        TextButton(onClick = { editWin = w; editStart = null; android.widget.Toast.makeText(ctx, "Set the start time", android.widget.Toast.LENGTH_SHORT).show() }) { Text("${hm(ws)}–${hm(we)}") }
                    }
                }
                val paused = pausedUntil > Store.today()
                LabeledRow(if (paused) "Paused until " + LocalDate.ofEpochDay(pausedUntil).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())) else "Pause") {
                    if (paused) TextButton(onClick = { pausedUntil = 0L; Store.promptsPausedUntil = 0L; Prompts.plan(ctx) }) { Text("Resume") }
                    else Box {
                        TextButton(onClick = { pauseMenu = true }) { Text("Pause for…") }
                        DropdownMenu(expanded = pauseMenu, onDismissRequest = { pauseMenu = false }) {
                            listOf(1, 3, 7).forEach { n ->
                                DropdownMenuItem(text = { Text(if (n == 1) "1 day" else "$n days") }, onClick = {
                                    pauseMenu = false
                                    pausedUntil = Store.today() + n; Store.promptsPausedUntil = pausedUntil; Prompts.plan(ctx)
                                })
                            }
                        }
                    }
                }
            }
        }
        editWin?.let { w ->
            val (ws, we) = windows[w]
            val first = editStart == null
            key(first) { TimeDialog(if (first) ws else we, { editWin = null }) { m ->
                if (first) { editStart = m; android.widget.Toast.makeText(ctx, "Now set the end time", android.widget.Toast.LENGTH_SHORT).show(); return@TimeDialog }
                val st = editStart!!
                if (m - st < 30) {
                    android.widget.Toast.makeText(ctx, "End must be at least 30 minutes after start", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    windows[w] = st to m; Store.setPromptWindow(w, st, m); editWin = null
                    Prompts.plan(ctx, reroll = true)
                }
            } }
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
            causeList.forEachIndexed { i, c ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { editingCard = c },
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(c, Modifier.weight(1f, fill = false), fontSize = 15.sp)
                    if (c in cards) Text("  ● calm card", fontSize = 12.sp, color = MaterialTheme.bcalm.accent)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { removingCause = c }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove $c")
                    }
                }
                if (i < causeList.lastIndex) HorizontalDivider(color = cs.outlineVariant)
            }
            TextButton(onClick = { addingCause = true }) { Text("Add cause…") }
            Caption("Tip: a neutral name keeps the list easy to look at; details can live in the calm card.")
        }

        SettingsSection("Support", "Shown on the calm card at 8 or above, or for causes you mark.") {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = crisisLabel, onValueChange = { crisisLabel = it.take(30); Store.crisisLabel = crisisLabel },
                    label = { Text("Label") }, placeholder = { Text("Crisis line") }, singleLine = true,
                    shape = SmallShape, modifier = Modifier.weight(1f))
                OutlinedTextField(value = crisisNumber, onValueChange = { v ->
                    crisisNumber = v.filter { it.isDigit() || it in "+*#" }.take(20); Store.crisisNumber = crisisNumber
                }, label = { Text("Number") }, singleLine = true, shape = SmallShape,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = personLabel, onValueChange = { personLabel = it.take(30); Store.personLabel = personLabel },
                    label = { Text("Label") }, placeholder = { Text("Therapist") }, singleLine = true,
                    shape = SmallShape, modifier = Modifier.weight(1f))
                OutlinedTextField(value = personNumber, onValueChange = { v ->
                    personNumber = v.filter { it.isDigit() || it in "+*#" }.take(20); Store.personNumber = personNumber
                }, label = { Text("Number") }, singleLine = true, shape = SmallShape,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.weight(1f))
            }
            OutlinedTextField(value = customStep, onValueChange = { customStep = it.take(80); Store.customStep = customStep },
                label = { Text("A step to take") }, placeholder = { Text("Something that helps, e.g. go for a walk") },
                singleLine = true, shape = SmallShape, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }

        SettingsSection("Sync to Google Drive") {
            val target = syncUri
            if (target == null) {
                Caption("Pick a new file, or an existing bcalm CSV to merge it in. Changes are synced both ways.",
                    Modifier.padding(vertical = 8.dp))
                Row {
                    TextButton(onClick = { picker.launch("bcalm.csv") }) { Text("New file…") }
                    TextButton(onClick = { existingPicker.launch(SYNC_MIMES) }) { Text("Use existing file…") }
                }
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
                    color = if (syncState.message.startsWith("Failed") || syncState.message == CsvSync.MISSING_MSG) cs.error else cs.onSurfaceVariant)
                Row {
                    TextButton(onClick = { CsvSync.enqueue(ctx) }) { Text("Sync now") }
                    TextButton(onClick = { CsvSync.stop(ctx) }) { Text("Stop syncing") }
                }
                Row {
                    TextButton(onClick = { picker.launch("bcalm.csv") }) { Text("New file…") }
                    TextButton(onClick = { existingPicker.launch(SYNC_MIMES) }) { Text("Use existing file…") }
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
        Caption("Version ${info.versionName} · installed $built", Modifier.padding(start = 4.dp, top = 4.dp))
        UpdateCheckRow(ctx, PackageInfoCompat.getLongVersionCode(info))
        Caption("Updates are only checked when you tap the button; the app makes no other network calls.",
            Modifier.padding(start = 4.dp, bottom = 48.dp))
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
    editingCard?.let { c ->
        val cur = cards[c] ?: CauseCard()
        var thought by remember(c) { mutableStateOf(cur.thought) }
        var todo by remember(c) { mutableStateOf(cur.todo) }
        var heavy by remember(c) { mutableStateOf(cur.heavy) }
        AlertDialog(onDismissRequest = { editingCard = null },
            title = { Text("Calm card · $c") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = thought, onValueChange = { thought = it.take(300) },
                        label = { Text("Calm thought") }, placeholder = { Text("What you'd tell yourself when calm") },
                        minLines = 2, shape = SmallShape, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = todo, onValueChange = { todo = it.take(200) },
                        label = { Text("What to do") }, placeholder = { Text("One small step") },
                        minLines = 2, shape = SmallShape, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Show support options when I log this", Modifier.weight(1f), fontSize = 14.sp)
                        Switch(heavy, { heavy = it })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { Store.setCard(c, CauseCard(thought, todo, heavy)); editingCard = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editingCard = null }) { Text("Cancel") } })
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

private val SYNC_MIMES = arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream", "*/*")
