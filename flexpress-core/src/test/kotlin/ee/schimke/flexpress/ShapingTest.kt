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
  fun arabicJoiningMatchesHarfBuzz() {
    val font = font("noto_sans_arabic")
    // HarfBuzz's full Arabic shaping, positioning off. HarfBuzz returns right-to-left runs in
    // visual order, as shape does. It also keeps a zero-width space glyph (1) for each joiner,
    // where shape drops default-ignorable characters; those are left out here.
    val expected =
      mapOf(
        "مرحبا" to listOf(5, 221, 12, 21, 25, 69),
        "لا" to listOf(6, 63),
        "(سلام)" to listOf(528, 64, 7, 61, 29, 529),
        "السلام عليكم" to listOf(67, 50, 222, 13, 60, 41, 1, 64, 7, 61, 28, 62, 4),
        "محمد" to listOf(23, 68, 20, 69),
        "بببب" to listOf(221, 11, 221, 12, 221, 12, 221, 14),
        "ب" to listOf(221, 10),
        // Tatweel joins on both sides and takes no form of its own.
        "بـب" to listOf(221, 11, 94, 221, 14),
        // The zero width non-joiner breaks the join; the joiner forces one.
        "ب\u200cب" to listOf(221, 10, 221, 10),
        "ب\u200dا" to listOf(5, 221, 14),
        // Vowel marks are transparent to joining.
        "بِسْمِ" to listOf(270, 67, 250, 28, 270, 221, 14),
        "سنة" to listOf(201, 73, 200, 12, 29),
        "عين" to listOf(200, 71, 222, 13, 41),
        "لله" to listOf(73, 60, 62),
        "كتب" to listOf(221, 11, 201, 13, 51),
        "في" to listOf(222, 91, 200, 45),
      )
    for ((text, glyphs) in expected) {
      assertWithMessage(text).that(font.shape(text)).isEqualTo(glyphs)
    }
  }

  @Test
  fun markPositionsMatchHarfBuzz() {
    val font = font("noto_sans_arabic")
    // HarfBuzz's full shaping with kerning and mark positioning, at the default location: each
    // glyph's id and absolute (x, y) in font units, in visual order, and the line's advance.
    class Expected(val glyphs: List<Int>, val positions: List<Pair<Int, Int>>, val advance: Int)
    val expected =
      mapOf(
        "مرحبا" to
          Expected(
            listOf(5, 221, 12, 21, 25, 69),
            listOf(0 to 0, 385 to -3, 291 to 0, 634 to 0, 1183 to 0, 1579 to 0),
            2104,
          ),
        "بِسْمِ" to
          Expected(
            listOf(270, 67, 250, 28, 270, 221, 14),
            listOf(139 to 0, 0 to 0, 863 to -126, 562 to 0, 1377 to -191, 1443 to -3, 1412 to 0),
            1681,
          ),
        "مَرْحَبًا" to
          Expected(
            listOf(5, 248, 221, 12, 244, 21, 250, 25, 244, 69),
            listOf(
              0 to 0,
              284 to -174,
              385 to -3,
              291 to 0,
              638 to -106,
              634 to 0,
              1218 to -107,
              1183 to 0,
              1721 to -100,
              1579 to 0,
            ),
            2104,
          ),
        // Shadda with a vowel on it: mark to mark.
        "السَّلَامُ" to
          Expected(
            listOf(245, 64, 7, 244, 61, 244, 241, 28, 62, 4),
            listOf(
              89 to -76,
              0 to 0,
              484 to 0,
              744 to 256,
              863 to 0,
              1332 to 74,
              1340 to -126,
              1083 to 0,
              1933 to 0,
              2193 to 0,
            ),
            2431,
          ),
        "كِتَابٌ" to
          Expected(
            listOf(249, 221, 10, 5, 244, 201, 13, 270, 51),
            listOf(
              302 to -160,
              410 to -23,
              0 to 0,
              993 to 0,
              1313 to 27,
              1338 to -180,
              1284 to 0,
              1678 to 0,
              1657 to 0,
            ),
            2069,
          ),
        // A mark on the lam-alef ligature.
        "لَا" to Expected(listOf(6, 244, 63), listOf(0 to 0, 249 to 256, 363 to 0), 582),
        // Stacked marks whose mark-to-mark lookups use mark filtering sets: each attaches to the
        // nearest earlier mark in its lookup's set, passing over the others.
        "\u062C\u0651\u0656\u0628" to
          Expected(
            listOf(221, 11, 275, 241, 221, 21),
            listOf(387 to -23, 0 to 0, 1303 to -186, 1088 to -106, 1296 to 2, 1093 to 0),
            1682,
          ),
        "\u0646\u0653\u0656\u0628" to
          Expected(
            listOf(221, 11, 214, 275, 200, 14),
            listOf(387 to -23, 0 to 0, 1041 to 88, 1131 to -5, 1145 to -59, 1093 to 0),
            1362,
          ),
        "\u0646\u0654\u0655\u0628" to
          Expected(
            listOf(221, 11, 210, 226, 200, 14),
            listOf(387 to -23, 0 to 0, 1082 to 88, 1094 to -5, 1145 to -59, 1093 to 0),
            1362,
          ),
      )
    val coords = font.normalize(emptyMap())
    for ((text, want) in expected) {
      val glyphs = font.shapePositioned(text, emptyMap())
      assertWithMessage(text).that(glyphs.map { it.id }).isEqualTo(want.glyphs)
      val placement =
        font.placeGlyphs(
          glyphs,
          emptyMap(),
          0f,
          { font.outline(glyphs[it].id, coords).advance },
          { it },
          Float::plus,
        )
      glyphs.indices.forEach { i ->
        val (x, y) = want.positions[i]
        assertWithMessage("$text glyph $i x").that(placement.x[i]).isWithin(1f).of(x.toFloat())
        assertWithMessage("$text glyph $i y").that(glyphs[i].dy).isWithin(1f).of(y.toFloat())
      }
      assertWithMessage("$text advance")
        .that(placement.advance)
        .isWithin(1f)
        .of(want.advance.toFloat())
    }
  }

  @Test
  fun syriacJoiningMatchesHarfBuzz() {
    val font = font("noto_sans_syriac")
    // HarfBuzz's full Syriac shaping, positioning off. The words marked * draw differently
    // without fin2, fin3 and med2: each has an Alaph after a letter that cannot join it.
    val expected =
      mapOf(
        "\u0710" to listOf(4),
        "\u0718\u0710" to listOf(6, 28), // *
        "\u0715\u0710" to listOf(5, 22), // *
        "\u0712\u0710" to listOf(177, 13),
        "\u0712\u0710\u0712" to listOf(10, 179, 13), // *
        "\u072B\u0720\u0721\u0710" to listOf(177, 59, 55, 96),
        "\u0721\u072A\u071D\u0710" to listOf(7, 47, 92, 60),
        "\u0710\u0720\u0717\u0710" to listOf(6, 27, 56, 4), // *
        "\u0715\u072A\u0710" to listOf(5, 91, 22), // *
        "\u0712\u072A\u0710" to listOf(5, 92, 13), // *
        "\u0723\u0718\u072A\u071D\u071D\u0710" to listOf(7, 46, 47, 91, 29, 68),
        "\u0725\u0720\u0721\u0710 \u0715\u0710" to listOf(5, 22, 1, 177, 59, 55, 76), // *
        "\u0710\u0712\u0710" to listOf(177, 13, 4),
      )
    for ((text, glyphs) in expected) {
      assertWithMessage(text).that(font.shape(text)).isEqualTo(glyphs)
    }
  }

  @Test
  fun joiningFormsFollowTheUnicodeAlgorithm() {
    // beh (dual), alef (right), fatha (transparent), tatweel (join causing), zwnj (non joining);
    // Syriac beth (dual), waw (right), dalath (Dalath-Rish group), alaph (Alaph group).
    fun forms(text: String) = joiningForms(text.codePoints().toArray()).toList()
    val (isol, fina, medi, init) = listOf(1, 2, 4, 8)
    assertThat(forms("ب")).containsExactly(isol)
    assertThat(forms("بب")).containsExactly(init, fina).inOrder()
    assertThat(forms("ببب")).containsExactly(init, medi, fina).inOrder()
    // Alef joins only the letter before it, so the beh after it starts again.
    assertThat(forms("بابب")).containsExactly(init, fina, init, fina).inOrder()
    // A transparent mark between two letters takes no form and does not break the join.
    assertThat(forms("بَب")).containsExactly(init, 0, fina).inOrder()
    assertThat(forms("ب\u200cب")).containsExactly(isol, 0, isol).inOrder()
    // Tatweel causes joining, and as HarfBuzz does, takes a form itself.
    assertThat(forms("ب\u0640")).containsExactly(init, fina).inOrder()
    // Syriac Alaph after letters that cannot join it: waw (fin2), dalath (fin3); after beth it
    // joins (fina), and becomes med2 when a letter follows.
    val (fin2, fin3, med2) = listOf(16, 32, 64)
    assertThat(forms("\u0710")).containsExactly(isol)
    assertThat(forms("\u0718\u0710")).containsExactly(isol, fin2).inOrder()
    assertThat(forms("\u0715\u0710")).containsExactly(isol, fin3).inOrder()
    assertThat(forms("\u0712\u0710")).containsExactly(init, fina).inOrder()
    assertThat(forms("\u0712\u0710\u0712")).containsExactly(init, med2, isol).inOrder()
  }

  @Test
  fun numbersStayLeftToRightInsideRightToLeftText() {
    val font = font("noto_sans_arabic")
    // A right-to-left paragraph: the Arabic-Indic digits are a left-to-right run, drawn in reading
    // order at the left end of the line, then the space, then the word, itself right to left.
    val digits = font.shape("\u0661\u0662\u0663")
    assertThat(digits).isEqualTo("\u0661\u0662\u0663".map { font.glyphId(it.code) })
    assertThat(font.shape("سنة \u0661\u0662\u0663"))
      .isEqualTo(digits + font.shape(" ") + font.shape("سنة"))
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
  fun marksTakeHarfBuzzOrder() {
    fun order(text: String) = canonicalMarkOrder(text.codePoints().toArray()).toList()
    val shaddaFirst = listOf(0x0633, 0x0651, 0x064E)
    // Shadda before fatha whichever was typed first, as HarfBuzz orders them.
    assertThat(order("\u0633\u0651\u064E")).isEqualTo(shaddaFirst)
    assertThat(order("\u0633\u064E\u0651")).isEqualTo(shaddaFirst)
    // Hamza above moves ahead of the kasra that canonical order (32 before 230) puts first.
    assertThat(order("\u0628\u0654\u0650")).isEqualTo(listOf(0x0628, 0x0654, 0x0650))
    // Below modifier marks first, then above ones: hamza below, hamza above, kasra.
    assertThat(order("\u0628\u0650\u0654\u0655")).isEqualTo(listOf(0x0628, 0x0655, 0x0654, 0x0650))
    // Other marks keep canonical order: kasra (32) before small high meem (230).
    assertThat(order("\u0628\u06E2\u0650")).isEqualTo(listOf(0x0628, 0x0650, 0x06E2))
    // Text without adjacent marks is not copied.
    val plain = "café ب\u0650".codePoints().toArray()
    assertThat(canonicalMarkOrder(plain)).isSameInstanceAs(plain)
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
