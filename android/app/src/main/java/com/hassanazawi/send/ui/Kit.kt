package com.hassanazawi.send.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun Txt(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text,
        modifier,
        style = if (color == Color.Unspecified) style else style.copy(color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun Txt(text: AnnotatedString, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    BasicText(text, modifier, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Press feedback shared by every tappable thing: a quick, springy 2% shrink, no ripple. */
@Composable
fun Modifier.tappable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val down by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (down && enabled) 0.97f else 1f, spring(dampingRatio = 0.6f, stiffness = 900f), label = "press")
    return this
        .scale(s)
        .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
}

enum class Ic { Up, Down, X, Check, Gear, Copy, Plus, Text, Image, Back }

/** Tiny stroked icons, drawn with paths so the app needs no icon library. */
@Composable
fun Icon(kind: Ic, tint: Color, size: Dp = 18.dp, width: Float = 1.7f) {
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 24f
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        val st = Stroke(width = width * u * 1.15f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, p(x1, y1), p(x2, y2), st.width, StrokeCap.Round)
        when (kind) {
            Ic.Up -> { line(12f, 19f, 12f, 5f); line(6f, 11f, 12f, 5f); line(18f, 11f, 12f, 5f) }
            Ic.Down -> { line(12f, 5f, 12f, 19f); line(6f, 13f, 12f, 19f); line(18f, 13f, 12f, 19f) }
            Ic.X -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            Ic.Check -> { line(5f, 12.5f, 10f, 17.5f); line(10f, 17.5f, 19f, 7f) }
            Ic.Plus -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            Ic.Back -> { line(19f, 12f, 5f, 12f); line(11f, 6f, 5f, 12f); line(11f, 18f, 5f, 12f) }
            Ic.Text -> { line(5f, 7f, 19f, 7f); line(12f, 7f, 12f, 19f); line(9f, 19f, 15f, 19f) }
            Ic.Image -> {
                drawRoundRect(tint, p(4f, 5f), androidx.compose.ui.geometry.Size(16f * u, 14f * u), androidx.compose.ui.geometry.CornerRadius(2.5f * u), style = st)
                drawCircle(tint, 1.6f * u, p(9f, 10f), style = st)
                line(4.5f, 17f, 10f, 13f); line(10f, 13f, 14f, 16f); line(14f, 16f, 19.5f, 11.5f)
            }
            Ic.Copy -> {
                drawRoundRect(tint, p(8.5f, 8.5f), androidx.compose.ui.geometry.Size(11f * u, 11f * u), androidx.compose.ui.geometry.CornerRadius(2.5f * u), style = st)
                val path = Path().apply { moveTo(p(15.5f, 8.5f).x, p(15.5f, 8.5f).y); lineTo(p(15.5f, 6.5f).x, p(15.5f, 6.5f).y); lineTo(p(6.5f, 6.5f).x, p(6.5f, 6.5f).y); lineTo(p(6.5f, 15.5f).x, p(6.5f, 15.5f).y); lineTo(p(8.5f, 15.5f).x, p(8.5f, 15.5f).y) }
                drawPath(path, tint, style = st)
            }
            Ic.Gear -> {
                drawCircle(tint, 3.2f * u, p(12f, 12f), style = st)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val c = Math.cos(a).toFloat(); val s = Math.sin(a).toFloat()
                    line(12f + c * 6.6f, 12f + s * 6.6f, 12f + c * 9f, 12f + s * 9f)
                }
                drawCircle(tint, 6.6f * u, p(12f, 12f), style = Stroke(width = st.width, cap = StrokeCap.Round))
            }
        }
    }
}

/** The one bright surface: a small white button with black text. */
@Composable
fun PrimaryButton(label: String, modifier: Modifier = Modifier, icon: Ic? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val down by src.collectIsPressedAsState()
    val bg by animateColorAsState(if (down) C.pressed else C.ink, tween(80), label = "bg")
    val s by animateFloatAsState(if (down) 0.97f else 1f, spring(dampingRatio = 0.6f, stiffness = 900f), label = "s")
    Row(
        modifier
            .scale(s)
            .alpha(if (enabled) 1f else 0.35f)
            .height(44.dp)
            .clip(RoundedCornerShape(R12.md.dp))
            .background(bg)
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, C.canvas, 18.dp); Spacer(Modifier.width(8.dp)) }
        Txt(label, T.button, color = C.canvas, maxLines = 1)
    }
}

/** Secondary action: elevated surface, hairline border. */
@Composable
fun GhostButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: Ic? = null,
    enabled: Boolean = true,
    tint: Color = C.ink,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val down by src.collectIsPressedAsState()
    val bg by animateColorAsState(if (down) C.card else C.elevated, tween(80), label = "bg")
    val s by animateFloatAsState(if (down) 0.97f else 1f, spring(dampingRatio = 0.6f, stiffness = 900f), label = "s")
    Row(
        modifier
            .scale(s)
            .alpha(if (enabled) 1f else 0.35f)
            .height(if (compact) 34.dp else 44.dp)
            .clip(RoundedCornerShape(R12.md.dp))
            .background(bg)
            .border(1.dp, C.hairlineStrong, RoundedCornerShape(R12.md.dp))
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 12.dp else 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, tint, if (compact) 16.dp else 18.dp); Spacer(Modifier.width(8.dp)) }
        Txt(label, T.button, color = tint, maxLines = 1)
    }
}

@Composable
fun SendCard(
    modifier: Modifier = Modifier,
    bg: Color = C.card,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(R12.lg.dp))
            .background(bg)
            .border(1.dp, C.hairlineStrong, RoundedCornerShape(R12.lg.dp))
            .padding(padding),
    ) { content() }
}

@Composable
fun StatusDot(color: Color, pulse: Boolean = false, size: Dp = 8.dp) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(size).alpha(if (pulse) a else 1f).clip(CircleShape).background(color))
}

@Composable
fun Pill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier
            .clip(CircleShape)
            .background(C.elevated)
            .border(1.dp, C.hairline, CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) { content() }
}

/** One soft radial wash anchored at the top of the screen; the colour tells the state. */
@Composable
fun Glow(color: Color, modifier: Modifier = Modifier) {
    val c by animateColorAsState(color, tween(900), label = "glow")
    Canvas(modifier.fillMaxSize()) {
        drawRect(
            Brush.radialGradient(
                colors = listOf(c, Color.Transparent),
                center = Offset(size.width * 0.5f, -size.height * 0.02f),
                radius = size.width * 1.15f,
            ),
        )
    }
}

/** 2dp hairline track with a bright fill. */
@Composable
fun HairBar(progress: Float, fill: Color = C.ink, modifier: Modifier = Modifier) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(220), label = "bar")
    Box(modifier.fillMaxWidth().height(2.dp).clip(CircleShape).background(C.hairlineStrong)) {
        Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(fill))
    }
}

/** Full-screen overlays use this so a tap on an empty spot never reaches the screen underneath. */
fun Modifier.blockTouches(): Modifier = this.pointerInput(Unit) { detectTapGestures { } }
