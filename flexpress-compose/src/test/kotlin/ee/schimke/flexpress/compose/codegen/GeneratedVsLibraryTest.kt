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

package ee.schimke.flexpress.compose.codegen

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import ee.schimke.flexpress.compose.VariableFontText
import ee.schimke.flexpress.compose.generated.HamburgWght
import ee.schimke.flexpress.compose.generated.HamburgWghtSlntStandalone
import ee.schimke.flexpress.compose.generated.HamburgWghtStandalone
import ee.schimke.flexpress.compose.robotoFlex
import ee.schimke.flexpress.outline
import kotlin.math.roundToInt
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A generated composable draws exactly the pixels [VariableFontText] draws from the same exact
 * outline worked out from the font, at several axis values: through the library, and standalone,
 * with the file's own copy of the evaluation and drawing.
 */
@Config(sdk = [35], qualifiers = "w400dp-h800dp-xhdpi")
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GeneratedVsLibraryTest {
  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  private val wghtOutline = robotoFlex.font.outline("Hamburg", listOf("wght"))
  private val wghtSlntOutline = robotoFlex.font.outline("Hamburg", listOf("wght", "slnt"))

  @Test
  fun generatedTextDrawsTheLibrarysPixels() {
    var wght by mutableFloatStateOf(400f)
    var slnt by mutableFloatStateOf(0f)
    val size = 40.sp
    val cases: List<Pair<String, @Composable () -> Unit>> =
      listOf(
        "library" to { VariableFontText(wghtOutline, mapOf("wght" to { wght }), size) },
        "generated" to { HamburgWght({ wght }, size) },
        "standalone" to { HamburgWghtStandalone({ wght }, size) },
        "library wght+slnt" to
          {
            VariableFontText(wghtSlntOutline, mapOf("wght" to { wght }, "slnt" to { slnt }), size)
          },
        "standalone wght+slnt" to { HamburgWghtSlntStandalone({ wght }, { slnt }, size) },
      )
    composeRule.setContent {
      Column(Modifier.background(Color.White)) {
        cases.forEach { (name, content) -> Box(Modifier.testTag(name)) { content() } }
      }
    }
    for ((w, s) in listOf(100f to 0f, 400f to -5f, 1000f to -10f)) {
      wght = w
      slnt = s
      composeRule.waitForIdle()
      val root = composeRule.activity.window.decorView
      val whole = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
      composeRule.runOnUiThread { root.draw(Canvas(whole)) }
      fun pixels(tag: String) = pixels(whole, tag)
      val library = pixels("library")
      assertWithMessage("the library draws text at wght $w")
        .that(library.drop(2).count { it != Color.White.toArgb() })
        .isGreaterThan(500)
      assertWithMessage("generated at wght $w").that(pixels("generated")).isEqualTo(library)
      assertWithMessage("standalone at wght $w").that(pixels("standalone")).isEqualTo(library)
      assertWithMessage("standalone wght+slnt at $w, $s")
        .that(pixels("standalone wght+slnt"))
        .isEqualTo(pixels("library wght+slnt"))
    }
  }

  /** The pixels of the node tagged [tag] in [whole], the window drawn, after its size. */
  private fun pixels(whole: Bitmap, tag: String): List<Int> {
    val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
    val bitmap =
      Bitmap.createBitmap(
        whole,
        bounds.left.roundToInt(),
        bounds.top.roundToInt(),
        bounds.width.roundToInt(),
        bounds.height.roundToInt(),
      )
    val out = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(out, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return listOf(bitmap.width, bitmap.height) + out.toList()
  }
}
