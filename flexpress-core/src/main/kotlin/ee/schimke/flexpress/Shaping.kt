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

import java.text.Bidi

/**
 * One line of text as glyphs in visual order, left to right.
 *
 * The text is split into runs by the Unicode bidirectional algorithm. Each run is mapped through
 * `cmap` in logical order (characters in right-to-left runs mirrored, as `(` to `)`), and the
 * font's `GSUB` lookups for [DEFAULT_FEATURES] in the run's script are applied, so ligatures and
 * contextual alternates are formed. Right-to-left runs are then reversed, and the runs placed in
 * visual order.
 *
 * Not done: the positional forms of joining scripts (Arabic `init`/`medi`/`fina`), mark
 * positioning, and the reordering of Indic scripts.
 */
internal fun shapeText(text: String, glyphId: (Int) -> Int, gsub: Gsub?): List<Int> {
  if (text.isEmpty()) return emptyList()
  val bidi = Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
  val runCount = bidi.runCount
  val levels = ByteArray(runCount) { bidi.getRunLevel(it).toByte() }
  val runs: Array<Any> = Array(runCount) { it }
  Bidi.reorderVisually(levels, 0, runs, 0, runCount)
  val glyphs = mutableListOf<Int>()
  for (run in runs) {
    val r = run as Int
    val rtl = bidi.getRunLevel(r) % 2 == 1
    val runText = text.substring(bidi.getRunStart(r), bidi.getRunLimit(r))
    val ids = mutableListOf<Int>()
    var i = 0
    while (i < runText.length) {
      val cp = runText.codePointAt(i)
      ids += glyphId(if (rtl) mirror(cp) else cp)
      i += Character.charCount(cp)
    }
    val shaped = gsub?.substitute(ids, scriptTag(runText), DEFAULT_FEATURES) ?: ids
    glyphs += if (rtl) shaped.asReversed() else shaped
  }
  return glyphs
}

/** The `GSUB` features a shaper applies by default to horizontal text. */
internal val DEFAULT_FEATURES: Set<String> = setOf("ccmp", "locl", "rlig", "liga", "clig", "calt")

/** The OpenType script tag of the first character in [text] with a script of its own. */
private fun scriptTag(text: String): String {
  var i = 0
  while (i < text.length) {
    val cp = text.codePointAt(i)
    when (Character.UnicodeScript.of(cp)) {
      Character.UnicodeScript.COMMON,
      Character.UnicodeScript.INHERITED,
      Character.UnicodeScript.UNKNOWN -> {}
      else -> return SCRIPT_TAGS[Character.UnicodeScript.of(cp)] ?: "DFLT"
    }
    i += Character.charCount(cp)
  }
  return "DFLT"
}

private val SCRIPT_TAGS =
  mapOf(
    Character.UnicodeScript.LATIN to "latn",
    Character.UnicodeScript.GREEK to "grek",
    Character.UnicodeScript.CYRILLIC to "cyrl",
    Character.UnicodeScript.ARABIC to "arab",
    Character.UnicodeScript.HEBREW to "hebr",
    Character.UnicodeScript.SYRIAC to "syrc",
    Character.UnicodeScript.THAANA to "thaa",
    Character.UnicodeScript.ARMENIAN to "armn",
    Character.UnicodeScript.GEORGIAN to "geor",
    Character.UnicodeScript.HAN to "hani",
    Character.UnicodeScript.HIRAGANA to "kana",
    Character.UnicodeScript.KATAKANA to "kana",
    Character.UnicodeScript.HANGUL to "hang",
    Character.UnicodeScript.THAI to "thai",
  )

/** [cp]'s mirrored counterpart in right-to-left text, for the common paired characters. */
private fun mirror(cp: Int): Int = MIRRORS[cp] ?: cp

private val MIRRORS: Map<Int, Int> =
  listOf(
      '(' to ')',
      '[' to ']',
      '{' to '}',
      '<' to '>',
      '«' to '»',
      '‹' to '›',
      '⁅' to '⁆',
      '⁽' to '⁾',
      '₍' to '₎',
      '≤' to '≥',
      '⟨' to '⟩',
      '⟦' to '⟧',
      '〈' to '〉',
      '《' to '》',
      '「' to '」',
      '『' to '』',
      '【' to '】',
    )
    .flatMap { (a, b) -> listOf(a.code to b.code, b.code to a.code) }
    .toMap()
