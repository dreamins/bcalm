package com.example.anxietytracker

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private val introLines = listOf(
    "Hi. Take a breath.",
    "Log as much or as little as you like.",
    "The more you log, the more you'll understand — but none of it is required.",
    "One tap is plenty. Missed a day? That's fine.",
    "When we check in, it's just to see how you are — answer or don't.",
    "No streaks. No scores. No pressure.",
    "This is here to help, not to add one more thing to worry about.",
)
private const val UPDATE_LINE = "A few gentle changes: random check-ins and soft follow-ups, so the calm moments count too."

/** One-time, full-screen, slow intro: a breathing circle and one soft line at a time. */
@Composable
fun IntroScreen(update: Boolean, onDone: () -> Unit) {
    val t = MaterialTheme.bcalm
    val lines = remember(update) { if (update) listOf(introLines[0], UPDATE_LINE) + introLines.drop(1) else introLines }
    var idx by remember { mutableIntStateOf(0) }
    var taps by remember { mutableIntStateOf(0) }   // a tap cuts the current line short: its fade-out starts early
    val a = remember { Animatable(0f) }
    val finished = idx >= lines.size

    LaunchedEffect(idx) {
        if (idx >= lines.size) return@LaunchedEffect
        val seen = taps
        a.snapTo(0f)
        a.animateTo(1f, tween(1400, easing = FastOutSlowInEasing))
        withTimeoutOrNull((3200 + lines[idx].length * 20).toLong()) { while (taps == seen) delay(50) }
        a.animateTo(0f, tween(1200, easing = FastOutSlowInEasing))
        idx++
    }

    val inf = rememberInfiniteTransition(label = "breath")
    val s by inf.animateFloat(0.9f, 1.05f,
        infiniteRepeatable(tween(5000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s")
    val beginA by animateFloatAsState(if (finished) 1f else 0f, tween(1400, easing = FastOutSlowInEasing), label = "begin")

    Box(Modifier.fillMaxSize().background(t.background)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { taps++ }
        .safeDrawingPadding()) {
        Canvas(Modifier.align(Alignment.Center).size(300.dp).scale(s)) {
            drawCircle(t.accent.copy(alpha = 0.12f))
        }
        if (!finished) Text(lines[idx], Modifier.align(Alignment.Center).widthIn(max = 300.dp).alpha(a.value),
            color = t.ink, fontSize = 20.sp, fontWeight = FontWeight.Medium, lineHeight = 30.sp, textAlign = TextAlign.Center)
        TextButton(onDone, Modifier.align(Alignment.TopEnd).padding(8.dp)) { Text("Skip", color = t.secondary, fontSize = 14.sp) }
        if (beginA > 0f) TextButton({ if (finished) onDone() }, Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp).alpha(beginA)) {
            Text("Begin", color = t.accent, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }
    }
}
