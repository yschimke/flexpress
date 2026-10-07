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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.preview.AnimatedPreview
import ee.schimke.flexpress.VariableFont

/**
 * Reads one of the repository's test fonts (`fonts/res/raw`, this module's debug resources), once
 * per composition.
 */
@Composable
internal fun rememberVariableFont(@RawRes resId: Int): VariableFont {
  val resources = LocalResources.current
  return remember(resId) {
    resources.openRawResource(resId).use { VariableFont.parse(it.readBytes()) }
  }
}

@Preview(backgroundColor = 0xff000000, showBackground = true, widthDp = 220, heightDp = 120)
@Composable
fun RobotoFlexWeightsPreview() {
  val font = rememberVariableFont(R.raw.roboto_flex)
  Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    for (weight in listOf(100f, 400f, 1000f)) {
      VariableFontText(
        "Hello",
        font,
        axes = mapOf("wght" to { weight }),
        fontSize = 24.sp,
        color = Color.White,
      )
    }
  }
}

@Preview(backgroundColor = 0xff000000, showBackground = true, widthDp = 200, heightDp = 60)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 100, showCurves = false)
@Composable
fun RobotoFlexWeightAnimatedPreview() {
  val font = rememberVariableFont(R.raw.roboto_flex)
  val weight by
    rememberInfiniteTransition(label = "wght")
      .animateFloat(
        initialValue = 100f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
        label = "wght",
      )
  VariableFontText(
    "Hello",
    font,
    axes = mapOf("wght" to { weight }),
    fontSize = 32.sp,
    modifier = Modifier.padding(8.dp),
    color = Color.White,
  )
}

@Preview(backgroundColor = 0xff000000, showBackground = true, widthDp = 200, heightDp = 60)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 100, showCurves = false)
@Composable
fun GoogleSansFlexWeightAndRoundnessAnimatedPreview() {
  val font = rememberVariableFont(R.raw.google_sans_flex_wght_rond)
  val transition = rememberInfiniteTransition(label = "axes")
  val weight by
    transition.animateFloat(
      initialValue = 100f,
      targetValue = 1000f,
      animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
      label = "wght",
    )
  val roundness by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 100f,
      animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Reverse),
      label = "ROND",
    )
  VariableFontText(
    "Hello",
    font,
    axes = mapOf("wght" to { weight }, "ROND" to { roundness }),
    fontSize = 32.sp,
    modifier = Modifier.padding(8.dp),
    color = Color.White,
  )
}

@Preview(backgroundColor = 0xff000000, showBackground = true, widthDp = 240, heightDp = 60)
@Composable
fun RobotoFlexWidthAndSlantPreview() {
  val font = rememberVariableFont(R.raw.roboto_flex)
  VariableFontText(
    "Condensed",
    font,
    axes = mapOf("wdth" to { 25f }),
    fontSize = 32.sp,
    modifier = Modifier.padding(8.dp),
    color = Color.White,
    location = mapOf("slnt" to -10f, "wght" to 700f),
  )
}
