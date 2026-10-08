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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.preview.AnimatedPreview
import ee.schimke.flexpress.compose.generated.HamburgWght
import ee.schimke.flexpress.compose.generated.HamburgWghtSlntStandalone
import ee.schimke.flexpress.compose.generated.HamburgWghtStandalone

// The generated sources in `generated`, drawn with no font at run time: through the library
// (HamburgWght) and standalone, with their own copy of the drawing.

/** Each generated composable at three weights: library, standalone, standalone with slant. */
@Preview(backgroundColor = 0xff000000, showBackground = true, widthDp = 420, heightDp = 150)
@Composable
fun GeneratedTextWeightsPreview() {
  Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    for (weight in listOf(100f, 400f, 1000f)) {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HamburgWght({ weight }, 24.sp, color = Color.White)
        HamburgWghtStandalone({ weight }, 24.sp, color = Color.White)
        HamburgWghtSlntStandalone({ weight }, { -10f }, 24.sp, color = Color.White)
      }
    }
  }
}

/** The library and standalone files, with one animated weight driving both. */
@Preview(backgroundColor = 0xff000000, showBackground = true, widthDp = 300, heightDp = 110)
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 100, showCurves = false)
@Composable
fun GeneratedTextWeightAnimatedPreview() {
  val weight by
    rememberInfiniteTransition(label = "wght")
      .animateFloat(
        initialValue = 100f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
        label = "wght",
      )
  Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    HamburgWght({ weight }, 32.sp, color = Color.White)
    HamburgWghtStandalone({ weight }, 32.sp, color = Color.White)
  }
}
