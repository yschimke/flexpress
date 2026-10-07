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

@file:OptIn(ExperimentalTextApi::class)

package ee.schimke.flexpress

import androidx.annotation.RawRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.preview.AnimatedPreview
import ee.schimke.flexpress.remote.R

// What flexpress gives up by drawing text as a path from one font, against Compose UI's own text,
// which re-instances the font with new variation settings on every frame of the same weight
// animation. Each is a limitation documented in the repository README. Top row: Compose `Text`.
// Bottom row: `RemoteVariableFontText`.

/**
 * Ligatures: Compose shapes "ffi" and "fj" with the font's `GSUB` ligatures; flexpress does not.
 */
@Preview(backgroundColor = 0xff000000, showBackground = true, widthDp = 300, heightDp = 200)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 200, showCurves = false)
@Composable
fun LimitationLigaturesPreview() {
  LimitationComparison("office fluff", R.raw.fraunces_liga, height = 70, size = 48)
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
) {
  val transition = rememberInfiniteTransition(label = "Weight")
  val weight by
    transition.animateFloat(
      initialValue = 300f,
      targetValue = 800f,
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
          fontFamily =
            FontFamily(
              Font(
                fontResId,
                variationSettings = FontVariation.Settings(FontVariation.weight(weight.toInt())),
              )
            ),
        ),
    )
    Label("flexpress")
    Row {
      VariableFontTextPreview(
        text,
        mapOf("wght" to weight),
        Modifier.size(WIDTH.dp, height.dp),
        fontResId = fontResId,
        fontSize = size.dp,
        documentWidth = WIDTH,
        documentHeight = height,
      )
    }
  }
}

@Composable
private fun Label(text: String) {
  BasicText(text, style = TextStyle(color = Color.Gray, fontSize = 10.sp))
}

private const val WIDTH = 280
private const val SIZE = 24
