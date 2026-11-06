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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.remote.creation.compose.capture.rememberRemoteDocument
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.asRdp
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import kotlin.math.roundToInt
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every RemoteString effect, its text set on the player: each draws ink, and draws something else
 * when the text changes. Ink is counted in the green channel, so the player's error screen (red, as
 * when a substring overruns the text or a frame exceeds the operation budget) counts as none.
 */
@Config(sdk = [35], qualifiers = "w400dp-h1600dp-mdpi")
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemoteTextEffectsTest {
  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  private var text by mutableStateOf("12:34")

  private val font = testFonts[1].font // Roboto Flex

  private val effects: List<Pair<String, @Composable () -> Unit>> =
    listOf(
      "curved" to
        {
          RemoteVariableFontCurvedText(
            live(),
            8,
            glyphs(),
            16.dp.asRdp(),
            radius = 30.dp.asRdp(),
            tabularFigures = true,
            growLeadingGlyph = true,
            color = Color.White.rc,
          )
        },
      "baselineWave" to
        {
          RemoteVariableFontBaselineWaveText(live(), 5, glyphs(), 18.dp.asRdp(), phase = 1f.rf)
        },
      "waveWarp" to
        {
          RemoteVariableFontWaveWarpText(live(), 5, glyphs(), 18.dp.asRdp(), phase = 1f.rf)
        },
      "pathWarp" to
        {
          val path =
            Path().apply {
              moveTo(0f, 20f)
              quadraticTo(60f, -10f, 120f, 20f)
            }
          RemoteVariableFontPathWarpText(live(), 5, glyphs(), 16.dp.asRdp(), path)
        },
      "rangeWave" to
        {
          RemoteVariableFontRangeWaveText(
            live(),
            CHARACTERS,
            5,
            font,
            mapOf("wght" to 200f),
            mapOf("wght" to 900f),
            center = 0.5f.rf,
            fontSize = 18.dp.asRdp(),
          )
        },
      "kineticCascade" to
        {
          RemoteVariableFontKineticCascadeText(
            live(),
            CHARACTERS,
            5,
            font,
            progress = 0.3f.rf,
            fontSize = 18.dp.asRdp(),
          )
        },
      "wiggly" to
        {
          RemoteVariableFontWigglyText(
            live(),
            CHARACTERS,
            5,
            font,
            mapOf("wght" to 300f),
            mapOf("wght" to 800f),
            timeSeconds = 0.5f.rf,
            fontSize = 18.dp.asRdp(),
          )
        },
      "trimPath" to
        {
          RemoteVariableFontTrimPathText(live(), 5, glyphs(), 18.dp.asRdp(), endFraction = 0.7f.rf)
        },
      "echo" to
        {
          RemoteVariableFontEchoText(live(), 5, listOf(glyphs(), glyphs(500f)), 18.dp.asRdp())
        },
      "glitch" to
        {
          RemoteVariableFontGlitchText(live(), 5, glyphs(), 18.dp.asRdp(), time = 0.4f.rf)
        },
      "shimmer" to
        {
          RemoteVariableFontShimmerText(live(), 5, glyphs(), 18.dp.asRdp(), sweepProgress = 0.5f.rf)
        },
      "neon" to { RemoteVariableFontNeonText(live(), 5, glyphs(), 18.dp.asRdp()) },
      "emboss" to
        {
          RemoteVariableFontEmbossText(
            live(),
            5,
            glyphs(),
            18.dp.asRdp(),
            lightAngleDegrees = (-135f).rf,
          )
        },
    )

  private fun live() = RemoteString.createNamedRemoteString("text", "")

  @Composable
  private fun glyphs(weight: Float = 700f) =
    rememberVariableFontGlyphs(font, CHARACTERS, mapOf("wght" to weight.rf * 1f))

  @Test
  fun everyEffectDrawsTheLiveTextAndFollowsIt() {
    composeRule.setContent { Column { for ((name, effect) in effects) Player(name) { effect() } } }
    val first = effects.associate { (name, _) -> name to settled(name) }
    text = "10:09"
    val second = effects.associate { (name, _) -> name to settled(name) }
    for ((name, _) in effects) {
      assertWithMessage("$name draws the text").that(ink(first.getValue(name))).isGreaterThan(200)
      assertWithMessage("$name draws the new text")
        .that(ink(second.getValue(name)))
        .isGreaterThan(200)
      assertWithMessage("$name follows the text")
        .that(first.getValue(name).sameAs(second.getValue(name)))
        .isFalse()
    }
  }

  @SuppressLint("RestrictedApi")
  @Composable
  private fun Player(tag: String, content: @Composable () -> Unit) {
    val doc = rememberRemoteDocument { content() }
    Box(Modifier.size(WIDTH.dp, HEIGHT.dp).testTag(tag)) {
      doc.value?.let {
        RemoteDocumentPlayer(
          it,
          WIDTH,
          HEIGHT,
          Modifier.size(WIDTH.dp, HEIGHT.dp),
          update = { p -> p.setUserLocalString("text", text) },
        )
      }
    }
  }

  private fun settled(tag: String): Bitmap {
    var last: Bitmap? = null
    repeat(10) {
      composeRule.mainClock.advanceTimeByFrame()
      composeRule.waitForIdle()
      val now = capture(tag)
      if (last?.sameAs(now) == true) return now
      last = now
    }
    return last!!
  }

  private fun capture(tag: String): Bitmap {
    val root = composeRule.activity.window.decorView
    val whole = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
    composeRule.runOnUiThread { root.draw(Canvas(whole)) }
    val b = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
    return Bitmap.createBitmap(
      whole,
      b.left.roundToInt(),
      b.top.roundToInt(),
      b.width.roundToInt(),
      b.height.roundToInt(),
    )
  }

  /** Green-channel ink above the player's light background is not counted: dark pixels only. */
  private fun ink(b: Bitmap): Int {
    var n = 0
    val background = b.getPixel(0, 0)
    for (y in 0 until b.height) {
      for (x in 0 until b.width) {
        val p = b.getPixel(x, y)
        if (p != background && (p shr 8 and 0xff) > 60) n++
      }
    }
    return n
  }

  private companion object {
    const val CHARACTERS = "0123456789:"
    const val WIDTH = 170
    const val HEIGHT = 110
  }
}
