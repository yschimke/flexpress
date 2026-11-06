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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.preview.AnimatedPreview

/** A round watch face: the time's label bent over the top, a second line under the bottom. */
@Preview(backgroundColor = 0xff000000, showBackground = true, widthDp = 220, heightDp = 220)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 100, showCurves = false)
@Composable
fun WarpCurvedTextWatchFaceAnimatedPreview() {
  val font = rememberVariableFont(R.raw.roboto_flex)
  val weight by
    rememberInfiniteTransition(label = "curved")
      .animateFloat(
        initialValue = 200f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
        label = "wght",
      )
  val brush = remember {
    Brush.sweepGradient(
      listOf(Color(0xFFFFD166), Color(0xFFEF476F), Color(0xFF06D6A0), Color(0xFFFFD166)),
    )
  }
  Box(Modifier.fillMaxSize().background(Color(0xFF101418), CircleShape), Alignment.Center) {
    VariableFontCurvedText(
      text = "FLEXPRESS ON WEAR",
      font = font,
      axes = mapOf("wght" to { weight }, "wdth" to { 110f }),
      fontSize = 18.sp,
      radius = 86.dp,
      brush = brush,
    )
    VariableFontCurvedText(
      text = "curved without a shader",
      font = font,
      axes = mapOf("wght" to { 1100f - weight }),
      fontSize = 13.sp,
      radius = 84.dp,
      anchorAngleDegrees = { 90f },
      direction = CurvedTextDirection.CounterClockwise,
      color = Color(0xFFB0BEC5),
    )
  }
}

/** The text spinning around the circle while its weight breathes. */
@Preview(backgroundColor = 0xff0b0c10, showBackground = true, widthDp = 200, heightDp = 200)
@AnimatedPreview(durationMs = 3000, frameIntervalMs = 100, showCurves = false)
@Composable
fun WarpCurvedTextSpinAnimatedPreview() {
  val font = rememberVariableFont(R.raw.google_sans_flex_wght_rond)
  val transition = rememberInfiniteTransition(label = "spin")
  val angle by
    transition.animateFloat(
      initialValue = -90f,
      targetValue = 270f,
      animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart),
      label = "angle",
    )
  val weight by
    transition.animateFloat(
      initialValue = 300f,
      targetValue = 900f,
      animationSpec = infiniteRepeatable(tween(750, easing = LinearEasing), RepeatMode.Reverse),
      label = "wght",
    )
  Box(Modifier.fillMaxSize(), Alignment.Center) {
    VariableFontCurvedText(
      text = "AROUND • AND • AROUND •",
      font = font,
      axes = mapOf("wght" to { weight }, "ROND" to { 100f }),
      fontSize = 20.sp,
      radius = 62.dp,
      anchorAngleDegrees = { angle },
      color = Color(0xFF80DEEA),
    )
  }
}

/** Text running along an S-curve; wide glyphs bend with the path rather than sitting on it. */
@Preview(backgroundColor = 0xff0b0c10, showBackground = true, widthDp = 300, heightDp = 140)
@AnimatedPreview(durationMs = 3000, frameIntervalMs = 100, showCurves = false)
@Composable
fun WarpAlongPathAnimatedPreview() {
  val font = rememberVariableFont(R.raw.roboto_flex)
  val offset by
    rememberInfiniteTransition(label = "pathWarp")
      .animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart),
        label = "offset",
      )
  val density = LocalDensity.current
  val path =
    remember(density) {
      with(density) {
        Path().apply {
          moveTo(0f, 60.dp.toPx())
          cubicTo(
            70.dp.toPx(),
            -20.dp.toPx(),
            150.dp.toPx(),
            140.dp.toPx(),
            260.dp.toPx(),
            40.dp.toPx(),
          )
        }
      }
    }
  val brush = remember {
    Brush.horizontalGradient(listOf(Color(0xFF72EFDD), Color(0xFF5390D9), Color(0xFFB5179E)))
  }
  VariableFontPathWarpText(
    text = "BENDING ALONG A PATH",
    font = font,
    axes = mapOf("wght" to { 800f }, "wdth" to { 120f }),
    fontSize = 24.sp,
    path = path,
    offset = { offset * 0.6f - 0.1f },
    brush = brush,
    modifier = Modifier.padding(4.dp),
  )
}

/** Brushed aluminium letters on a slate surface, the light swinging round them. */
@Preview(backgroundColor = 0xff3a4048, showBackground = true, widthDp = 300, heightDp = 110)
@AnimatedPreview(durationMs = 4000, frameIntervalMs = 100, showCurves = false)
@Composable
fun EmbossRaisedAnimatedPreview() {
  val font = rememberVariableFont(R.raw.roboto_flex)
  val transition = rememberInfiniteTransition(label = "emboss")
  val light by
    transition.animateFloat(
      initialValue = -180f,
      targetValue = 180f,
      animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart),
      label = "light",
    )
  val weight by
    transition.animateFloat(
      initialValue = 500f,
      targetValue = 1000f,
      animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Reverse),
      label = "wght",
    )
  val metal = remember {
    Brush.verticalGradient(
      0f to Color(0xFFE6E9ED),
      0.45f to Color(0xFFB4BAC2),
      0.55f to Color(0xFFA3AAB3),
      1f to Color(0xFFD5D9DF),
    )
  }
  Box(
    Modifier.fillMaxSize()
      .background(Brush.verticalGradient(listOf(Color(0xFF4A515B), Color(0xFF2E333A)))),
    Alignment.Center,
  ) {
    VariableFontEmbossText(
      text = "Emboss",
      font = font,
      axes = mapOf("wght" to { weight }, "wdth" to { 115f }),
      fontSize = 52.sp,
      lightAngleDegrees = { light },
      depth = 2.5.dp,
      elevation = 4.dp,
      brush = metal,
    )
  }
}

/** Letterpress: the same text stamped into warm paper, lit from the top left. */
@Preview(backgroundColor = 0xffe9e2d4, showBackground = true, widthDp = 300, heightDp = 200)
@Composable
fun EmbossStylesPreview() {
  val font = rememberVariableFont(R.raw.fraunces)
  Column(
    Modifier.fillMaxSize().background(Color(0xFFE9E2D4)).padding(8.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    VariableFontEmbossText(
      text = "Pressed",
      font = font,
      axes = mapOf("wght" to { 800f }),
      fontSize = 44.sp,
      style = EmbossStyle.Pressed,
      depth = 2.dp,
      color = Color(0xFFD9D0BE),
      shadowColor = Color(0xFF3B2F1E),
      highlightColor = Color(0xFFFFFCF5),
    )
    VariableFontEmbossText(
      text = "Raised",
      font = font,
      axes = mapOf("wght" to { 800f }),
      fontSize = 44.sp,
      depth = 2.dp,
      elevation = 3.dp,
      color = Color(0xFFEDE6D8),
      shadowColor = Color(0xFF3B2F1E),
      highlightColor = Color(0xFFFFFCF5),
    )
  }
}
