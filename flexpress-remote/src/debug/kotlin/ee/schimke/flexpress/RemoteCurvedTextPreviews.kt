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

package ee.schimke.flexpress

import android.annotation.SuppressLint
import android.icu.text.DecimalFormat
import androidx.compose.foundation.layout.size
import androidx.compose.remote.creation.compose.layout.RemoteTime
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.asRdp
import androidx.compose.remote.creation.compose.state.cos
import androidx.compose.remote.creation.compose.state.floor
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.preview.AnimatedPreview
import ee.schimke.flexpress.remote.R

/** [value], a whole number from 0 to 99, as two digits. */
@SuppressLint("RestrictedApi")
private fun twoDigits(value: RemoteFloat): RemoteString {
  val digit = DecimalFormat("0")
  val ones = value % 10f
  return ((value - ones) / 10f).toRemoteString(digit) + ones.toRemoteString(digit)
}

/**
 * `h:mm:ss` for [seconds] since midnight, the hour unpadded, as a [RemoteString] the player builds:
 * the text a curved clock draws.
 */
@SuppressLint("RestrictedApi")
private fun clockText(seconds: RemoteFloat): RemoteString {
  val whole = floor(seconds)
  val hours = floor(whole / 3600f)
  val minutes = floor(whole / 60f) % 60f
  return hours.toRemoteString(DecimalFormat("0")) +
    ":" +
    twoDigits(minutes) +
    ":" +
    twoDigits(whole % 60f)
}

/**
 * A curved clock on a round watch, kept by the player: a second every 400 ms from 9:59:55, so each
 * four-second loop passes 10:00:00. The text is a `RemoteString` the document builds from its own
 * time; the figures are tabular, and the new leading 1 grows in as the hour gains a digit.
 */
@Preview(backgroundColor = 0xff000000, showBackground = true, widthDp = 192, heightDp = 192)
@AnimatedPreview(durationMs = 4000, frameIntervalMs = 100, showCurves = false)
@SuppressLint("RestrictedApi")
@Composable
fun RemoteCurvedClockAnimatedPreview() {
  val context = LocalContext.current
  val font = remember { context.variableFont(R.raw.roboto_flex) }
  SelfAnimatedDocumentPreview(
    Modifier.size(192.dp, 192.dp),
    documentWidth = 192,
    documentHeight = 192,
  ) {
    val seconds = RemoteTime().ContinuousSec() % 4f * 2.5f + (9 * 3600 + 59 * 60 + 55).toFloat()
    val axes = mapOf("wght" to sweep(400f, 800f, 4f))
    val glyphs = rememberVariableFontGlyphs(font, "0123456789:", axes)
    RemoteVariableFontCurvedText(
      text = clockText(seconds),
      maxLength = 8,
      glyphs = glyphs,
      fontSize = 20.dp.asRdp(),
      radius = 78.dp.asRdp(),
      tabularFigures = true,
      growLeadingGlyph = true,
      color = Color.White.rc,
    )
  }
}

private const val CLOCK_CHARACTERS = "0123456789:"

/** `mm:ss` from the document's own clock: a text the player changes every second. */
@SuppressLint("RestrictedApi")
private fun minutesSeconds(): RemoteString {
  val time = RemoteTime()
  return twoDigits(time.Minutes() % 60f) + ":" + twoDigits(time.Seconds() % 60f)
}

/** The player's seconds, looping every [period]. */
@SuppressLint("RestrictedApi")
private fun loopSeconds(period: Float): RemoteFloat = RemoteTime().ContinuousSec() % period

/** One RemoteString effect, self-animated by the player, in a [widthDp] by [heightDp] box. */
@Composable
private fun RemoteEffectPreview(
  widthDp: Int = 260,
  heightDp: Int = 90,
  content: @Composable (font: VariableFont) -> Unit,
) {
  val context = LocalContext.current
  val font = remember { context.variableFont(R.raw.roboto_flex) }
  SelfAnimatedDocumentPreview(
    Modifier.size(widthDp.dp, heightDp.dp),
    documentWidth = widthDp,
    documentHeight = heightDp,
  ) {
    content(font)
  }
}

@Preview(backgroundColor = 0xff0b0c10, showBackground = true, widthDp = 260, heightDp = 90)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 100, showCurves = false)
@SuppressLint("RestrictedApi")
@Composable
fun RemoteEffectBaselineWaveAnimatedPreview() = RemoteEffectPreview { font ->
  val glyphs =
    rememberVariableFontGlyphs(font, CLOCK_CHARACTERS, mapOf("wght" to sweep(300f, 900f, 2f)))
  RemoteVariableFontBaselineWaveText(
    minutesSeconds(),
    5,
    glyphs,
    34.dp.asRdp(),
    phase = loopSeconds(2f) * 3.1416f,
    wavelength = 120.dp,
    color = Color(0xFF72EFDD),
  )
}

@Preview(backgroundColor = 0xff0b0c10, showBackground = true, widthDp = 260, heightDp = 90)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 100, showCurves = false)
@SuppressLint("RestrictedApi")
@Composable
fun RemoteEffectWaveWarpAnimatedPreview() = RemoteEffectPreview { font ->
  val glyphs = rememberVariableFontGlyphs(font, CLOCK_CHARACTERS, mapOf("wght" to 800f.rf))
  RemoteVariableFontWaveWarpText(
    minutesSeconds(),
    5,
    glyphs,
    40.dp.asRdp(),
    phase = loopSeconds(2f) * 3.1416f,
    color = Color(0xFF4FACFE),
  )
}

@Preview(backgroundColor = 0xff0b0c10, showBackground = true, widthDp = 260, heightDp = 90)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 100, showCurves = false)
@SuppressLint("RestrictedApi")
@Composable
fun RemoteEffectRangeWaveAnimatedPreview() = RemoteEffectPreview { font ->
  RemoteVariableFontRangeWaveText(
    minutesSeconds(),
    CLOCK_CHARACTERS,
    5,
    font,
    baseLocation = mapOf("wght" to 200f, "wdth" to 65f),
    peakLocation = mapOf("wght" to 950f, "wdth" to 135f),
    center = loopSeconds(2f) / 2f,
    fontSize = 36.dp.asRdp(),
    color = Color(0xFFF093FB),
  )
}

@Preview(backgroundColor = 0xff0b0c10, showBackground = true, widthDp = 260, heightDp = 100)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 100, showCurves = false)
@SuppressLint("RestrictedApi")
@Composable
fun RemoteEffectKineticCascadeAnimatedPreview() =
  RemoteEffectPreview(heightDp = 100) { font ->
    RemoteVariableFontKineticCascadeText(
      minutesSeconds(),
      CLOCK_CHARACTERS,
      5,
      font,
      progress = loopSeconds(2f) / 2f,
      fontSize = 34.dp.asRdp(),
      color = Color(0xFFFFD166),
    )
  }

@Preview(backgroundColor = 0xff0b0c10, showBackground = true, widthDp = 260, heightDp = 90)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 100, showCurves = false)
@SuppressLint("RestrictedApi")
@Composable
fun RemoteEffectWigglyAnimatedPreview() = RemoteEffectPreview { font ->
  RemoteVariableFontWigglyText(
    minutesSeconds(),
    CLOCK_CHARACTERS,
    5,
    font,
    lowLocation = mapOf("wght" to 350f),
    highLocation = mapOf("wght" to 850f),
    timeSeconds = loopSeconds(2f),
    fontSize = 36.dp.asRdp(),
    color = Color(0xFFEF476F),
  )
}

@Preview(backgroundColor = 0xff0b0c10, showBackground = true, widthDp = 260, heightDp = 90)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 100, showCurves = false)
@SuppressLint("RestrictedApi")
@Composable
fun RemoteEffectTrimPathAnimatedPreview() = RemoteEffectPreview { font ->
  val glyphs = rememberVariableFontGlyphs(font, CLOCK_CHARACTERS, mapOf("wght" to 700f.rf))
  RemoteVariableFontTrimPathText(
    minutesSeconds(),
    5,
    glyphs,
    40.dp.asRdp(),
    endFraction = loopSeconds(2f) / 2f,
    fillAlpha = 0.15f,
    strokeColor = Color(0xFF00F2FE),
  )
}

@Preview(backgroundColor = 0xff0b0c10, showBackground = true, widthDp = 260, heightDp = 100)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 100, showCurves = false)
@SuppressLint("RestrictedApi")
@Composable
fun RemoteEffectEchoAnimatedPreview() =
  RemoteEffectPreview(heightDp = 100) { font ->
    // Each echo's weight lags the foreground's sweep by a fifth of a second more.
    val lags =
      (0..4).map { lag ->
        val t = (RemoteTime().ContinuousSec() - lag * 0.2f) % 2f
        val weight = (cos(t * 3.1416f) * -0.5f + 0.5f) * 600f + 300f
        rememberVariableFontGlyphs(font, CLOCK_CHARACTERS, mapOf("wght" to weight))
      }
    RemoteVariableFontEchoText(minutesSeconds(), 5, lags, 34.dp.asRdp())
  }

@Preview(backgroundColor = 0xff0b0c10, showBackground = true, widthDp = 260, heightDp = 90)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 100, showCurves = false)
@SuppressLint("RestrictedApi")
@Composable
fun RemoteEffectGlitchAnimatedPreview() = RemoteEffectPreview { font ->
  val glyphs = rememberVariableFontGlyphs(font, CLOCK_CHARACTERS, mapOf("wght" to 800f.rf))
  RemoteVariableFontGlitchText(minutesSeconds(), 5, glyphs, 40.dp.asRdp(), time = loopSeconds(2f))
}

@Preview(backgroundColor = 0xff0b0c10, showBackground = true, widthDp = 260, heightDp = 90)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 100, showCurves = false)
@SuppressLint("RestrictedApi")
@Composable
fun RemoteEffectShimmerAnimatedPreview() = RemoteEffectPreview { font ->
  val glyphs = rememberVariableFontGlyphs(font, CLOCK_CHARACTERS, mapOf("wght" to 900f.rf))
  RemoteVariableFontShimmerText(
    minutesSeconds(),
    5,
    glyphs,
    42.dp.asRdp(),
    sweepProgress = loopSeconds(2f) / 2f,
  )
}

@Preview(backgroundColor = 0xff0b0c10, showBackground = true, widthDp = 260, heightDp = 100)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 100, showCurves = false)
@SuppressLint("RestrictedApi")
@Composable
fun RemoteEffectNeonAnimatedPreview() =
  RemoteEffectPreview(heightDp = 100) { font ->
    val glyphs =
      rememberVariableFontGlyphs(font, CLOCK_CHARACTERS, mapOf("wght" to sweep(350f, 850f, 2f)))
    RemoteVariableFontNeonText(minutesSeconds(), 5, glyphs, 38.dp.asRdp())
  }

@Preview(backgroundColor = 0xff3a4048, showBackground = true, widthDp = 260, heightDp = 100)
@AnimatedPreview(durationMs = 4000, frameIntervalMs = 200, showCurves = false)
@SuppressLint("RestrictedApi")
@Composable
fun RemoteEffectEmbossAnimatedPreview() =
  RemoteEffectPreview(heightDp = 100) { font ->
    val glyphs =
      rememberVariableFontGlyphs(
        font,
        CLOCK_CHARACTERS,
        mapOf("wght" to 800f.rf, "wdth" to 115f.rf),
      )
    RemoteVariableFontEmbossText(
      minutesSeconds(),
      5,
      glyphs,
      46.dp.asRdp(),
      lightAngleDegrees = loopSeconds(4f) * 90f - 180f,
      depth = 2.dp,
      elevation = 3.dp,
    )
  }

@Preview(backgroundColor = 0xff0b0c10, showBackground = true, widthDp = 300, heightDp = 140)
@AnimatedPreview(durationMs = 3000, frameIntervalMs = 100, showCurves = false)
@SuppressLint("RestrictedApi")
@Composable
fun RemoteEffectPathWarpAnimatedPreview() =
  RemoteEffectPreview(widthDp = 300, heightDp = 140) { font ->
    val glyphs =
      rememberVariableFontGlyphs(
        font,
        CLOCK_CHARACTERS,
        mapOf("wght" to 800f.rf, "wdth" to 120f.rf),
      )
    val path = remember {
      androidx.compose.ui.graphics.Path().apply {
        moveTo(0f, 60f)
        cubicTo(70f, -20f, 150f, 140f, 260f, 40f)
      }
    }
    RemoteVariableFontPathWarpText(
      minutesSeconds(),
      5,
      glyphs,
      34.dp.asRdp(),
      path,
      offset = loopSeconds(3f) / 3f * 0.5f,
      color = Color(0xFFB5179E),
    )
  }
