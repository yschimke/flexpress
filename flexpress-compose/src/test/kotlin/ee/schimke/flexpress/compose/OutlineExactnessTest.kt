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

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ee.schimke.flexpress.VariableTextOutline
import ee.schimke.flexpress.VariableTextOutlineEvaluator
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Test

/**
 * The model evaluated at any axis values must be the font instanced there: the same path commands,
 * and every point within a hundredth of a font unit of the static instance read straight from the
 * font's tables.
 */
class OutlineExactnessTest {

  @Test
  fun everyAxisAloneAtRandomValues() {
    val random = Random(7)
    for (testFont in testFonts) {
      for (axis in testFont.axes) {
        val fixed = randomLocation(testFont, testFont.axes - axis, random)
        check(testFont, listOf(axis), fixed, random, allowTween = true)
        check(testFont, listOf(axis), fixed, random, allowTween = false)
      }
    }
  }

  @Test
  fun severalAxesTogether() {
    val random = Random(13)
    for (testFont in testFonts) {
      val animated = testFont.axes.take(3)
      val fixed = randomLocation(testFont, testFont.axes - animated.toSet(), random)
      check(testFont, animated, fixed, random, allowTween = true)
    }
  }

  @Test
  fun oneAxisUsesKeyOutlinesWhenTheyAreSmaller() {
    val report = StringBuilder("font axis: coordinates terms tents keys\n")
    var keys = 0
    for (testFont in testFonts) {
      for (axis in testFont.axes) {
        val evaluator = evaluator(testFont, listOf(axis))
        report.appendLine(
          "$testFont $axis: ${evaluator.coordinateCount} ${evaluator.termCount} " +
            "${evaluator.tentCount} ${evaluator.isKeys}"
        )
        if (evaluator.isKeys) keys++
        assertThat(evaluator(testFont, listOf(axis), allowKeys = false).isKeys).isFalse()
      }
    }
    println(report)
    assertWithMessage(report.toString()).that(keys).isGreaterThan(0)
    assertThat(evaluator(googleSansFlex, listOf("wght", "ROND")).isKeys).isFalse()
  }

  @Test
  fun valuesOutsideTheRangeAreClamped() {
    val evaluator = evaluator(robotoFlex, listOf("wght"))
    val axis = robotoFlex.font.axes.first { it.tag == "wght" }
    val beyond = FloatArray(evaluator.coordinateCount)
    val atMax = FloatArray(evaluator.coordinateCount)
    evaluator.evaluate(floatArrayOf(axis.maxValue + 500f), beyond)
    evaluator.evaluate(floatArrayOf(axis.maxValue), atMax)
    assertThat(beyond.toList()).isEqualTo(atMax.toList())
  }

  /** An outline decoded from its string, with no font, evaluates exactly as the original. */
  @Test
  fun aDecodedOutlineEvaluatesTheSame() {
    for (allowKeys in listOf(true, false)) {
      val original = evaluator(robotoFlex, listOf("wght"), allowKeys = allowKeys)
      val decoded =
        VariableTextOutlineEvaluator(VariableTextOutline.decode(original.outline.encode()))
      assertThat(decoded.verbs.toList()).isEqualTo(original.verbs.toList())
      val a = FloatArray(original.coordinateCount)
      val b = FloatArray(decoded.coordinateCount)
      for (w in listOf(100f, 333f, 400f, 777f, 1000f)) {
        original.evaluate(floatArrayOf(w), a)
        decoded.evaluate(floatArrayOf(w), b)
        assertThat(b.toList()).isEqualTo(a.toList())
      }
    }
  }

  private fun evaluator(
    testFont: TestFont,
    animated: List<String>,
    fixed: Map<String, Float> = emptyMap(),
    allowKeys: Boolean = true,
  ) =
    VariableTextOutlineEvaluator(
      VariableTextOutlineEvaluator.exactOutline(
        testFont.font,
        TEXT,
        animated,
        fixed,
        allowKeys = allowKeys,
      )
    )

  private fun check(
    testFont: TestFont,
    animated: List<String>,
    fixed: Map<String, Float>,
    random: Random,
    allowTween: Boolean,
  ) {
    val font = testFont.font
    val evaluator = evaluator(testFont, animated, fixed, allowKeys = allowTween)
    val out = FloatArray(evaluator.coordinateCount)
    val axes = animated.map { tag -> font.axes.first { it.tag == tag } }
    val samples =
      List(SAMPLES) {
        axes.map { it.minValue + (it.maxValue - it.minValue) * random.nextFloat() }
      } + listOf(axes.map { it.minValue }, axes.map { it.defaultValue }, axes.map { it.maxValue })
    for (sample in samples) {
      val location = fixed + animated.zip(sample)
      val truth =
        VariableTextOutlineEvaluator.instanceCoordinates(
          font,
          TEXT,
          location,
          kerningLocation = fixed,
        )
      evaluator.evaluate(sample.toFloatArray(), out)
      val what = "$testFont $location (keys=${evaluator.isKeys})"
      assertWithMessage("coordinates of $what")
        .that(evaluator.coordinateCount)
        .isEqualTo(truth.size)
      var worst = 0f
      for (i in truth.indices) {
        // Float sums of a few hundred font units' worth of deltas: allow for rounding relative to
        // the coordinate's size (a long line reaches tens of thousands of units).
        val error = abs(out[i] - truth[i])
        worst = maxOf(worst, error / (1f + abs(truth[i]) * RELATIVE / TOLERANCE))
      }
      assertWithMessage("worst point error of $what").that(worst).isLessThan(TOLERANCE)
    }
  }

  private fun randomLocation(testFont: TestFont, tags: Collection<String>, random: Random) =
    tags.associateWith { tag ->
      val axis = testFont.font.axes.first { it.tag == tag }
      axis.minValue + (axis.maxValue - axis.minValue) * random.nextFloat()
    }

  private companion object {
    const val TEXT = "Hamburgefonstiv HOW 0123 &?"
    const val SAMPLES = 8
    const val TOLERANCE = 0.01f
    const val RELATIVE = 2e-6f
  }
}
