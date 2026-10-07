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
import java.text.Normalizer

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
 * Adjacent combining marks are first put in HarfBuzz's order ([canonicalMarkOrder]). With [marks],
 * each mark glyph is then attached to its base or preceding mark by the font's `GPOS` `mark` and
 * `mkmk` anchors at normalized [coords].
 *
 * Not done: cursive attachment, and the reordering of Indic scripts.
 */
internal fun shapeText(
  text: String,
  glyphId: (Int) -> Int,
  gsub: Gsub?,
  marks: MarkAttachment? = null,
  coords: FloatArray = FloatArray(0),
): List<ShapedGlyph> {
  if (text.isEmpty()) return emptyList()
  val bidi = Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
  val runCount = bidi.runCount
  val levels = ByteArray(runCount) { bidi.getRunLevel(it).toByte() }
  val runs: Array<Any> = Array(runCount) { it }
  Bidi.reorderVisually(levels, 0, runs, 0, runCount)
  val glyphs = mutableListOf<ShapedGlyph>()
  for (run in runs) {
    val r = run as Int
    val rtl = bidi.getRunLevel(r) % 2 == 1
    val runText = text.substring(bidi.getRunStart(r), bidi.getRunLimit(r))
    val codePoints = canonicalMarkOrder(runText.codePoints().toArray())
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
    // Marks attach in logical order; the run is then placed in visual order.
    val attachments = marks?.attach(drawn, coords)
    val start = glyphs.size
    fun visual(k: Int) = start + if (rtl) drawn.size - 1 - k else k
    val placed = arrayOfNulls<ShapedGlyph>(drawn.size)
    // A glyph's height is its own offset plus its base's: a mark can sit on a mark. A base always
    // comes earlier in logical order, so its height is known by then.
    val heights = FloatArray(drawn.size)
    drawn.forEachIndexed { k, id ->
      val a = attachments?.get(k)
      if (a != null) heights[k] = heights[a.base] + a.dy
      placed[visual(k) - start] =
        ShapedGlyph(id, a?.let { visual(it.base) } ?: -1, a?.dx ?: 0f, heights[k], rtl)
    }
    placed.mapTo(glyphs) { it!! }
  }
  return glyphs
}

/**
 * [codePoints] with each run of two or more combining marks in the order HarfBuzz puts them:
 * canonical order, by combining class, as Unicode normalization sorts them, with two Arabic
 * changes. A shadda goes before the short vowels, so fonts can put the vowel on it, and modifier
 * marks such as hamza above move to the front, so they attach to the letter. Text with no adjacent
 * marks is returned as is.
 */
internal fun canonicalMarkOrder(codePoints: IntArray): IntArray {
  var result = codePoints
  var i = 0
  while (i < codePoints.size) {
    if (!isMark(codePoints[i])) {
      i++
      continue
    }
    var end = i + 1
    while (end < codePoints.size && isMark(codePoints[end])) end++
    if (end - i > 1) {
      // NFD of marks alone only reorders them, unless one decomposes; then the run is kept.
      val run = String(codePoints, i, end - i)
      val ordered = Normalizer.normalize(run, Normalizer.Form.NFD).codePoints().toArray()
      if (ordered.size == end - i) {
        moveShaddaFirst(ordered)
        moveModifierMarksFirst(ordered)
        if (!ordered.contentEquals(codePoints.copyOfRange(i, end))) {
          if (result === codePoints) result = codePoints.copyOf()
          ordered.copyInto(result, i)
        }
      }
    }
    i = end
  }
  return result
}

/**
 * Moves each shadda in [marks], canonically ordered, before the vowel marks of combining classes 27
 * to 32 ahead of it (class 33): HarfBuzz gives the shadda class 27 instead.
 */
private fun moveShaddaFirst(marks: IntArray) {
  for (k in marks.indices) {
    if (marks[k] != SHADDA) continue
    var j = k
    while (j > 0 && isHaraka(marks[j - 1])) {
      marks[j] = marks[j - 1]
      j--
    }
    marks[j] = SHADDA
  }
}

private const val SHADDA = 0x0651

/**
 * The Arabic vowel marks of combining classes 27 to 32, which canonical order puts before shadda.
 */
private fun isHaraka(cp: Int): Boolean =
  cp in 0x064B..0x0650 || cp in 0x0618..0x061A || cp in 0x08F0..0x08F2

/**
 * HarfBuzz's Arabic mark reordering: in [marks], ordered, the modifier combining marks that begin
 * the below (220) marks, then those that begin the above (230) marks, move to the front, after any
 * moved before them.
 */
private fun moveModifierMarksFirst(marks: IntArray) {
  var front = 0
  var i = 0
  for (cc in intArrayOf(BELOW, ABOVE)) {
    while (i < marks.size && arabicMarkClass(marks[i]) < cc) i++
    if (i == marks.size) break
    if (arabicMarkClass(marks[i]) > cc) continue
    var j = i
    while (j < marks.size && arabicMarkClass(marks[j]) == cc && marks[j] in MODIFIER_MARKS) j++
    if (i == j) continue
    val moved = marks.copyOfRange(i, j)
    marks.copyInto(marks, front + j - i, front, i)
    moved.copyInto(marks, front)
    front += j - i
    i = j
  }
}

private const val BELOW = 220
private const val ABOVE = 230

/** Marks HarfBuzz moves to the front of an Arabic mark sequence: hamza and its kin. */
private val MODIFIER_MARKS =
  setOf(
    0x0654,
    0x0655,
    0x0658,
    0x06DC,
    0x06E3,
    0x06E7,
    0x06E8,
    0x08CA,
    0x08CB,
    0x08CD,
    0x08CE,
    0x08CF,
    0x08D3,
    0x08F3,
  )

/**
 * An Arabic mark's combining class where it is below (220) or above (230), from Unicode 15.1's
 * `UnicodeData.txt`, and 0 for every other character: the harakat's classes 27 to 35 sort before
 * both.
 */
private fun arabicMarkClass(cp: Int): Int =
  when (cp) {
    in 0x0655..0x0656,
    0x065C,
    0x065F,
    0x06E3,
    0x06EA,
    0x06ED,
    in 0x0899..0x089B,
    in 0x08CF..0x08D3,
    0x08E3,
    0x08E6,
    0x08E9,
    in 0x08ED..0x08EF,
    0x08F6,
    in 0x08F9..0x08FA -> BELOW
    in 0x0610..0x0617,
    in 0x0653..0x0654,
    in 0x0657..0x065B,
    in 0x065D..0x065E,
    in 0x06D6..0x06DC,
    in 0x06DF..0x06E2,
    0x06E4,
    in 0x06E7..0x06E8,
    in 0x06EB..0x06EC,
    0x0898,
    in 0x089C..0x089F,
    in 0x08CA..0x08CE,
    in 0x08D4..0x08E1,
    in 0x08E4..0x08E5,
    in 0x08E7..0x08E8,
    in 0x08EA..0x08EC,
    in 0x08F3..0x08F5,
    in 0x08F7..0x08F8,
    in 0x08FB..0x08FF -> ABOVE
    else -> 0
  }

private fun isMark(cp: Int): Boolean {
  val type = Character.getType(cp)
  return type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()
}

/** The `GSUB` features a shaper applies by default to horizontal text. */
internal val DEFAULT_FEATURES: Set<String> = setOf("ccmp", "locl", "rlig", "liga", "clig", "calt")

/**
 * A glyph of shaped text, in visual order. A mark that attaches to another glyph has [base], that
 * glyph's index, and goes [dx] font units right of its origin, taking no advance of its own; other
 * glyphs have a [base] of -1 and are placed by advance. [dy] is the glyph's height above the
 * baseline, through any chain of attachments, and 0 for glyphs placed by advance. [rtl] is whether
 * the glyph is in a right-to-left run, where kerning pairs are taken in logical order.
 */
@InternalFlexpressApi
class ShapedGlyph(val id: Int, val base: Int, val dx: Float, val dy: Float, val rtl: Boolean)

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
