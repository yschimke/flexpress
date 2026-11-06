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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TabularFiguresTest {
  private val digits = "0123456789"

  @Test
  fun hasFeatureReadsTheGsubFeatureList() {
    assertThat(interTnum.font.hasFeature("tnum")).isTrue()
    assertThat(interTnum.font.hasFeature("smcp")).isFalse()
    assertThat(googleSansFlex.font.hasFeature("tnum")).isFalse()
    assertThat(googleSansFlex.font.hasFeature("calt")).isTrue()
  }

  @Test
  fun tnumSubstitutesTabularDigitsAtEveryWeight() {
    val font = interTnum.font
    val proportional = font.glyphOutlines(digits, listOf("wght"))
    val tabular =
      font.glyphOutlines(
        digits,
        listOf("wght"),
        emptyMap(),
        emptyMap(),
        null,
        DEFAULT_TOLERANCE_PIXELS,
        true,
        setOf("tnum"),
      )
    assertThat(tabular.map { it.glyphId }).isNotEqualTo(proportional.map { it.glyphId })
    for (weight in listOf(100f, 400f, 900f)) {
      val p = proportional.map { advance(it, weight) }
      val t = tabular.map { advance(it, weight) }
      assertThat(p.max() - p.min()).isGreaterThan(50f)
      for (a in t) assertThat(a).isWithin(0.01f).of(t[0])
    }
  }

  @Test
  fun featuresAreCachedApart() {
    val font = interTnum.font
    val plain = font.cachedGlyphOutlines("1", listOf("wght"))
    val tnum =
      font.cachedGlyphOutlines(
        "1",
        listOf("wght"),
        emptyMap(),
        emptyMap(),
        null,
        DEFAULT_TOLERANCE_PIXELS,
        true,
        setOf("tnum"),
      )
    assertThat(tnum.single().glyphId).isNotEqualTo(plain.single().glyphId)
  }

  private fun advance(glyph: VariableGlyphOutline, weight: Float): Float {
    val evaluator = VariableTextOutlineEvaluator(glyph.advance)
    val out = FloatArray(evaluator.coordinateCount)
    evaluator.evaluate(floatArrayOf(weight), out)
    return out[0]
  }
}
