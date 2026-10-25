package com.example.anxietytracker

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.view.HapticFeedbackConstants
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// ---- Settings (own prefs file; Store is untouched) ----

/** Remembered breathing choices; keys: pattern, duration (index into [CalmMinutes]), haptics, sound. */
private object CalmPrefs {
    private fun sp(c: Context) = c.getSharedPreferences("calm", Context.MODE_PRIVATE)
    fun pattern(c: Context) = sp(c).getInt("pattern", 0).coerceIn(0, CalmPatterns.lastIndex)
    fun duration(c: Context) = sp(c).getInt("duration", 1).coerceIn(0, CalmMinutes.lastIndex)
    fun haptics(c: Context) = sp(c).getBoolean("haptics", true)
    fun sound(c: Context) = sp(c).getBoolean("sound", false)
    fun putInt(c: Context, key: String, v: Int) = sp(c).edit().putInt(key, v).apply()
    fun putBool(c: Context, key: String, v: Boolean) = sp(c).edit().putBoolean(key, v).apply()
}

// ---- Breathing model ----

/** One leg of a cycle: lung level moves [from] -> [to] (0 empty, 1 full) over [secs]. */
private class Phase(val secs: Float, val from: Float, val to: Float)

private class BreathPattern(val label: String, val phases: List<Phase>) {
    val starts = FloatArray(phases.size)
    val length: Float
    private val exhaleEnd: Float

    init {
        var c = 0f
        var ee = 0f
        phases.forEachIndexed { i, p ->
            starts[i] = c
            c += p.secs
            if (p.to < p.from) ee = c
        }
        length = c
        exhaleEnd = ee
    }

    fun indexAt(c: Float): Int {
        for (i in phases.indices.reversed()) if (c >= starts[i]) return i
        return 0
    }

    /** Lung level 0..1 at cycle position [c], sine-eased within each phase. */
    fun levelAt(c: Float): Float {
        val i = indexAt(c)
        val p = phases[i]
        val t = ((c - starts[i]) / p.secs).coerceIn(0f, 1f)
        return p.from + (p.to - p.from) * (0.5f - 0.5f * cos(PI.toFloat() * t))
    }

    /** First exhale end at or after [dur] seconds, so the session never stops mid-breath. */
    fun endTime(dur: Float): Float {
        val k = max(0, ceil((dur - exhaleEnd) / length).toInt())
        return k * length + exhaleEnd
    }
}

private fun inhale(s: Float) = Phase(s, 0f, 1f)
private fun exhale(s: Float) = Phase(s, 1f, 0f)
private fun holdFull(s: Float) = Phase(s, 1f, 1f)
private fun holdEmpty(s: Float) = Phase(s, 0f, 0f)

private val CalmPatterns = listOf(
    BreathPattern("4 · 8", listOf(inhale(4f), exhale(8f))),
    BreathPattern("4 · 7 · 8", listOf(inhale(4f), holdFull(7f), exhale(8f))),
    BreathPattern("Box 4 · 4 · 4 · 4", listOf(inhale(4f), holdFull(4f), exhale(4f), holdEmpty(4f))),
    BreathPattern("5 · 5", listOf(inhale(5f), exhale(5f))),
)
private val CalmMinutes = listOf(1, 2, 3, 5)

private const val FadeIn = 1.5f
private const val Settle = 2.5f
private const val Blend = 1.2f

private fun smooth(x: Float) = 0.5f - 0.5f * cos(PI.toFloat() * x.coerceIn(0f, 1f))
private fun mix(a: Float, b: Float, f: Float) = a + (b - a) * f

/** Everything the canvas needs for one frame. [kind]: 0 in, 1 hold, 2 out, -1 none. */
private class Frame(
    val lung: Float, val r1: Float, val r2: Float, val r3: Float,
    val shimmer: Float, val progress: Float, val alpha: Float, val kind: Int,
)

private val IdleFrame = Frame(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0)

private class RunState {
    var started = false
    var lastLung = 0f
}

private class GainBox {
    @Volatile var v = 0.08f
}

// ---- Shared pieces ----

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        val prev = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = prev }
    }
}

/** Theme background, slightly deepened toward the edges. */
@Composable
private fun CalmBackdrop() {
    val bg = MaterialTheme.bcalm.background
    val edge = lerp(bg, Color.Black, if (bg.luminance() < 0.5f) 0.35f else 0.06f)
    Canvas(Modifier.fillMaxSize()) {
        drawRect(bg)
        drawRect(Brush.radialGradient(listOf(bg, edge), center = center, radius = size.maxDimension * 0.7f))
    }
}

@Composable
private fun CloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val col = MaterialTheme.bcalm.ink.copy(alpha = 0.35f)
    Box(
        modifier.size(48.dp).clip(CircleShape)
            .clickable(onClickLabel = "Close", role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(14.dp)) {
            val w = 1.5f.dp.toPx()
            drawLine(col, Offset.Zero, Offset(size.width, size.height), w, StrokeCap.Round)
            drawLine(col, Offset(size.width, 0f), Offset(0f, size.height), w, StrokeCap.Round)
        }
    }
}

@Composable
private fun QuietText(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text, fontSize = 13.sp, color = MaterialTheme.bcalm.secondary.copy(alpha = 0.8f),
        modifier = modifier.clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun QuietToggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val t = MaterialTheme.bcalm
    Row(
        Modifier.clip(RoundedCornerShape(8.dp))
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(7.dp).clip(CircleShape)
                .then(if (on) Modifier.background(t.accent) else Modifier.border(1.dp, t.secondary, CircleShape)),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 13.sp, color = if (on) t.ink.copy(alpha = 0.85f) else t.secondary.copy(alpha = 0.7f))
        Spacer(Modifier.width(6.dp))
        Text(if (on) "on" else "off", fontSize = 11.sp, color = if (on) t.accent else t.secondary.copy(alpha = 0.55f))
    }
}

/** "How is it now?" with a 1-10 row; a pick or Skip ends the screen. */
@Composable
private fun RateStep(onFinish: (Int?) -> Unit) {
    val t = MaterialTheme.bcalm
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("How is it now?", fontSize = 24.sp, fontWeight = FontWeight.Light, color = t.ink.copy(alpha = 0.85f))
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (n in 1..10) {
                Box(
                    Modifier.weight(1f).height(46.dp).clip(shape)
                        .background(t.card)
                        .border(1.dp, t.cardBorder, shape)
                        .clickable(role = Role.Button) { onFinish(n) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$n", maxLines = 1, fontSize = if (n >= 10) 14.sp else 15.sp, color = t.ink)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Calm", fontSize = 12.sp, color = t.secondary)
            Text("Overwhelmed", fontSize = 12.sp, color = t.secondary)
        }
        Spacer(Modifier.height(20.dp))
        QuietText("Skip", onClick = { onFinish(null) })
    }
}

// ---- Breathe ----

/** Full-screen paced breathing. onFinish(after): after = re-rated anxiety 1..10, or null if closed/skipped. */
@Composable
fun BreatheScreen(onFinish: (after: Int?) -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val view = LocalView.current
    val t = MaterialTheme.bcalm

    var patIdx by remember { mutableIntStateOf(CalmPrefs.pattern(ctx)) }
    var durIdx by remember { mutableIntStateOf(CalmPrefs.duration(ctx)) }
    var haptics by remember { mutableStateOf(CalmPrefs.haptics(ctx)) }
    var sound by remember { mutableStateOf(CalmPrefs.sound(ctx)) }
    var finished by remember { mutableStateOf(false) }

    val frame = remember { mutableStateOf(IdleFrame) }
    val gain = remember { GainBox() }
    val run = remember { RunState() }
    val durRef = rememberUpdatedState(durIdx)
    val hapticsRef = rememberUpdatedState(haptics)

    KeepScreenOn()

    // One frame clock drives orb, rings, arc, haptics and sound gain. dt is clamped, so a paused
    // app (no frames) simply resumes where it left off.
    LaunchedEffect(patIdx, finished) {
        if (finished) return@LaunchedEffect
        val pat = CalmPatterns[patIdx]
        val fresh = !run.started
        run.started = true
        val from = if (fresh) 0f else run.lastLung
        var b = if (fresh) -FadeIn else 0f
        var lastKey = -1
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            b += ((now - last) / 1e9f).coerceIn(0f, 0.1f)
            last = now
            val bb = max(b, 0f)
            val end = pat.endTime(CalmMinutes[durRef.value] * 60f)
            val settle = bb - end
            if (settle >= Settle) {
                finished = true
                return@LaunchedEffect
            }
            val settling = settle > 0f
            val w = if (fresh) 1f else smooth(bb / Blend)
            val cyc = bb % pat.length
            val idx = pat.indexAt(cyc)
            val ph = pat.phases[idx]
            val lung = mix(from, pat.levelAt(min(bb, end) % pat.length), w)
            fun ring(k: Int): Float {
                val tb = min(bb, end) - (k + 1) * 0.4f
                return mix(from, if (tb < 0f) 0f else pat.levelAt(tb % pat.length), w)
            }
            val shimmer = if (ph.from == ph.to && !settling) {
                val pt = ((cyc - pat.starts[idx]) / ph.secs).coerceIn(0f, 1f)
                0.015f * sin(2f * PI.toFloat() * bb * 0.7f) * sin(PI.toFloat() * pt)
            } else 0f
            val fade = if (b < 0f) smooth((b + FadeIn) / FadeIn) else 1f
            val alpha = fade * (if (settling) 1f - smooth(settle / Settle) else 1f)
            val kind = when {
                settling -> -1
                ph.to > ph.from -> 0
                ph.from > ph.to -> 2
                else -> 1
            }
            val key = (bb / pat.length).toInt() * 16 + idx
            if (b >= 0f && !settling && key != lastKey) {
                lastKey = key
                if (hapticsRef.value) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            run.lastLung = lung
            gain.v = 0.35f + 0.65f * lung
            frame.value = Frame(lung, ring(0), ring(1), ring(2), shimmer, (bb / end).coerceIn(0f, 1f), alpha, kind)
        }
    }

    var mediaMuted by remember { mutableStateOf(false) }
    LaunchedEffect(sound, finished) {
        if (sound && !finished) playNoise(gain)
    }
    // Polled while sound is on, so the caption follows the volume keys.
    LaunchedEffect(sound) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        while (sound && am != null) {
            mediaMuted = am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
            delay(1000)
        }
        mediaMuted = false
    }

    val word by remember {
        derivedStateOf {
            when (frame.value.kind) {
                0 -> "Breathe in"
                1 -> "Hold"
                2 -> "Breathe out"
                else -> ""
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        CalmBackdrop()
        Crossfade(targetState = finished, animationSpec = tween(900), label = "breathe") { done ->
            if (done) {
                RateStep(onFinish)
            } else {
                Box(Modifier.fillMaxSize()) {
                    val accent = t.accent
                    val ink = t.secondary
                    Canvas(Modifier.fillMaxSize()) {
                        val f = frame.value
                        val a = f.alpha
                        val maxR = size.minDimension * 0.30f
                        val r = maxR * (0.55f + 0.45f * f.lung) * (1f + f.shimmer)
                        val stroke = 1.dp.toPx()
                        drawCircle(
                            Brush.radialGradient(
                                0f to accent.copy(alpha = 0.42f * a),
                                0.55f to accent.copy(alpha = 0.22f * a),
                                1f to accent.copy(alpha = 0f),
                                center = center, radius = r,
                            ),
                            radius = r, center = center,
                        )
                        fun ring(lv: Float, k: Int) = drawCircle(
                            accent.copy(alpha = (0.16f - 0.04f * k) * a),
                            radius = maxR * (0.55f + 0.45f * lv) * (1.12f + 0.12f * k),
                            center = center, style = Stroke(stroke),
                        )
                        ring(f.r1, 0); ring(f.r2, 1); ring(f.r3, 2)
                        val ar = maxR * 1.5f
                        val tl = Offset(center.x - ar, center.y - ar)
                        drawCircle(ink.copy(alpha = 0.06f * a), radius = ar, center = center, style = Stroke(stroke))
                        drawArc(
                            ink.copy(alpha = 0.28f * a), startAngle = -90f, sweepAngle = 360f * f.progress,
                            useCenter = false, topLeft = tl, size = Size(ar * 2, ar * 2),
                            style = Stroke(1.5f.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = frame.value.alpha }, Alignment.Center) {
                        Crossfade(targetState = word, animationSpec = tween(700), label = "word") { w ->
                            Text(w, fontSize = 20.sp, fontWeight = FontWeight.Light, letterSpacing = 1.sp,
                                color = t.ink.copy(alpha = 0.6f))
                        }
                    }
                    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                        Row(Modifier.fillMaxWidth().height(56.dp).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.weight(1f))
                            QuietText("Done", onClick = { finished = true })
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically) {
                            QuietText(CalmPatterns[patIdx].label, onClick = {
                                patIdx = (patIdx + 1) % CalmPatterns.size
                                CalmPrefs.putInt(ctx, "pattern", patIdx)
                            })
                            QuietText("${CalmMinutes[durIdx]} min", onClick = {
                                durIdx = (durIdx + 1) % CalmMinutes.size
                                CalmPrefs.putInt(ctx, "duration", durIdx)
                            })
                        }
                    }
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (sound && mediaMuted) Text("Media volume is off", fontSize = 12.sp, color = t.secondary.copy(alpha = 0.6f))
                        Row(horizontalArrangement = Arrangement.Center) {
                            QuietToggle("Haptics", haptics) { haptics = it; CalmPrefs.putBool(ctx, "haptics", it) }
                            QuietToggle("Sound", sound) { sound = it; CalmPrefs.putBool(ctx, "sound", it) }
                        }
                    }
                }
            }
        }
        CloseButton({ onFinish(null) }, Modifier.statusBarsPadding().padding(4.dp))
    }
}

/**
 * Streams soft "surf" noise (white noise low-passed at ~1 kHz with a gentle second pole, rumble below ~120 Hz removed;
 * ~-12 dBFS RMS at full gain) until cancelled. [gain] (0..1) is smoothed per sample. Fades in on start,
 * fades out and releases the track on cancellation.
 */
private suspend fun playNoise(gain: GainBox): Unit = withContext(Dispatchers.Default) {
    val rate = 22050
    val chunk = 1024
    val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBuf <= 0) return@withContext
    val track = try {
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBuf, chunk * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    } catch (e: Exception) {
        return@withContext
    }
    val buf = ShortArray(chunk)
    val rnd = Random(System.nanoTime())
    // One-pole filters at 22.05 kHz: low-pass ~1 kHz, softer second pole, high-pass ~120 Hz.
    val lp1 = 0.248f
    val lp2 = 0.5f
    val hpK = 0.9664f
    val norm = 1.48f   // brings the filtered white noise to ~0.25 RMS
    var y1 = 0f
    var y2 = 0f
    var hp = 0f
    var prev = 0f
    var g = gain.v
    var master = 0f
    fun render(fadeStep: Float) {
        for (i in 0 until chunk) {
            y1 += lp1 * ((rnd.nextFloat() * 2f - 1f) - y1)
            y2 += lp2 * (y1 - y2)
            hp = hpK * (hp + y2 - prev)
            prev = y2
            g += (gain.v - g) * 0.0004f
            master = (master + fadeStep).coerceIn(0f, 1f)
            val s = (hp * norm * g * master).coerceIn(-1f, 1f)
            buf[i] = (s * 32767f).toInt().toShort()
        }
    }
    try {
        render(1f / rate)
        if (track.write(buf, 0, chunk) < 0) return@withContext   // prime the buffer, then start
        track.play()
        while (isActive) {
            render(1f / rate)
            if (track.write(buf, 0, chunk) < 0) break
        }
    } catch (e: Exception) {
        // Audio is optional; fall through to release.
    } finally {
        withContext(NonCancellable) {
            try {
                repeat(8) {
                    render(-1f / (chunk * 8))
                    track.write(buf, 0, chunk)
                }
                track.stop()
            } catch (e: Exception) {
                // ignore
            }
            track.release()
        }
    }
}

// ---- Ground ----

private val GroundSteps = listOf(
    5 to "things you can see",
    4 to "things you can feel",
    3 to "things you can hear",
    2 to "things you can smell",
    1 to "thing you can taste",
)

/** Full-screen 5-4-3-2-1 grounding. Same onFinish contract. */
@Composable
fun GroundScreen(onFinish: (after: Int?) -> Unit) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val t = MaterialTheme.bcalm
    var step by remember { mutableIntStateOf(0) }
    var filled by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val rating = step >= GroundSteps.size

    KeepScreenOn()

    Box(Modifier.fillMaxSize()) {
        CalmBackdrop()
        Box(
            Modifier.fillMaxSize().then(
                if (rating) Modifier else Modifier.clickable(interactionSource = source, indication = null) {
                    if (!busy) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        filled++
                        if (filled >= GroundSteps[step].first) {
                            busy = true
                            scope.launch {
                                delay(700)
                                step++
                                filled = 0
                                busy = false
                            }
                        }
                    }
                },
            ),
        ) {
            Crossfade(targetState = step, animationSpec = tween(900), label = "ground") { s ->
                if (s >= GroundSteps.size) {
                    RateStep(onFinish)
                } else {
                    val (n, label) = GroundSteps[s]
                    GroundPrompt(n, label, if (s == step) filled else n)
                }
            }
            if (!rating) {
                Text(
                    "Tap for each one", fontSize = 13.sp, color = t.secondary.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 32.dp),
                )
            }
        }
        CloseButton({ onFinish(null) }, Modifier.statusBarsPadding().padding(4.dp))
    }
}

@Composable
private fun GroundPrompt(n: Int, label: String, filled: Int) {
    val t = MaterialTheme.bcalm
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$n", fontSize = 88.sp, fontWeight = FontWeight.Light, color = t.ink.copy(alpha = 0.85f))
        Text(label, fontSize = 22.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.Center,
            color = t.ink.copy(alpha = 0.7f))
        Spacer(Modifier.height(36.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            for (i in 0 until n) {
                val fill by animateColorAsState(
                    if (i < filled) t.accent.copy(alpha = 0.8f) else Color.Transparent,
                    animationSpec = tween(300), label = "dot",
                )
                Box(Modifier.size(14.dp).clip(CircleShape).background(fill)
                    .border(1.dp, t.accent.copy(alpha = 0.5f), CircleShape))
            }
        }
    }
}
