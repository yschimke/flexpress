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

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlin.math.roundToInt
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [35], qualifiers = "w600dp-h1000dp-xhdpi")
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VariableFontTextEffectsTest {
  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun rangeSelectorShapesProduceExpectedWeights() {
    for (shape in RangeShape.entries) {
      val sel = RangeSelector(center = 0.5f, width = 0.5f, shape = shape, wrap = false)
      val wMid = sel.weight(2, 5)
      assertThat(wMid).isGreaterThan(0.4f)
      val outside = RangeSelector(center = 0f, width = 0.2f, shape = shape, wrap = false)
      assertThat(outside.weight(4, 5)).isEqualTo(0f)
    }
  }

  @Test
  fun allTenTextEffectsRenderNonEmptyPixelsAndAnimate() {
    var t by mutableFloatStateOf(0.25f)
    val font = robotoFlex.font
    val tags = (1..10).map { "effect$it" }
    composeRule.setContent {
      Column(Modifier.background(Color.Black)) {
        VariableFontRangeWaveText(
          text = "WAVE",
          font = font,
          baseAxes = mapOf("wght" to 200f, "wdth" to 75f),
          peakAxes = mapOf("wght" to 900f, "wdth" to 130f),
          selector = { RangeSelector(center = t, width = 0.45f) },
          fontSize = 22.sp,
          modifier = Modifier.testTag(tags[0]),
        )
        VariableFontTrimPathText(
          text = "TRIM",
          font = font,
          axes = mapOf("wght" to { 700f }),
          fontSize = 22.sp,
          endFraction = { 0.3f + 0.6f * t },
          modifier = Modifier.testTag(tags[1]),
        )
        VariableFontWaveWarpText(
          text = "WARP",
          font = font,
          axes = mapOf("wght" to { 750f }),
          fontSize = 22.sp,
          phase = { t * 6.28f },
          modifier = Modifier.testTag(tags[2]),
        )
        VariableFontKineticCascadeText(
          text = "HOP",
          font = font,
          progress = { t },
          fontSize = 22.sp,
          modifier = Modifier.testTag(tags[3]),
        )
        VariableFontEchoText(
          text = "ECHO",
          font = font,
          axesAtLag = mapOf("wght" to { lag -> 400f + lag * 80f }),
          fontSize = 22.sp,
          modifier = Modifier.testTag(tags[4]),
        )
        VariableFontGlitchText(
          text = "GLITCH",
          font = font,
          axes = mapOf("wght" to { 700f }),
          fontSize = 22.sp,
          time = { t },
          modifier = Modifier.testTag(tags[5]),
        )
        VariableFontShimmerText(
          text = "SHIMMER",
          font = font,
          axes = mapOf("wght" to { 800f }),
          fontSize = 22.sp,
          sweepProgress = { t },
          modifier = Modifier.testTag(tags[6]),
        )
        VariableFontWigglyText(
          text = "SKETCH",
          font = font,
          baseAxes = mapOf("wght" to 600f),
          axisWiggle = mapOf("wght" to 250f),
          fontSize = 22.sp,
          timeSeconds = { t },
          modifier = Modifier.testTag(tags[7]),
        )
        VariableFontPathText(
          text = "PATH",
          font = font,
          axes = mapOf("wght" to { 700f }),
          fontSize = 22.sp,
          phase = { t * 6.28f },
          modifier = Modifier.testTag(tags[8]),
        )
        Box {
          VariableFontNeonText(
            text = "NEON",
            font = font,
            axes = mapOf("wght" to { 700f }),
            fontSize = 22.sp,
            glowIntensity = { 0.8f + 0.4f * t },
            modifier = Modifier.testTag(tags[9]),
          )
        }
      }
    }
    composeRule.waitForIdle()
    for (tag in tags) {
      assertThat(ink(captureTag(tag))).isGreaterThan(50)
    }
    t = 0.75f
    composeRule.waitForIdle()
    for (tag in tags) {
      assertThat(ink(captureTag(tag))).isGreaterThan(50)
    }
  }

  private fun captureTag(tag: String): Bitmap {
    val root = composeRule.activity.window.decorView
    val whole = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
    composeRule.runOnUiThread { root.draw(Canvas(whole)) }
    val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
    return Bitmap.createBitmap(
      whole,
      bounds.left.roundToInt().coerceAtLeast(0),
      bounds.top.roundToInt().coerceAtLeast(0),
      bounds.width.roundToInt().coerceAtLeast(1),
      bounds.height.roundToInt().coerceAtLeast(1),
    )
  }
}
