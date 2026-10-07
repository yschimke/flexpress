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
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import ee.schimke.flexpress.VariableTextOutlineEvaluator
import ee.schimke.flexpress.outline
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Frame cost of animating `wght` through [VALUES] distinct values: [VariableFontText] against
 * `BasicText` with a `FontFamily` re-created per value, each frame being "set the value, let
 * Compose do its work, draw the window into a bitmap". Robolectric on a JVM with CPU raster, so the
 * numbers are only meaningful relative to each other.
 *
 * Skipped unless the environment has `COMPOSE_VF_BENCH=1`:
 * ```
 * COMPOSE_VF_BENCH=1 ./gradlew :flexpress-compose:testDebugUnitTest --tests '*FrameCostBenchmarkTest*'
 * ```
 */
@Config(sdk = [35], qualifiers = "w600dp-h200dp-xhdpi")
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrameCostBenchmarkTest {
  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  private var mode by mutableStateOf(Mode.None)
  private var weight by mutableFloatStateOf(100f)

  private enum class Mode {
    None,
    VariableFontText,
    BasicText,
  }

  @Test
  fun animateWeight() {
    assumeTrue(System.getenv("COMPOSE_VF_BENCH") == "1")
    val testFont = robotoFlex
    composeRule.setContent {
      Box(Modifier.background(Color.Black)) {
        when (mode) {
          Mode.None -> {}
          Mode.VariableFontText ->
            VariableFontText(
              TEXT,
              testFont.font,
              axes = mapOf("wght" to { weight }),
              fontSize = SIZE.sp,
              color = Color.White,
            )
          Mode.BasicText -> Platform(testFont, weight)
        }
      }
    }
    val values = List(VALUES) { 100f + 900f * it / (VALUES - 1) }
    val rows = mutableListOf<String>()
    for (m in listOf(Mode.VariableFontText, Mode.BasicText)) {
      mode = m
      weight = 1f // Not among the values: the first frame below is a change.
      frame()
      // A warm-up over values the measured passes never use, so the JIT has seen every path.
      for (w in values) {
        weight = w + 0.5f
        frame()
      }
      val first = values.map { w -> timed { weight = w } }
      val second = values.map { w -> timed { weight = w } }
      rows += "| $m | %.2f | %.2f | %.2f |".format(first.average(), first.median(), second.median())
    }
    // The harness alone: a frame where nothing changed.
    val idle = values.map { timed {} }
    rows +=
      "| (no change) | %.2f | %.2f | %.2f |".format(idle.average(), idle.median(), idle.median())

    // The model alone: one evaluation into the coordinate array, and one path rebuild.
    val outlines =
      listOf(
        "keys" to VariableTextOutlineEvaluator.exactOutline(testFont.font, TEXT, listOf("wght")),
        "forms" to
          VariableTextOutlineEvaluator.exactOutline(
            testFont.font,
            TEXT,
            listOf("wght"),
            allowKeys = false,
          ),
        "simplified at ${SIZE * 2} px, as drawn" to
          testFont.font.outline(TEXT, listOf("wght"), pixelSize = SIZE * 2f),
      )
    val modelRows = outlines.map { (name, outline) ->
      val m = VariableTextOutlineEvaluator(outline)
      val renderer = VariableFontTextRenderer(m)
      val out = FloatArray(m.coordinateCount)
      val array = FloatArray(1)
      repeat(20_000) {
        array[0] = 100f + it % 900
        m.evaluate(array, out)
      }
      val evaluate =
        microseconds(20_000) {
          array[0] = 100f + it % 900 + 0.25f
          m.evaluate(array, out)
        }
      val rebuild =
        microseconds(5_000) {
          array[0] = 100f + it % 900 + 0.5f
          renderer.update(array, 0.03f)
        }
      "| $name | ${m.isKeys} | ${m.coordinateCount} | ${m.termCount} | " +
        "%.1f | %.1f |".format(evaluate, rebuild)
    }

    println(
      (listOf(
          "\"$TEXT\", ${testFont.name}, $SIZE sp at 2x, wght through $VALUES distinct values.",
          "",
          "| Composable | Mean ms/frame (new values) | Median ms/frame (new values) | " +
            "Median ms/frame (repeated values) |",
          "|---|---|---|---|",
        ) +
          rows +
          listOf(
            "",
            "| Outline | Keys | Coordinates | Terms | µs per evaluate | µs per evaluate + path rebuild |",
            "|---|---|---|---|---|---|",
          ) +
          modelRows)
        .joinToString("\n")
    )
  }

  @Composable
  private fun Platform(testFont: TestFont, weight: Float) {
    BasicText(
      TEXT,
      style =
        TextStyle(
          color = Color.White,
          fontSize = SIZE.sp,
          fontFamily =
            FontFamily(
              Font(
                testFont.file,
                variationSettings = FontVariation.Settings(FontVariation.Setting("wght", weight)),
              )
            ),
        ),
    )
  }

  private val bitmap by lazy {
    val root = composeRule.activity.window.decorView
    Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
  }

  private fun frame() {
    composeRule.waitForIdle()
    val root = composeRule.activity.window.decorView
    composeRule.runOnUiThread { root.draw(Canvas(bitmap)) }
  }

  private fun timed(change: () -> Unit): Double {
    val start = System.nanoTime()
    change()
    frame()
    return (System.nanoTime() - start) / 1e6
  }

  private fun microseconds(times: Int, block: (Int) -> Unit): Double {
    val start = System.nanoTime()
    for (i in 0 until times) block(i)
    return (System.nanoTime() - start) / 1e3 / times
  }

  private fun List<Double>.median(): Double = sorted()[size / 2]

  private companion object {
    const val TEXT = "Hamburgefonstiv"
    const val SIZE = 28
    const val VALUES = 60
  }
}
