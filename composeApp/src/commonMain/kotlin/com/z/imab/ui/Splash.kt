package com.z.imab.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.z.imab.resources.Res
import com.z.imab.resources.nutcracker
import org.jetbrains.compose.resources.painterResource
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Colors for the splash screen: its background and the falling bills. */
data class ArtColors(
    val bill: Color = Color(0xFF43A047),
    val billEdge: Color = Color(0xFF2E7D32),
    val billMark: Color = Color(0xFFC8E6C9),
    val backgroundTop: Color,
    val backgroundBottom: Color,
) {
    companion object {
        val Light = ArtColors(
            backgroundTop = Color(0xFFD6EAFB),
            backgroundBottom = Color(0xFFEEF1F4),
        )
        val Dark = ArtColors(
            backgroundTop = Color(0xFF1B2B3B),
            backgroundBottom = Color(0xFF121518),
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

/** "Nutcracker" splash: the nutcracker with money raining around it. Tap to skip. */
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
        // Rain behind the nutcracker, the nutcracker bobbing gently, then rain in front of it.
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(colors.backgroundTop, colors.backgroundBottom)))
            drawMoneyRain(bills.subList(0, 14), t, colors, min(size.width, size.height) / 14f * 0.8f)
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val side = min(maxWidth.value, maxHeight.value * 0.62f).dp * 0.62f
            Image(
                painterResource(Res.drawable.nutcracker),
                contentDescription = "Nutcracker",
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = -maxHeight * 0.06f + (side * 0.02f) * sin(t * 2.2f))
                    .size(side)
                    .shadow(16.dp, RoundedCornerShape(side * 0.22f))
                    .clip(RoundedCornerShape(side * 0.22f)),
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            drawMoneyRain(bills.subList(14, bills.size), t, colors, min(size.width, size.height) / 14f)
        }
        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 64.dp).alpha(textAlpha),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Nutcracker",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Envelope Budget",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

private fun Color.isDark() = (red * 0.299f + green * 0.587f + blue * 0.114f) < 0.5f
