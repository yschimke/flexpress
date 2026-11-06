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
import com.google.common.truth.Truth.assertWithMessage
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Test

/** [VariableTextOutline] is the font's outline everywhere, and [encode] loses nothing. */
class VariableTextOutlineTest {

  @Test
  fun outlinesEqualTheFontEverywhere() {
    for ((testFont, axes) in cases) {
      val font = testFont.font
      for (allowKeys in listOf(true, false)) {
        val outline = font.outline(TEXT, axes, emptyMap(), emptyMap(), null, 0f, allowKeys)
        val random = Random(7)
        repeat(6) {
          val location = axes.associateWith { tag ->
            val a = font.axes.first { it.tag == tag }
            a.minValue + (a.maxValue - a.minValue) * random.nextFloat()
          }
          val truth = coordinates(font.layout(TEXT, location, emptyMap()))
          val ours = outline.evaluate(FloatArray(axes.size) { location.getValue(axes[it]) })
          assertThat(ours.size).isEqualTo(truth.size)
          val off = ours.indices.maxOf { abs(ours[it] - truth[it]) }
          assertWithMessage("$testFont $axes keys=$allowKeys at $location")
            .that(off)
            .isLessThan(0.02f)
        }
      }
    }
  }

  @Test
  fun encodingRoundTripsExactly() {
    for ((testFont, axes) in cases) {
      for (allowKeys in listOf(true, false)) {
        val outline =
          testFont.font.outline(TEXT, axes, emptyMap(), emptyMap(), 44f, 1f / 16, allowKeys)
        val encoded = outline.encode()
        val decoded = VariableTextOutline.decode(*encoded.chunked(1000).toTypedArray())
        assertWithMessage("$testFont $axes").that(decoded.encode()).isEqualTo(encoded)
        val values =
          FloatArray(axes.size) { i -> testFont.font.axes.first { it.tag == axes[i] }.maxValue }
        assertThat(decoded.evaluate(values).toList()).isEqualTo(outline.evaluate(values).toList())
      }
    }
  }

  /** Simplifying for a pixel size saves something; [SimplifiedOutlineTest] checks the pixels. */
  @Test
  fun simplifyingDropsTermsAndPoints() {
    val font = testFonts[1].font
    val exact = font.outline(TEXT, listOf("wght", "slnt"))
    val simplified = font.outline(TEXT, listOf("wght", "slnt"), pixelSize = 44f)
    println(
      "exact ${exact.encode().length} chars, simplified at 44 px ${simplified.encode().length}",
    )
    assertThat(simplified.verbs.size).isAtMost(exact.verbs.size)
    assertThat(simplified.encode().length).isLessThan(exact.encode().length)
  }

  @Test
  fun stepTentsMatchExactTentEvaluation() {
    val font = testFonts[1].font
    val wghtIndex = font.axes.indexOfFirst { it.tag == "wght" }
    val wghtAxis = font.axes[wghtIndex]
    val stepRight = Tent(wghtIndex, start = 0.5f, peak = 0.5f, end = 1f)
    val stepLeft = Tent(wghtIndex, start = -1f, peak = -0.5f, end = -0.5f)
    for (tent in listOf(stepRight, stepLeft)) {
      val (y0, triples) = font.tentRamps(tent)
      val ramps =
        VariableTextOutline.TentRamps(
          0,
          y0,
          triples.map { it.first }.toFloatArray(),
          triples.map { it.second }.toFloatArray(),
          triples.map { it.third }.toFloatArray(),
        )
      for (step in 0..80) {
        val user = wghtAxis.minValue + (wghtAxis.maxValue - wghtAxis.minValue) * (step / 80f)
        val norm = font.normalize(wghtIndex, user)
        assertWithMessage("tent $tent at user=$user (norm=$norm)")
          .that(ramps.evaluate(user))
          .isWithin(1e-4f)
          .of(tent.evaluate(norm))
      }
    }
  }

  @Test
  fun unvariedOutlineUsesKeysWithNoTents() {
    val font = testFonts[1].font
    // A space has no contours or moving coordinates, and empty animated axes produce 0 tents.
    val outline = font.outline("Hamburg", emptyList())
    assertThat(outline.tents).isEmpty()
    assertThat(outline.body).isInstanceOf(VariableTextOutline.Keys::class.java)
    val decoded = VariableTextOutline.decode(outline.encode())
    assertThat(decoded.evaluate(FloatArray(0)).toList())
      .isEqualTo(coordinates(font.layout("Hamburg", emptyMap(), emptyMap())).toList())
  }

  private fun coordinates(outline: TextOutline): FloatArray {
    val out = mutableListOf<Float>()
    outline.emit(
      object : PathSink<Float> {
        override fun moveTo(x: Float, y: Float) {
          out += listOf(x, y)
        }

        override fun lineTo(x: Float, y: Float) {
          out += listOf(x, y)
        }

        override fun quadTo(x1: Float, y1: Float, x2: Float, y2: Float) {
          out += listOf(x1, y1, x2, y2)
        }

        override fun close() {}
      },
    )
    return out.toFloatArray()
  }

  private companion object {
    const val TEXT = "Hamburgefonstiv 0123"
    val cases =
      listOf(
        testFonts[1] to listOf("wght"),
        testFonts[1] to listOf("wght", "slnt"),
        googleSansFlex to listOf("wght", "ROND"),
        testFonts[3] to listOf("SOFT"),
        testFonts[5] to listOf("wght", "opsz"),
      )
  }
}
