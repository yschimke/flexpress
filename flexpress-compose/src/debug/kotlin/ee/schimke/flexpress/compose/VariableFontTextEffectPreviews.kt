/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ee.schimke.flexpress.compose

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.preview.AnimatedPreview
import kotlin.math.PI
import kotlin.math.sin

@Preview(backgroundColor = 0xff0b0c10, showBackground = true, widthDp = 260, heightDp = 72)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 100, showCurves = false)
@Composable
fun Effect01RangeSelectorWaveAnimatedPreview() {
  val font = rememberVariableFont(R.raw.roboto_flex)
  val center by
    rememberInfiniteTransition(label = "rangeWave")
      .animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "center",
      )
  val brush = remember {
    Brush.horizontalGradient(
      listOf(Color(0xFF00F2FE), Color(0xFF4FACFE), Color(0xFFF093FB), Color(0xFFF5576C)),
    )
  }
  VariableFontRangeWaveText(
    text = "FLEXPRESS",
    font = font,
    baseAxes = mapOf("wght" to 200f, "wdth" to 65f, "slnt" to 0f),
    peakAxes = mapOf("wght" to 950f, "wdth" to 135f, "slnt" to -10f),
    selector = { RangeSelector(center = center, width = 0.38f, shape = RangeShape.Smooth) },
    fontSize = 28.sp,
    peakOffsetY = (-7).dp,
    peakScale = 1.15f,
    brush = brush,
    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
  )
}

@Preview(backgroundColor = 0xff0b0c10, showBackground = true, widthDp = 230, heightDp = 72)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 100, showCurves = false)
@Composable
fun Effect02TrimPathsWriteOnAnimatedPreview() {
  val font = rememberVariableFont(R.raw.google_sans_flex_wght_rond)
  val transition = rememberInfiniteTransition(label = "trimPaths")
  val progress by
    transition.animateFloat(
      initialValue = 0.08f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
      label = "trim",
    )
  val weight by
    transition.animateFloat(
      initialValue = 300f,
      targetValue = 900f,
      animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
      label = "wght",
    )
  val strokeBrush = remember {
    Brush.horizontalGradient(listOf(Color(0xFF00E5FF), Color(0xFFFF4081), Color(0xFFFFEA00)))
  }
  VariableFontTrimPathText(
    text = "Vector",
    font = font,
    axes = mapOf("wght" to { weight }, "ROND" to { 100f }),
    fontSize = 36.sp,
    endFraction = { progress },
    strokeWidth = 2.dp,
    strokeBrush = strokeBrush,
    fillBrush = strokeBrush,
    fillAlpha = { ((progress - 0.55f) / 0.45f).coerceIn(0f, 1f) * 0.35f },
    modifier = Modifier.padding(8.dp),
  )
}

@Preview(backgroundColor = 0xff0b0c10, showBackground = true, widthDp = 230, heightDp = 76)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 100, showCurves = false)
@Composable
fun Effect03WaveWarpLiquidAnimatedPreview() {
  val font = rememberVariableFont(R.raw.google_sans_flex_wght_rond)
  val transition = rememberInfiniteTransition(label = "waveWarp")
  val phase by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = (2.0 * PI).toFloat(),
      animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
      label = "phase",
    )
  val weight by
    transition.animateFloat(
      initialValue = 500f,
      targetValue = 950f,
      animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
      label = "wght",
    )
  val brush = remember {
    Brush.verticalGradient(listOf(Color(0xFF80FFDB), Color(0xFF48BFE3), Color(0xFF5E60CE)))
  }
  VariableFontWaveWarpText(
    text = "LIQUID",
    font = font,
    axes = mapOf("wght" to { weight }, "ROND" to { 100f }),
    fontSize = 36.sp,
    phase = { phase },
    amplitudeX = 2.dp,
    amplitudeY = 4.5.dp,
    wavelengthX = 48.dp,
    wavelengthY = 32.dp,
    brush = brush,
    modifier = Modifier.padding(8.dp),
  )
}

@Preview(backgroundColor = 0xff0b0c10, showBackground = true, widthDp = 250, heightDp = 82)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 100, showCurves = false)
@Composable
fun Effect04KineticCascadeBounceAnimatedPreview() {
  val font = rememberVariableFont(R.raw.roboto_flex)
  val progress by
    rememberInfiniteTransition(label = "cascade")
      .animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "progress",
      )
  val brush = remember {
    Brush.horizontalGradient(listOf(Color(0xFFFFD166), Color(0xFFEF476F), Color(0xFF118AB2)))
  }
  VariableFontKineticCascadeText(
    text = "BOUNCE",
    font = font,
    progress = { progress },
    fontSize = 32.sp,
    jumpHeight = 14.dp,
    squashAmount = 0.30f,
    brush = brush,
    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
  )
}

@Preview(backgroundColor = 0xff0b0c10, showBackground = true, widthDp = 230, heightDp = 78)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 100, showCurves = false)
@Composable
fun Effect05EchoMotionTrailAnimatedPreview() {
  val font = rememberVariableFont(R.raw.roboto_flex)
  val phase by
    rememberInfiniteTransition(label = "echo")
      .animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
      )
  VariableFontEchoText(
    text = "ECHO",
    font = font,
    axesAtLag =
      mapOf(
        "wght" to { lag -> 550f + 400f * sin(phase - lag * 0.45f) },
        "wdth" to { lag -> 100f + 35f * sin(phase - lag * 0.45f) },
        "slnt" to { lag -> -5f + 5f * sin(phase - lag * 0.45f) },
      ),
    fontSize = 36.sp,
    echoCount = 5,
    echoOffsetX = 3.5.dp,
    echoOffsetY = (-2.5).dp,
    modifier = Modifier.padding(8.dp),
  )
}

@Preview(backgroundColor = 0xff0b0c10, showBackground = true, widthDp = 240, heightDp = 72)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 100, showCurves = false)
@Composable
fun Effect06ChromaticGlitchAnimatedPreview() {
  val font = rememberVariableFont(R.raw.roboto_flex)
  val transition = rememberInfiniteTransition(label = "glitch")
  val time by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 2f,
      animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
      label = "time",
    )
  val weight by
    transition.animateFloat(
      initialValue = 400f,
      targetValue = 900f,
      animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
      label = "wght",
    )
  VariableFontGlitchText(
    text = "GLITCH",
    font = font,
    axes = mapOf("wght" to { weight }, "wdth" to { 110f }, "slnt" to { -5f }),
    fontSize = 36.sp,
    time = { time },
    maxRgbSplit = 4.5.dp,
    maxSliceShift = 7.dp,
    sliceCount = 4,
    modifier = Modifier.padding(8.dp),
  )
}

@Preview(backgroundColor = 0xff0b0c10, showBackground = true, widthDp = 250, heightDp = 72)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 100, showCurves = false)
@Composable
fun Effect07SpecularLightSweepAnimatedPreview() {
  val font = rememberVariableFont(R.raw.google_sans_flex_wght_rond)
  val transition = rememberInfiniteTransition(label = "shimmer")
  val sweep by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
      label = "sweep",
    )
  val weight by
    transition.animateFloat(
      initialValue = 400f,
      targetValue = 950f,
      animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
      label = "wght",
    )
  VariableFontShimmerText(
    text = "SHIMMER",
    font = font,
    axes = mapOf("wght" to { weight }, "ROND" to { 80f }),
    fontSize = 34.sp,
    sweepProgress = { sweep },
    modifier = Modifier.padding(8.dp),
  )
}

@Preview(backgroundColor = 0xff0b0c10, showBackground = true, widthDp = 240, heightDp = 76)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 100, showCurves = false)
@Composable
fun Effect08WigglyHandDrawnAnimatedPreview() {
  val font = rememberVariableFont(R.raw.roboto_flex)
  val timeSeconds by
    rememberInfiniteTransition(label = "wiggly")
      .animateFloat(
        initialValue = 0f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "time",
      )
  val brush = remember {
    Brush.horizontalGradient(listOf(Color(0xFFFFEA00), Color(0xFFFF9100), Color(0xFFFF3D00)))
  }
  VariableFontWigglyText(
    text = "SKETCH",
    font = font,
    baseAxes = mapOf("wght" to 600f, "wdth" to 105f, "slnt" to -4f),
    axisWiggle = mapOf("wght" to 320f, "wdth" to 32f, "slnt" to 5f),
    fontSize = 34.sp,
    timeSeconds = { timeSeconds },
    stepsPerSecond = 10f,
    offsetJitter = 2.5.dp,
    rotationJitterDegrees = 5f,
    vertexJitter = 0.8.dp,
    brush = brush,
    modifier = Modifier.padding(8.dp),
  )
}

@Preview(backgroundColor = 0xff0b0c10, showBackground = true, widthDp = 260, heightDp = 82)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 100, showCurves = false)
@Composable
fun Effect09TextOnPathWaveAnimatedPreview() {
  val font = rememberVariableFont(R.raw.roboto_flex)
  val transition = rememberInfiniteTransition(label = "pathWave")
  val phase by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = (2.0 * PI).toFloat(),
      animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
      label = "phase",
    )
  val weight by
    transition.animateFloat(
      initialValue = 300f,
      targetValue = 900f,
      animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
      label = "wght",
    )
  val brush = remember {
    Brush.horizontalGradient(listOf(Color(0xFF72EFDD), Color(0xFF5390D9), Color(0xFF7400B8)))
  }
  VariableFontPathText(
    text = "WAVELINE",
    font = font,
    axes = mapOf("wght" to { weight }, "wdth" to { 95f }),
    fontSize = 28.sp,
    phase = { phase },
    waveAmplitude = 7.dp,
    wavelength = 130.dp,
    brush = brush,
    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
  )
}

@Preview(backgroundColor = 0xff0b0c10, showBackground = true, widthDp = 220, heightDp = 80)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 100, showCurves = false)
@Composable
fun Effect10NeonGlowPulseAnimatedPreview() {
  val font = rememberVariableFont(R.raw.google_sans_flex_wght_rond)
  val transition = rememberInfiniteTransition(label = "neon")
  val pulse by
    transition.animateFloat(
      initialValue = 0.55f,
      targetValue = 1.25f,
      animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
      label = "pulse",
    )
  val weight by
    transition.animateFloat(
      initialValue = 350f,
      targetValue = 850f,
      animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
      label = "wght",
    )
  VariableFontNeonText(
    text = "NEON",
    font = font,
    axes = mapOf("wght" to { weight }, "ROND" to { 100f }),
    fontSize = 38.sp,
    glowIntensity = { pulse },
    glowColor = Color(0xFFFF007F),
    secondaryGlowColor = Color(0xFF00E5FF),
    modifier = Modifier.padding(8.dp),
  )
}
