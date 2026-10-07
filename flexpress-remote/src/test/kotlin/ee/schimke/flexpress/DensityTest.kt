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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.creation.compose.capture.LocalRemoteComposeCreationState
import androidx.compose.remote.creation.compose.capture.RemoteDensity
import androidx.compose.remote.creation.compose.capture.rememberRemoteDocument
import androidx.compose.remote.creation.compose.state.asRdp
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rememberNamedRemoteFloat
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A document made for a constant density folds the font size into a literal scale and simplifies
 * the outline for the pixels it covers; one that takes the player's density ([RemoteDensity.Host])
 * keeps both as expressions and the outline exact. The two draw the same.
 */
@Config(sdk = [35], qualifiers = "w600dp-h900dp-xhdpi")
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DensityTest {
  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  private val values = mutableStateMapOf("wght" to 400f)
  private val documents = mutableStateMapOf<String, CoreDocument>()

  @Test
  fun pixelSizeIsKnownOnlyForConstants() {
    val constant = RemoteDensity(2f.rf, 1f.rf)
    assertThat(constantPixelSize(22.dp.asRdp(), constant)).isEqualTo(44f)
    assertThat(constantPixelSize(22.dp.asRdp(), RemoteDensity.Host)).isNull()
  }

  @Test
  fun hostDensityDrawsTheSameAsConstant() {
    composeRule.setContent {
      Column(Modifier.background(Color.Black)) {
        Player(CONSTANT, host = false)
        Player(HOST, host = true)
      }
    }
    composeRule.waitUntil(20_000) { documents.size == 2 }
    val constant = DocumentStats.of(documents.getValue(CONSTANT))
    val host = DocumentStats.of(documents.getValue(HOST))
    println("constant density: $constant\nhost density:     $host")
    assertWithMessage("a constant density simplifies the outline")
      .that(constant.pathFloats)
      .isLessThan(host.pathFloats)

    for (w in listOf(100f, 400f, 1000f, 650f)) {
      values["wght"] = w
      val (c, h) = settled()
      assertThat(ink(c)).isGreaterThan(0L)
      assertWithMessage("at wght $w").that(maxDiff(c, h)).isAtMost(MAX_DIFF)
    }
  }

  @SuppressLint("RestrictedApi")
  @Composable
  private fun Player(tag: String, host: Boolean) {
    val doc = rememberRemoteDocument {
      if (host) LocalRemoteComposeCreationState.current.remoteDensity = RemoteDensity.Host
      val wght = rememberNamedRemoteFloat("axis.wght") { 400f.rf }
      RemoteVariableFontText(
        TEXT,
        testFonts[1].font,
        mapOf("wght" to wght),
        SIZE.dp.asRdp(),
        color = Color.White.rc,
      )
    }
    doc.value?.let { d -> LaunchedEffect(d) { documents[tag] = d } }
    Box(Modifier.size(WIDTH.dp, HEIGHT.dp).testTag(tag)) {
      doc.value?.let {
        RemoteDocumentPlayer(
          it,
          documentWidth = WIDTH,
          documentHeight = HEIGHT,
          modifier = Modifier.size(WIDTH.dp, HEIGHT.dp),
          update = { player -> player.setUserLocalFloat("axis.wght", values.getValue("wght")) },
        )
      }
    }
  }

  private fun settled(): Pair<Bitmap, Bitmap> {
    var last: Pair<Bitmap, Bitmap>? = null
    repeat(10) {
      composeRule.waitForIdle()
      val now = capture(CONSTANT) to capture(HOST)
      if (last != null && last!!.first.sameAs(now.first) && last!!.second.sameAs(now.second)) {
        return now
      }
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

  private fun maxDiff(a: Bitmap, b: Bitmap): Int =
    (0 until a.height).maxOf { y ->
      (0 until a.width).maxOf { x ->
        abs((a.getPixel(x, y) and 0xff) - (b.getPixel(x, y) and 0xff))
      }
    }

  private fun ink(b: Bitmap): Long =
    (0 until b.height).sumOf { y ->
      (0 until b.width).sumOf { x -> (b.getPixel(x, y) and 0xff).toLong() }
    }

  private companion object {
    const val CONSTANT = "constant"
    const val HOST = "host"
    const val TEXT = "Hamburg"
    const val SIZE = 22
    const val WIDTH = 160
    const val HEIGHT = 32
    const val MAX_DIFF = 72
  }
}
