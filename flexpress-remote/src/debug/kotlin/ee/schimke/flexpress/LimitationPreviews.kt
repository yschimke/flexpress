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

@file:OptIn(ExperimentalTextApi::class, ExperimentalRemotePlayerApi::class)

package ee.schimke.flexpress

import android.annotation.SuppressLint
import androidx.annotation.RawRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.remote.creation.compose.capture.rememberRemoteDocument
import androidx.compose.remote.creation.compose.state.asRdp
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rememberNamedRemoteFloat
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.player.compose.ExperimentalRemotePlayerApi
import androidx.compose.remote.player.compose.RemoteComposePlayerFlags
import androidx.compose.remote.player.compose.embedded.RcPlayer
import androidx.compose.remote.player.compose.embedded.rememberRcPlayerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.preview.AnimatedPreview
import ee.schimke.flexpress.remote.R

// What flexpress gives up by drawing text as a path from one font, against Compose UI's own text,
// which re-instances the font with new variation settings on every frame of the same weight
// animation. Each is a limitation documented in the repository README. Top row: Compose `Text`,
// with `TextMotion.Animated`. Bottom row: `RemoteVariableFontText`, played by the embedded Compose
// player.

/**
 * Ligatures: Compose shapes "ffi" and "fj" with the font's `GSUB` ligatures; flexpress does not.
 */
@Preview(backgroundColor = 0xff000000, showBackground = true, widthDp = 300, heightDp = 200)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 200, showCurves = false)
@Composable
fun LimitationLigaturesPreview() {
  LimitationComparison("a => b != c", R.raw.fira_code, height = 70, size = 40, maxWeight = 700f)
}

/**
 * Font fallback: characters the font lacks are drawn by Compose from a system font and by flexpress
 * as the font's `.notdef` glyph.
 */
@Preview(backgroundColor = 0xff000000, showBackground = true, widthDp = 300, heightDp = 140)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 200, showCurves = false)
@Composable
fun LimitationFontFallbackPreview() {
  LimitationComparison("Café → 東京", R.raw.google_sans_flex_wght_rond)
}

/**
 * Complex scripts: both draw from the same Arabic-capable font, but Compose shapes the text right
 * to left with joined letter forms, while flexpress places each character's isolated glyph, left to
 * right.
 */
@Preview(backgroundColor = 0xff000000, showBackground = true, widthDp = 300, heightDp = 220)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 200, showCurves = false)
@Composable
fun LimitationComplexScriptPreview() {
  LimitationComparison("Hi مرحبا", R.raw.noto_sans_arabic, height = 90)
}

/** Single line: Compose wraps text to its width; flexpress draws one line and is clipped. */
@Preview(backgroundColor = 0xff000000, showBackground = true, widthDp = 300, heightDp = 200)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 200, showCurves = false)
@Composable
fun LimitationSingleLinePreview() {
  LimitationComparison("Compose wraps this onto a second line", R.raw.roboto_flex, height = 70)
}

/** [text] in [fontResId] with `wght` sweeping, in Compose `Text` above and flexpress below. */
@Composable
private fun LimitationComparison(
  text: String,
  @RawRes fontResId: Int,
  height: Int = 50,
  size: Int = SIZE,
  maxWeight: Float = 800f,
) {
  val transition = rememberInfiniteTransition(label = "Weight")
  val weight by
    transition.animateFloat(
      initialValue = 300f,
      targetValue = maxWeight,
      animationSpec =
        infiniteRepeatable(tween(2000, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
      label = "wght",
    )
  Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Label("Compose Text")
    BasicText(
      text,
      Modifier.size(WIDTH.dp, height.dp),
      style =
        TextStyle(
          color = Color.White,
          fontSize = size.sp,
          // Laid out for animation: no pixel snapping or hinting between frames.
          textMotion = TextMotion.Animated,
          fontFamily =
            FontFamily(
              Font(
                fontResId,
                variationSettings = FontVariation.Settings(FontVariation.weight(weight.toInt())),
              )
            ),
        ),
    )
    Label("flexpress, embedded player")
    EmbeddedVariableFontText(text, weight, fontResId, size, WIDTH, height)
  }
}

/**
 * A [RemoteVariableFontText] document with its `wght` axis the named float `axis.wght`, played by
 * the embedded Compose player (`RcPlayer`) with that float set to [weight]. One document serves
 * every frame; only the player's float changes.
 */
@SuppressLint("RestrictedApi")
@Composable
private fun EmbeddedVariableFontText(
  text: String,
  weight: Float,
  @RawRes fontResId: Int,
  size: Int,
  width: Int,
  height: Int,
) {
  val context = LocalContext.current
  val font = remember(fontResId) { context.variableFont(fontResId) }
  val doc = rememberRemoteDocument {
    val wght = rememberNamedRemoteFloat("axis.wght") { weight.rf }
    RemoteVariableFontText(
      text,
      font,
      mapOf("wght" to wght),
      size.dp.asRdp(),
      color = Color.White.rc,
    )
  }
  // The embedded player is behind a flag in this Remote Compose release.
  RemoteComposePlayerFlags.isEmbeddedPlayerEnabled = true
  doc.value?.let { document ->
    val state = rememberRcPlayerState(document)
    // Written after composition: a state write during composition would recompose it again.
    SideEffect { state.floatState("axis.wght").value = weight }
    RcPlayer(state, Modifier.size(width.dp, height.dp))
  }
}

@Composable
private fun Label(text: String) {
  BasicText(text, style = TextStyle(color = Color.Gray, fontSize = 10.sp))
}

private const val WIDTH = 280
private const val SIZE = 24
