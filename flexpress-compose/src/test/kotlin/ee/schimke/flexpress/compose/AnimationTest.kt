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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import ee.schimke.flexpress.VariableTextOutlineEvaluator
import kotlin.math.ceil
import kotlin.math.roundToInt
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Animating an axis redraws [VariableFontText] and does nothing else. */
@Config(sdk = [35], qualifiers = "w400dp-h200dp-xhdpi")
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnimationTest {
  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun anAxisChangeRedrawsWithoutRecompositionOrRelayout() {
    var weight by mutableFloatStateOf(100f)
    var compositions = 0
    var innerCompositions = 0
    var layouts = 0
    var draws = 0
    composeRule.setContent {
      SideEffect { compositions++ }
      Box(Modifier.background(Color.Black)) {
        VariableFontText(
          "Hello",
          robotoFlex.font,
          axes = mapOf("wght" to { weight }),
          fontSize = 28.sp,
          // Materialized each time VariableFontText itself recomposes.
          modifier =
            Modifier.composed {
                SideEffect { innerCompositions++ }
                Modifier
              }
              .layout { measurable, constraints ->
                layouts++
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
              }
              .drawWithContent {
                draws++
                drawContent()
              }
              .testTag("text"),
          color = Color.White,
        )
      }
    }
    composeRule.waitForIdle()
    val light = ink(capture())
    val before = listOf(compositions, innerCompositions, layouts)
    val drawsBefore = draws

    for (w in listOf(300f, 500f, 700f, 900f)) {
      weight = w
      composeRule.waitForIdle()
    }
    val bold = ink(capture())

    assertThat(listOf(compositions, innerCompositions, layouts)).isEqualTo(before)
    assertThat(draws).isGreaterThan(drawsBefore)
    assertThat(bold).isGreaterThan(light * 2)
  }

  @Test
  fun theBoxFitsTheWidestValueAndReportsTheBaseline() {
    val font = robotoFlex.font
    composeRule.setContent {
      Row {
        VariableFontText(
          "Hello",
          font,
          axes = mapOf("wght" to { 400f }, "wdth" to { 100f }),
          fontSize = 20.sp,
          modifier = Modifier.alignByBaseline().testTag("text"),
        )
        BasicText("Hello", Modifier.alignByBaseline().testTag("platform"))
      }
    }
    val node = composeRule.onNodeWithTag("text").fetchSemanticsNode()
    val scale = 40f / font.unitsPerEm // 20sp at 2x
    val widest =
      VariableTextOutlineEvaluator.exactOutline(font, "Hello", listOf("wght", "wdth")).let {
        VariableTextOutlineEvaluator(it).width
      }
    assertThat(node.size.width).isEqualTo(ceil(widest * scale).toInt())
    assertThat(node.size.height).isEqualTo(ceil((font.ascender - font.descender) * scale).toInt())
    val baseline = node.getAlignmentLinePosition(FirstBaseline)
    assertThat(baseline).isEqualTo((font.ascender * scale).roundToInt())
    val platform = composeRule.onNodeWithTag("platform").fetchSemanticsNode()
    // Aligned by baseline: both baselines at the same y in the row.
    assertThat(node.boundsInRoot.top + baseline)
      .isEqualTo(platform.boundsInRoot.top + platform.getAlignmentLinePosition(FirstBaseline))
    assertThat(node.config[SemanticsProperties.Text].single().text).isEqualTo("Hello")
  }

  private fun capture(): Bitmap {
    val root = composeRule.activity.window.decorView
    val whole = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
    composeRule.runOnUiThread { root.draw(Canvas(whole)) }
    val bounds = composeRule.onNodeWithTag("text").fetchSemanticsNode().boundsInWindow
    return Bitmap.createBitmap(
      whole,
      bounds.left.roundToInt(),
      bounds.top.roundToInt(),
      bounds.width.roundToInt(),
      bounds.height.roundToInt(),
    )
  }
}
