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
import java.io.File
import org.junit.Test

/**
 * [VariableFont.shape] against HarfBuzz: the expected glyph ids are what `hb-shape` produces with
 * the same `GSUB` features ([DEFAULT_FEATURES]) and the joining, positioning and variation features
 * turned off. Regenerate them with uharfbuzz, `hb.shape(font, buf, {"init": False, ...})`.
 */
class ShapingTest {
  @Test
  fun firaCodeLigaturesMatchHarfBuzz() {
    val font = font("fira_code")
    val expected =
      mapOf(
        "a => b != c -> d" to
          listOf(27, 249, 345, 351, 249, 28, 249, 145, 203, 249, 29, 249, 230, 357, 249, 30),
        "www" to listOf(60, 60, 63),
        "<!-- x -->" to listOf(269, 145, 177, 301, 249, 53, 249, 230, 229, 357),
        "===" to listOf(267, 267, 297),
        "<=>" to listOf(363, 343, 351),
        "0xFF" to listOf(66, 56, 6, 6),
        "|> ::" to listOf(278, 291, 249, 143, 196),
        "fi fl ffi" to listOf(32, 57, 249, 32, 40, 249, 32, 32, 57),
        "/* */" to listOf(149, 221, 249, 147, 211),
        "&&" to listOf(277, 284),
      )
    for ((text, glyphs) in expected) {
      assertWithMessage(text).that(font.shape(text)).isEqualTo(glyphs)
    }
  }

  @Test
  fun ligaturesChangeTheGlyphs() {
    val font = font("fira_code")
    val text = "=>"
    val unshaped = text.map { font.glyphId(it.code) }
    assertThat(font.shape(text)).isNotEqualTo(unshaped)
  }

  @Test
  fun arabicIsVisualOrderAndMatchesHarfBuzz() {
    val font = font("noto_sans_arabic")
    // HarfBuzz returns right-to-left runs in visual order, as shape does.
    val expected =
      mapOf(
        "مرحبا" to listOf(4, 221, 10, 16, 24, 64),
        "لا" to listOf(4, 58),
        "(سلام)" to listOf(528, 64, 4, 58, 26, 529),
      )
    for ((text, glyphs) in expected) {
      assertWithMessage(text).that(font.shape(text)).isEqualTo(glyphs)
    }
  }

  @Test
  fun mixedDirectionRunsAreInVisualOrder() {
    val font = font("noto_sans_arabic")
    val latin = font.shape("Hi ")
    val arabic = font.shape("مرحبا")
    // A left-to-right paragraph: the Latin run first, then the Arabic run, itself right to left.
    assertThat(font.shape("Hi مرحبا")).isEqualTo(latin + arabic)
    // A right-to-left paragraph (it starts with Arabic): the Arabic run is placed on the right.
    assertThat(font.shape("مرحبا Hi")).isEqualTo(font.shape("Hi") + font.shape(" ") + arabic)
  }

  @Test
  fun bracketsMirrorInRightToLeftRuns() {
    val font = font("noto_sans_arabic")
    val shaped = font.shape("(سلام)")
    // Visually the run is reversed, so its closing parenthesis is drawn first, as "(" mirrored.
    assertThat(shaped.first()).isEqualTo(font.shape("(").single())
    assertThat(shaped.last()).isEqualTo(font.shape(")").single())
  }

  @Test
  fun textWithoutLookupsIsCmap() {
    val font = testFonts[1].font // Roboto Flex: no default GSUB lookups for these letters
    val text = "Hamburg"
    assertThat(font.shape(text)).isEqualTo(text.map { font.glyphId(it.code) })
  }

  private fun font(name: String): VariableFont =
    VariableFont.parse(File(fontsDir, "res/raw/$name.ttf").readBytes())
}
