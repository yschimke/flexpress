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
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [35], qualifiers = "w600dp-h1000dp-xhdpi")
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VariableFontTextWarpTest {
  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun circleWarpPutsTheBaselineOnTheCircleAndHeightOnTheRadius() {
    val warp =
      CircleWarp().apply {
        centerX = 100f
        centerY = 100f
        radius = 50f
        anchorRad = (-PI / 2).toFloat()
      }
    warp.map(0f, 0f)
    assertNear(warp.x, warp.y, 100f, 50f) // 12 o'clock
    warp.map(0f, 10f)
    assertNear(warp.x, warp.y, 100f, 40f) // further out, up the screen
    warp.map((50f * PI / 2).toFloat(), 0f)
    assertNear(warp.x, warp.y, 150f, 100f) // a quarter turn clockwise: 3 o'clock
    warp.clockwise = false
    warp.anchorRad = (PI / 2).toFloat()
    warp.map(0f, 10f)
    assertNear(warp.x, warp.y, 100f, 140f) // 6 o'clock, height toward the center
    warp.map((50f * PI / 2).toFloat(), 0f)
    assertNear(warp.x, warp.y, 150f, 100f) // reading left to right along the bottom
  }

  @Test
  fun pathWarpAlongAStraightLineIsATranslation() {
    val path =
      Path().apply {
        moveTo(10f, 80f)
        lineTo(310f, 80f)
      }
    val warp = PathWarp(path).apply { offsetPx = 0f }
    assertThat(warp.length).isWithin(0.5f).of(300f)
    for (u in listOf(0f, 37.5f, 150f, 299f, 340f, -20f)) {
      for (v in listOf(0f, 12f, -5f)) {
        warp.map(u, v)
        assertNear(warp.x, warp.y, 10f + u, 80f - v)
      }
    }
  }

  @Test
  fun closedPathWarpWrapsAround() {
    val path =
      Path().apply {
        moveTo(0f, 0f)
        lineTo(100f, 0f)
        lineTo(100f, 100f)
        lineTo(0f, 100f)
        close()
      }
    val warp = PathWarp(path)
    warp.map(10f, 0f)
    val x = warp.x
    val y = warp.y
    warp.map(10f + warp.length, 0f)
    assertNear(warp.x, warp.y, x, y)
  }

  @Test
  fun warpAndEmbossEffectsRenderAndAnimate() {
    var t by mutableFloatStateOf(0f)
    val font = robotoFlex.font
    val tags = listOf("curved", "curvedCcw", "pathWarp", "raised", "pressed")
    val path =
      Path().apply {
        moveTo(0f, 60f)
        cubicTo(80f, -20f, 160f, 140f, 300f, 40f)
      }
    composeRule.setContent {
      Column(Modifier.background(Color.Black)) {
        VariableFontCurvedText(
          text = "CURVED",
          font = font,
          axes = mapOf("wght" to { 400f + 500f * t }),
          fontSize = 18.sp,
          radius = 50.dp,
          anchorAngleDegrees = { -90f + 90f * t },
          modifier = Modifier.testTag(tags[0]),
        )
        VariableFontCurvedText(
          text = "UNDER",
          font = font,
          axes = mapOf("wght" to { 700f }),
          fontSize = 18.sp,
          radius = 50.dp,
          anchorAngleDegrees = { 90f - 45f * t },
          direction = CurvedTextDirection.CounterClockwise,
          modifier = Modifier.testTag(tags[1]),
        )
        VariableFontPathWarpText(
          text = "ALONG",
          font = font,
          axes = mapOf("wght" to { 700f }),
          fontSize = 18.sp,
          path = path,
          offset = { 0.4f * t },
          modifier = Modifier.testTag(tags[2]),
        )
        VariableFontEmbossText(
          text = "RAISED",
          font = font,
          axes = mapOf("wght" to { 800f }),
          fontSize = 22.sp,
          lightAngleDegrees = { -135f + 180f * t },
          modifier = Modifier.testTag(tags[3]),
        )
        VariableFontEmbossText(
          text = "PRESSED",
          font = font,
          axes = mapOf("wght" to { 800f }),
          fontSize = 22.sp,
          style = EmbossStyle.Pressed,
          lightAngleDegrees = { -135f + 180f * t },
          modifier = Modifier.testTag(tags[4]),
        )
      }
    }
    composeRule.waitForIdle()
    val before = tags.map { capture(it) }
    for (b in before) assertThat(ink(b)).isGreaterThan(50f)
    t = 1f
    composeRule.waitForIdle()
    val after = tags.map { capture(it) }
    for (i in tags.indices) {
      assertThat(ink(after[i])).isGreaterThan(50f)
      assertThat(after[i].sameAs(before[i])).isFalse()
    }
  }

  @Test
  fun curvedTextBoxSurroundsTheCircle() {
    val font = robotoFlex.font
    composeRule.setContent {
      VariableFontCurvedText(
        text = "SIZE",
        font = font,
        axes = mapOf("wght" to { 400f }),
        fontSize = 20.sp,
        radius = 40.dp,
        modifier = Modifier.testTag("box"),
      )
    }
    val bounds = composeRule.onNodeWithTag("box").fetchSemanticsNode().boundsInWindow
    assertThat(abs(bounds.width - bounds.height)).isLessThan(1f)
    // 2 x (radius + the larger of ascender and descender) at xhdpi.
    assertThat(bounds.width).isGreaterThan(2f * 40f * 2f)
  }

  private fun assertNear(x: Float, y: Float, ex: Float, ey: Float) {
    assertThat(hypot(x - ex, y - ey)).isLessThan(0.05f)
  }

  private fun capture(tag: String): Bitmap {
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
