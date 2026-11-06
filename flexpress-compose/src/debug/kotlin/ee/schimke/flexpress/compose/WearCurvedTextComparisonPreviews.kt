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

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.basicCurvedText
import androidx.wear.compose.material3.TimeSource
import androidx.wear.compose.material3.TimeText
import ee.schimke.composeai.preview.AnimatedPreview
import ee.schimke.flexpress.VariableFont

/*
 * Wear Compose's two ways of curving text, against VariableFontCurvedText, in the same font
 * (Roboto Flex at wght 700), size and screen:
 *
 * - TimeText draws curvedText: on API 34+ the platform's glyph path (Paint.getTextPath) with each
 *   segment warped as one cubic, keeping the line's length at half the optical height.
 * - Modifier.curveToEdge (Material 3 1.8 alphas, API 33+; ported in WearCurveToEdgeShader.kt) draws the content flat and resamples its pixels through an AGSL
 *   RuntimeShader in log-polar coordinates, so glyphs keep their proportions but shrink toward the
 *   center.
 * - VariableFontCurvedText warps the variable-font outline itself, keeping the length at the
 *   baseline, with each segment split and re-fitted as quadratics.
 *
 * Each overlay draws Wear in cyan and Flexpress in red, added: white is where they agree.
 */

private const val COMPARE_TEXT = "FLEXPRESS 10:09"
private val CompareFontSize: TextUnit = 18.sp
private const val COMPARE_WEIGHT = 700
private val Wear = Color(0xFF00FFFF)
private val Ours = Color(0xFFFF0000)

private object FixedTime : TimeSource {
  @Composable override fun currentTime(): String = "10:09"
}

@Composable
private fun rememberRobotoFlexFamily(weight: Int): FontFamily =
  remember(weight) {
    FontFamily(
      Font(
        R.raw.roboto_flex,
        weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
      ),
    )
  }

/** Wear's TimeText with [text] as its only content, filling the screen with no padding. */
@Composable
private fun WearTimeText(
  text: String,
  color: Color,
  modifier: Modifier = Modifier,
  warpOffset: CurvedTextStyle.WarpOffset = CurvedTextStyle.WarpOffset.Unspecified,
) {
  val family = rememberRobotoFlexFamily(COMPARE_WEIGHT)
  TimeText(
    modifier = modifier,
    maxSweepAngle = 180f,
    backgroundColor = Color.Transparent,
    timeSource = FixedTime,
    contentPadding = PaddingValues(0.dp),
  ) { _ ->
    basicCurvedText(text) {
      CurvedTextStyle(
        fontSize = CompareFontSize,
        fontFamily = family,
        fontWeight = FontWeight(COMPARE_WEIGHT),
        color = color,
        warpOffset = warpOffset,
        // CurvedTextStyle takes no font features: curvedText draws the font's default figures.
      )
    }
  }
}

/** The same text flat, curved by Wear's curveToEdge shader. */
@Composable
private fun WearCurveToEdgeText(text: String, color: Color, modifier: Modifier = Modifier) {
  val family = rememberRobotoFlexFamily(COMPARE_WEIGHT)
  val screenHeightPx =
    with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.roundToPx() }
  Box(modifier.fillMaxSize(), Alignment.TopCenter) {
    BasicText(
      text,
      // The shader needs API 33; below that the text stays flat.
      modifier =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          Modifier.wearCurveToEdge(maxSweepAngle = 180f, screenHeightPx = screenHeightPx)
        } else {
          Modifier
        },
      style =
        TextStyle(
          fontSize = CompareFontSize,
          fontFamily = family,
          fontWeight = FontWeight(COMPARE_WEIGHT),
          color = color,
          fontFeatureSettings = "tnum",
        ),
    )
  }
}

/**
 * VariableFontCurvedText on the baseline TimeText uses: the screen's radius less the font's
 * ascender, which is where curvedText puts its baseline (`outerRadius + fontMetrics.ascent`).
 */
@Composable
private fun FlexpressCurved(
  text: String,
  font: VariableFont,
  color: Color,
  screen: Dp = 192.dp,
  glyphScale: (Int) -> Float = { 1f },
  tabularFigures: Boolean = false,
) {
  val density = LocalDensity.current
  val radius =
    with(density) {
      val ascentPx = font.ascender.toFloat() / font.unitsPerEm * CompareFontSize.toPx()
      (screen / 2) - ascentPx.toDp()
    }
  Box(Modifier.fillMaxSize(), Alignment.Center) {
    VariableFontCurvedText(
      text = text,
      font = font,
      axes = mapOf("wght" to { COMPARE_WEIGHT.toFloat() }),
      fontSize = CompareFontSize,
      radius = radius,
      glyphScale = glyphScale,
      tabularFigures = tabularFigures,
      color = color,
    )
  }
}

@Composable
private fun WatchFace(content: @Composable () -> Unit) {
  Box(Modifier.fillMaxSize().background(Color.Black, CircleShape)) { content() }
}

@Preview(widthDp = 192, heightDp = 192, showBackground = true, backgroundColor = 0xff000000)
@Composable
fun CompareCurvedWearTimeTextPreview() {
  WatchFace { WearTimeText(COMPARE_TEXT, Color.White) }
}

@Preview(widthDp = 192, heightDp = 192, showBackground = true, backgroundColor = 0xff000000)
@Composable
fun CompareCurvedWearCurveToEdgeShaderPreview() {
  WatchFace { WearCurveToEdgeText(COMPARE_TEXT, Color.White) }
}

@Preview(widthDp = 192, heightDp = 192, showBackground = true, backgroundColor = 0xff000000)
@Composable
fun CompareCurvedFlexpressPreview() {
  val font = rememberVariableFont(R.raw.roboto_flex)
  WatchFace { FlexpressCurved(COMPARE_TEXT, font, Color.White) }
}

@Preview(widthDp = 192, heightDp = 192, showBackground = true, backgroundColor = 0xff000000)
@Composable
fun CompareCurvedOverlayTimeTextPreview() {
  val font = rememberVariableFont(R.raw.roboto_flex)
  WatchFace {
    Box(Modifier.fillMaxSize().graphicsLayer { blendMode = BlendMode.Plus }) {
      WearTimeText(COMPARE_TEXT, Wear)
    }
    Box(Modifier.fillMaxSize().graphicsLayer { blendMode = BlendMode.Plus }) {
      FlexpressCurved(COMPARE_TEXT, font, Ours)
    }
  }
}

@Preview(widthDp = 192, heightDp = 192, showBackground = true, backgroundColor = 0xff000000)
@Composable
fun CompareCurvedOverlayShaderPreview() {
  val font = rememberVariableFont(R.raw.roboto_flex)
  WatchFace {
    Box(Modifier.fillMaxSize().graphicsLayer { blendMode = BlendMode.Plus }) {
      WearCurveToEdgeText(COMPARE_TEXT, Wear)
    }
    Box(Modifier.fillMaxSize().graphicsLayer { blendMode = BlendMode.Plus }) {
      FlexpressCurved(COMPARE_TEXT, font, Ours)
    }
  }
}

/*
 * A clock running through the hour, a second every 400 ms, with tabular figures (Roboto Flex's are
 * tabular by default, so tabularFigures changes nothing here) so the seconds tick without the line
 * moving. At 10:00:00 the new leading 1 grows in
 * (glyphScale) and the line re-centers as it widens; as the loop resets to 9:59:55 it shrinks
 * away again. TimeText, given the same strings, can only jump.
 */

private const val CLOCK_LOOP_MS = 4000
private const val CLOCK_TICK_MS = 400
private const val CLOCK_START_SECONDS = 9 * 3600 + 59 * 60 + 55
private const val CLOCK_GROW_MS = 320f

private class ClockFrame(val text: String, val leading: Float)

private fun clockFrame(ms: Float): ClockFrame {
  val seconds = CLOCK_START_SECONDS + (ms / CLOCK_TICK_MS).toInt()
  val h = seconds / 3600
  val m = seconds / 60 % 60
  val s = seconds % 60
  val text = "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
  val leading =
    if (h < 10) {
      0f
    } else {
      val since = ms - (10 * 3600 - CLOCK_START_SECONDS) * CLOCK_TICK_MS
      val grow = smoothstep(since / CLOCK_GROW_MS)
      val shrink = 1f - smoothstep((ms - (CLOCK_LOOP_MS - CLOCK_GROW_MS)) / CLOCK_GROW_MS)
      minOf(grow, shrink)
    }
  return ClockFrame(text, leading)
}

private fun smoothstep(x: Float): Float {
  val t = x.coerceIn(0f, 1f)
  return t * t * (3f - 2f * t)
}

@Composable
private fun rememberClockMs(): Float {
  val ms by
    rememberInfiniteTransition(label = "clock")
      .animateFloat(
        initialValue = 0f,
        targetValue = CLOCK_LOOP_MS.toFloat() - 1f,
        animationSpec =
          infiniteRepeatable(tween(CLOCK_LOOP_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "ms",
      )
  return ms
}

@Preview(widthDp = 192, heightDp = 192, showBackground = true, backgroundColor = 0xff000000)
@AnimatedPreview(durationMs = 4000, frameIntervalMs = 80, showCurves = false)
@Composable
fun CurvedClockFlexpressAnimatedPreview() {
  val font = rememberVariableFont(R.raw.roboto_flex)
  val frame = clockFrame(rememberClockMs())
  // The leading hour digit is glyph 0 only once the hour has two digits.
  val twoDigitHour = frame.text.indexOf(':') == 2
  WatchFace {
    FlexpressCurved(
      frame.text,
      font,
      Color.White,
      glyphScale = { i -> if (twoDigitHour && i == 0) frame.leading else 1f },
      tabularFigures = true,
    )
  }
}

@Preview(widthDp = 192, heightDp = 192, showBackground = true, backgroundColor = 0xff000000)
@AnimatedPreview(durationMs = 4000, frameIntervalMs = 80, showCurves = false)
@Composable
fun CurvedClockWearTimeTextAnimatedPreview() {
  val frame = clockFrame(rememberClockMs())
  WatchFace { WearTimeText(frame.text, Color.White) }
}

/*
 * Adaptive tabular figures in a font whose figures are proportional, Google Sans Flex, while its
 * weight swings: on top as shaped, so the line twitches as the seconds change (a 1 is narrow);
 * underneath with tabularFigures, each digit centered in a cell as wide as the widest digit at the
 * current weight, so the line holds still between ticks and only breathes with the weight.
 */
@Preview(widthDp = 192, heightDp = 192, showBackground = true, backgroundColor = 0xff000000)
@AnimatedPreview(durationMs = 4000, frameIntervalMs = 80, showCurves = false)
@Composable
fun CurvedClockAdaptiveTabularAnimatedPreview() {
  val font = rememberVariableFont(R.raw.google_sans_flex_wght_rond)
  val ms = rememberClockMs()
  val frame = clockFrame(ms)
  val twoDigitHour = frame.text.indexOf(':') == 2
  val weight = 500f + 350f * kotlin.math.sin(ms / CLOCK_LOOP_MS * 2f * kotlin.math.PI.toFloat())
  val grow: (Int) -> Float = { i -> if (twoDigitHour && i == 0) frame.leading else 1f }
  WatchFace {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
      VariableFontCurvedText(
        text = frame.text,
        font = font,
        axes = mapOf("wght" to { weight }),
        fontSize = 20.sp,
        radius = 76.dp,
        glyphScale = grow,
        color = Color(0xFFFFAB91),
      )
      VariableFontCurvedText(
        text = frame.text,
        font = font,
        axes = mapOf("wght" to { weight }),
        fontSize = 20.sp,
        radius = 76.dp,
        anchorAngleDegrees = { 90f },
        direction = CurvedTextDirection.CounterClockwise,
        glyphScale = grow,
        tabularFigures = true,
        color = Color(0xFF80DEEA),
      )
    }
  }
}

/**
 * TimeText keeping the line's length at the baseline, as Flexpress does: the warps should agree.
 */
@Preview(widthDp = 192, heightDp = 192, showBackground = true, backgroundColor = 0xff000000)
@Composable
fun CompareCurvedOverlayTimeTextBaselinePreview() {
  val font = rememberVariableFont(R.raw.roboto_flex)
  WatchFace {
    Box(Modifier.fillMaxSize().graphicsLayer { blendMode = BlendMode.Plus }) {
      WearTimeText(COMPARE_TEXT, Wear, warpOffset = CurvedTextStyle.WarpOffset.Baseline)
    }
    Box(Modifier.fillMaxSize().graphicsLayer { blendMode = BlendMode.Plus }) {
      FlexpressCurved(COMPARE_TEXT, font, Ours)
    }
  }
}
