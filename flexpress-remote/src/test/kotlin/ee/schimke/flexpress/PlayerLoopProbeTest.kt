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
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.remote.creation.compose.capture.rememberRemoteDocument
import androidx.compose.remote.creation.compose.layout.RemoteCanvas
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteSize
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.RemoteBitmapFont
import androidx.compose.remote.creation.compose.state.RemotePaint
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.min
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlin.math.roundToInt
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pins down the player behaviour the RemoteString effects are built on: inside a `loop`, a
 * substring at the loop index, a bitmap-font measure of it and expressions of the index are
 * re-evaluated on every iteration. A substring past the end of the text throws on the player (it
 * draws its error screen), so both ends are clamped to the length.
 */
@Config(sdk = [35], qualifiers = "w400dp-h400dp-mdpi")
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerLoopProbeTest {
  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  private var text by mutableStateOf("")

  @SuppressLint("RestrictedApi")
  @Test
  fun loopBodiesReadTheirIndexTextAndMeasures() {
    val pixel = ImageBitmap(1, 1)
    // 'a' is 1 wide, 'b' 2, 'c' 3: a bar's height says which character a slot read.
    val font =
      RemoteBitmapFont(
        "abc"
          .mapIndexed { i, c ->
            RemoteBitmapFont.Glyph(c.toString(), pixel, 0, 0, i.toShort(), 0, 1, 1)
          },
        emptyMap(),
      )
    composeRule.setContent {
      val doc = rememberRemoteDocument {
        RemoteCanvas(RemoteModifier.size(400.rdp)) {
          val text = RemoteString.createNamedRemoteString("text", "")
          val paint = RemotePaint { color = Color.White.rc }
          drawRect(RemotePaint { color = Color.Black.rc })
          // Control: a constant bar outside any loop, at the bottom.
          drawRect(paint, RemoteOffset(0f.rf, 380f.rf), RemoteSize(400f.rf, 20f.rf))
          loop(0f.rf, 8f.rf, 1f.rf) { i ->
            // A substring past the end throws on the player: clamp both ends to the length.
            val n = text.length
            val ch = text.substring(min(n, i.toRemoteInt()), min(n, (i + 1f).toRemoteInt()))
            val w = font.measureWidth(ch, 0f.rf)
            val left = i * 40f + 10f
            // A constant bar per slot, and one as tall as the character's width.
            drawRect(paint, RemoteOffset(left, 300f.rf), RemoteSize(20f.rf, 20f.rf))
            drawRect(paint, RemoteOffset(left, 200f.rf - w * 40f), RemoteSize(20f.rf, w * 40f))
          }
        }
      }
      Box(Modifier.size(400.dp, 400.dp).testTag("p")) {
        doc.value?.let {
          RemoteDocumentPlayer(
            it,
            400,
            400,
            Modifier.size(400.dp, 400.dp),
            update = { p -> p.setUserLocalString("text", text) },
          )
        }
      }
    }
    for ((t, expected) in
      listOf("abc" to listOf(1, 2, 3), "cab" to listOf(3, 1, 2), "b" to listOf(2))) {
      text = t
      val b = settled()
      val heights = (0 until 8).map { slot -> barHeight(b, 10 + slot * 40 + 10) }
      println("'$t' -> $heights")
      // The constant bar drawn in every iteration, whatever the text.
      for (slot in 0 until 8) assertThat(b.getPixel(20 + slot * 40, 310) and 0xff)
        .isGreaterThan(128)
      assertThat(heights.take(expected.size)).isEqualTo(expected.map { it * 40 })
      assertThat(heights.drop(expected.size).all { it == 0 }).isTrue()
    }
  }

  private fun barHeight(b: Bitmap, x: Int): Int =
    (0 until 200).count { y -> (b.getPixel(x, y) shr 16 and 0xff) > 128 }

  private fun settled(): Bitmap {
    var last: Bitmap? = null
    repeat(20) {
      composeRule.mainClock.advanceTimeByFrame()
      composeRule.waitForIdle()
      val now = capture()
      if (last?.sameAs(now) == true) return now
      last = now
    }
    return last!!
  }

  private fun capture(): Bitmap {
    val root = composeRule.activity.window.decorView
    val whole = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
    composeRule.runOnUiThread { root.draw(Canvas(whole)) }
    val r = composeRule.onNodeWithTag("p").fetchSemanticsNode().boundsInWindow
    return Bitmap.createBitmap(
      whole,
      r.left.roundToInt(),
      r.top.roundToInt(),
      r.width.roundToInt(),
      r.height.roundToInt(),
    )
  }
}
