package com.z.imab.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Colors for the "i" person artwork. */
data class ArtColors(
    val person: Color,
    val face: Color,
    val bill: Color = Color(0xFF43A047),
    val billEdge: Color = Color(0xFF2E7D32),
    val billMark: Color = Color(0xFFC8E6C9),
    val backgroundTop: Color,
    val backgroundBottom: Color,
) {
    companion object {
        val Light = ArtColors(
            person = Color(0xFF3E4C59),
            face = Color(0xFFF4F7FA),
            backgroundTop = Color(0xFFD6EAFB),
            backgroundBottom = Color(0xFFEEF1F4),
        )
        val Dark = ArtColors(
            person = Color(0xFFCFD8DC),
            face = Color(0xFF1B2530),
            backgroundTop = Color(0xFF1B2B3B),
            backgroundBottom = Color(0xFF121518),
        )
        /** App icon: slate person on light blue. */
        val Icon = ArtColors(
            person = Color(0xFF34495E),
            face = Color(0xFFE3F1FC),
            backgroundTop = Color(0xFFA9D3F5),
            backgroundBottom = Color(0xFF6FAEE3),
        )
    }
}

/** A dollar bill centered at [center], [width] wide, turned by [degrees]. */
fun DrawScope.drawBill(center: Offset, width: Float, degrees: Float, colors: ArtColors) {
    val h = width * 0.5f
    rotate(degrees, pivot = center) {
        val topLeft = Offset(center.x - width / 2, center.y - h / 2)
        drawRoundRect(colors.bill, topLeft, Size(width, h), CornerRadius(width * 0.06f))
        val inset = width * 0.07f
        drawRoundRect(
            colors.billEdge,
            Offset(topLeft.x + inset, topLeft.y + inset),
            Size(width - inset * 2, h - inset * 2),
            CornerRadius(width * 0.04f),
            style = Stroke(width * 0.035f),
        )
        drawCircle(colors.billMark, radius = h * 0.24f, center = center)
        drawCircle(colors.billEdge, radius = h * 0.24f, center = center, style = Stroke(width * 0.03f))
    }
}

/**
 * The IMAB mascot: a lowercase "i" whose dot is his head, waving bills overhead with legs out of
 * the bottom of the stem. [u] is the drawing unit (the whole figure is about 7.3u tall, 5u wide);
 * [top] is the top of his head. [wave] in -1..1 tilts the arms for the money-tossing motion.
 */
fun DrawScope.drawMoneyPerson(cx: Float, top: Float, u: Float, wave: Float, colors: ArtColors) {
    val limb = Stroke(width = u * 0.38f, cap = StrokeCap.Round, join = StrokeJoin.Round)

    // Legs and feet out of the bottom of the stem.
    val hip = top + 6.0f * u
    for (side in listOf(-1f, 1f)) {
        val leg = Path().apply {
            moveTo(cx + side * 0.3f * u, hip - 0.2f * u)
            lineTo(cx + side * 0.95f * u, top + 7.1f * u)
            lineTo(cx + side * 1.45f * u, top + 7.1f * u)
        }
        drawPath(leg, colors.person, style = limb)
    }

    // Arms raised, hands holding bills. One goes up while the other comes down.
    val shoulderY = top + 2.6f * u
    for (side in listOf(-1f, 1f)) {
        val lift = wave * side * 0.3f * u
        val hand = Offset(cx + side * 2.0f * u, top + 0.75f * u + lift)
        val arm = Path().apply {
            moveTo(cx + side * 0.5f * u, shoulderY)
            lineTo(cx + side * 1.45f * u, shoulderY - 0.35f * u + lift * 0.4f)
            lineTo(hand.x, hand.y)
        }
        drawPath(arm, colors.person, style = limb)
        drawBill(Offset(hand.x + side * 0.15f * u, hand.y - 0.45f * u), 1.6f * u, side * 18f + wave * 10f, colors)
        drawCircle(colors.person, radius = 0.26f * u, center = hand)
    }

    // The stem of the "i" is his body.
    drawRoundRect(
        colors.person,
        topLeft = Offset(cx - 0.7f * u, top + 2.0f * u),
        size = Size(1.4f * u, 4.0f * u),
        cornerRadius = CornerRadius(0.3f * u),
    )

    // The dot of the "i" is his head, with a happy face.
    val head = Offset(cx, top + 0.78f * u)
    drawCircle(colors.person, radius = 0.78f * u, center = head)
    drawCircle(colors.face, radius = 0.1f * u, center = Offset(head.x - 0.28f * u, head.y - 0.12f * u))
    drawCircle(colors.face, radius = 0.1f * u, center = Offset(head.x + 0.28f * u, head.y - 0.12f * u))
    drawArc(
        colors.face,
        startAngle = 20f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = Offset(head.x - 0.38f * u, head.y - 0.25f * u),
        size = Size(0.76f * u, 0.6f * u),
        style = Stroke(0.09f * u, cap = StrokeCap.Round),
    )
}

/** Square app icon artwork: the mascot on a light blue tile with a few bills around him. */
fun DrawScope.drawAppIcon(colors: ArtColors = ArtColors.Icon, background: Boolean = true) {
    val s = min(size.width, size.height)
    if (background) {
        drawRoundRect(
            Brush.verticalGradient(listOf(colors.backgroundTop, colors.backgroundBottom)),
            cornerRadius = CornerRadius(s * 0.22f),
            size = Size(s, s),
        )
    }
    val u = s / 10.5f
    drawBill(Offset(s * 0.18f, s * 0.62f), u * 1.3f, -28f, colors)
    drawBill(Offset(s * 0.83f, s * 0.56f), u * 1.3f, 24f, colors)
    drawBill(Offset(s * 0.24f, s * 0.86f), u * 1.1f, 12f, colors)
    drawBill(Offset(s * 0.78f, s * 0.84f), u * 1.1f, -16f, colors)
    drawMoneyPerson(s / 2, s * 0.11f, u, 0f, colors)
}

private class FallingBill(val x: Float, val speed: Float, val offset: Float, val size: Float, val phase: Float, val spin: Float)

/** Bills raining down over the whole area, positioned by [t] seconds. */
private fun DrawScope.drawMoneyRain(bills: List<FallingBill>, t: Float, colors: ArtColors, unit: Float) {
    val span = size.height * 1.25f
    for (b in bills) {
        val y = ((b.offset + t * b.speed) * size.height) % span - size.height * 0.12f
        val x = b.x * size.width + sin(t * 1.6f + b.phase) * size.width * 0.03f
        drawBill(Offset(x, y), unit * b.size, sin(t * b.spin + b.phase) * 40f, colors)
    }
}

/** "I Made A Budget" splash: the mascot making it rain. Tap to skip. */
@Composable
fun SplashScreen(onSkip: () -> Unit) {
    val colors = if (MaterialTheme.colorScheme.background.isDark()) ArtColors.Dark else ArtColors.Light
    val bills = remember {
        val r = Random(7)
        List(26) {
            FallingBill(
                x = r.nextFloat(),
                speed = 0.18f + r.nextFloat() * 0.22f,
                offset = r.nextFloat() * 1.25f,
                size = 0.9f + r.nextFloat() * 0.7f,
                phase = r.nextFloat() * 2 * PI.toFloat(),
                spin = 1.2f + r.nextFloat() * 1.6f,
            )
        }
    }
    val t by produceState(0f) {
        var start = -1L
        while (true) {
            withFrameNanos { now ->
                if (start < 0) start = now
                value = (now - start) / 1_000_000_000f
            }
        }
    }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val textAlpha by animateFloatAsState(if (shown) 1f else 0f, tween(700, delayMillis = 150))

    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onSkip),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(colors.backgroundTop, colors.backgroundBottom)))
            val u = min(size.width, size.height) / 14f
            // Rain behind and in front of him.
            drawMoneyRain(bills.subList(0, 14), t, colors, u * 0.8f)
            val figureTop = size.height * 0.45f - 4.8f * u
            drawMoneyPerson(size.width / 2, figureTop, u, sin(t * 5f), colors)
            drawMoneyRain(bills.subList(14, bills.size), t, colors, u)
        }
        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 64.dp).alpha(textAlpha),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "I Made A Budget",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "(IMAB)",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 6.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

private fun Color.isDark() = (red * 0.299f + green * 0.587f + blue * 0.114f) < 0.5f
