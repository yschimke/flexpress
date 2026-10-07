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
 * contextual alternates are formed. In joining scripts (Arabic, Syriac) each letter is also given
 * its positional form (`isol`, `fina`, `medi` or `init`) by the Unicode joining algorithm, in
 * stages as HarfBuzz's Arabic shaper applies them. Right-to-left runs are then reversed, and the
 * runs placed in visual order.
 *
 * Not done: mark positioning, and the reordering of Indic scripts.
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
    val codePoints = runText.codePoints().toArray()
    // Default-ignorable characters (joiners, bidi controls) take part in shaping but draw nothing.
    val ids = codePoints.map {
      if (isDefaultIgnorable(it)) IGNORED else glyphId(if (rtl) mirror(it) else it)
    }
    val script = scriptTag(runText)
    val shaped =
      when {
        gsub == null -> ids
        script in JOINING_SCRIPTS ->
          gsub.substitute(ids, joiningForms(codePoints).toList(), script, JOINING_STAGES, FORMS)
        else -> gsub.substitute(ids, script, DEFAULT_FEATURES)
      }
    val drawn = shaped.filter { it != IGNORED }
    glyphs += if (rtl) drawn.asReversed() else drawn
  }
  return glyphs
}

/** The `GSUB` features a shaper applies by default to horizontal text. */
internal val DEFAULT_FEATURES: Set<String> = setOf("ccmp", "locl", "rlig", "liga", "clig", "calt")

/** A glyph id no font has, for default-ignorable characters: nothing in `GSUB` matches it. */
private const val IGNORED = -1

/** Whether [cp] has the Unicode `Default_Ignorable_Code_Point` property. */
internal fun isDefaultIgnorable(cp: Int): Boolean =
  cp == 0x00AD ||
    cp == 0x034F ||
    cp == 0x061C ||
    cp in 0x115F..0x1160 ||
    cp in 0x17B4..0x17B5 ||
    cp in 0x180B..0x180F ||
    cp in 0x200B..0x200F ||
    cp in 0x202A..0x202E ||
    cp in 0x2060..0x206F ||
    cp == 0x3164 ||
    cp in 0xFE00..0xFE0F ||
    cp == 0xFEFF ||
    cp == 0xFFA0 ||
    cp in 0xFFF0..0xFFF8 ||
    cp in 0x1BCA0..0x1BCA3 ||
    cp in 0x1D173..0x1D17A ||
    cp in 0xE0000..0xE0FFF

/** Scripts whose letters take positional forms by the Unicode joining algorithm. */
private val JOINING_SCRIPTS = setOf("arab", "syrc")

private const val ISOL = 1
private const val FINA = 2
private const val MEDI = 4
private const val INIT = 8

/** The positional-form features, each limited to the letters with its mask bit. */
private val FORMS = mapOf("isol" to ISOL, "fina" to FINA, "medi" to MEDI, "init" to INIT)

/** The `GSUB` stages of HarfBuzz's Arabic shaper: each feature group applied in turn. */
private val JOINING_STAGES =
  listOf(
    setOf("ccmp", "locl"),
    setOf("isol"),
    setOf("fina"),
    setOf("medi"),
    setOf("init"),
    setOf("rlig"),
    setOf("calt", "liga", "clig", "mset"),
  )

/**
 * Each code point's positional form, as a [FORMS] mask bit: the Unicode joining algorithm over
 * [codePoints] in logical order. A letter joins the letter before it (on its right) when it can
 * join on its right and that letter can join on its left, and likewise the letter after it;
 * transparent characters, such as vowel marks, are passed over and take no form, as do non-joining
 * and join-causing characters.
 */
internal fun joiningForms(codePoints: IntArray): IntArray {
  val types = codePoints.map(::joiningType)
  fun joinsLeft(t: JoiningType) =
    t == JoiningType.DUAL || t == JoiningType.LEFT || t == JoiningType.JOIN_CAUSING
  fun joinsRight(t: JoiningType) =
    t == JoiningType.DUAL || t == JoiningType.RIGHT || t == JoiningType.JOIN_CAUSING
  return IntArray(codePoints.size) { i ->
    val type = types[i]
    if (
      type == JoiningType.TRANSPARENT ||
        type == JoiningType.NON_JOINING ||
        type == JoiningType.JOIN_CAUSING
    ) {
      return@IntArray 0
    }
    var p = i - 1
    while (p >= 0 && types[p] == JoiningType.TRANSPARENT) p--
    var n = i + 1
    while (n < types.size && types[n] == JoiningType.TRANSPARENT) n++
    val before = p >= 0 && joinsLeft(types[p]) && joinsRight(type)
    val after = n < types.size && joinsLeft(type) && joinsRight(types[n])
    when {
      before && after -> MEDI
      before -> FINA
      after -> INIT
      else -> ISOL
    }
  }
}

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
