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
 * The text is split into runs by the Unicode bidirectional algorithm, and each run into runs of one
 * script ([scriptRuns]). Each script run is mapped through `cmap` in logical order (characters in
 * right-to-left runs mirrored, as `(` to `)`), and the font's `GSUB` lookups for [DEFAULT_FEATURES]
 * in that script are applied, so ligatures and contextual alternates are formed. In joining scripts
 * (Arabic, Syriac) each letter is also given its positional form (`isol`, `fina`, `medi`, `init`,
 * and Syriac's `fin2`, `fin3` and `med2`) by [joiningForms], in stages as HarfBuzz's Arabic shaper
 * applies them. Right-to-left runs are then reversed, and the runs placed in visual order.
 *
 * Characters are first normalized as HarfBuzz's normalizer does: split into their parts when the
 * font lacks them ([decompose]), adjacent combining marks put in HarfBuzz's order
 * ([canonicalMarkOrder]), and a mark composed onto its letter when the font has the composite
 * ([compose]). With [marks], each mark glyph is then attached to its base or preceding mark by the
 * font's `GPOS` `mark` and `mkmk` anchors at normalized [coords].
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
  val glyphs = mutableListOf<ShapedGlyph>()
  for ((start, limit, rtl) in bidiRuns(text)) {
    val runText = text.substring(start, limit)
    val hasGlyph = { cp: Int -> glyphId(cp) != 0 }
    val codePoints =
      compose(canonicalMarkOrder(decompose(runText.codePoints().toArray(), hasGlyph)), hasGlyph)
    // Each script in the run is shaped with its own script's features, in logical order.
    val drawn = mutableListOf<Int>()
    val attachments = mutableListOf<Attachment?>()
    for ((from, to, script) in scriptRuns(codePoints)) {
      val part = codePoints.copyOfRange(from, to)
      // Default-ignorable characters (joiners, bidi controls) take part in shaping but draw
      // nothing.
      val ids = part.map {
        if (isDefaultIgnorable(it)) IGNORED else glyphId(if (rtl) mirror(it) else it)
      }
      val shaped =
        when {
          gsub == null -> ids
          script in JOINING_SCRIPTS ->
            gsub.substitute(ids, joiningForms(part).toList(), script, JOINING_STAGES, FORMS)
          else -> gsub.substitute(ids, script, DEFAULT_FEATURES)
        }
      val partDrawn = shaped.filter { it != IGNORED }
      // Marks attach within their own script's glyphs.
      val offset = drawn.size
      val partAttachments = marks?.attach(partDrawn, script, coords)
      drawn += partDrawn
      partDrawn.indices.mapTo(attachments) { k ->
        partAttachments?.get(k)?.let { it.copy(base = it.base + offset) }
      }
    }
    val start = glyphs.size
    fun visual(k: Int) = start + if (rtl) drawn.size - 1 - k else k
    val placed = arrayOfNulls<ShapedGlyph>(drawn.size)
    // A glyph's height is its own offset plus its base's: a mark can sit on a mark. A base always
    // comes earlier in logical order, so its height is known by then.
    val heights = FloatArray(drawn.size)
    drawn.forEachIndexed { k, id ->
      val a = attachments[k]
      if (a != null) heights[k] = heights[a.base] + a.dy
      placed[visual(k) - start] =
        ShapedGlyph(id, a?.let { visual(it.base) } ?: -1, a?.dx ?: 0f, heights[k], rtl)
    }
    placed.mapTo(glyphs) { it!! }
  }
  return glyphs
}

/**
 * [codePoints] with each character the font lacks ([hasGlyph] false) replaced by its canonical
 * decomposition when the font has every part, as HarfBuzz's normalizer does: `é` drawn as `e` and a
 * combining acute.
 */
internal fun decompose(codePoints: IntArray, hasGlyph: (Int) -> Boolean): IntArray {
  if (codePoints.all(hasGlyph)) return codePoints
  val out = ArrayList<Int>(codePoints.size + 4)
  for (cp in codePoints) {
    if (hasGlyph(cp) || isDefaultIgnorable(cp)) {
      out += cp
      continue
    }
    val parts = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFD)
    val partCodePoints = parts.codePoints().toArray()
    // A singleton decomposition counts too: the Kelvin sign is drawn as K.
    if (!partCodePoints.contentEquals(intArrayOf(cp)) && partCodePoints.all(hasGlyph)) {
      out += partCodePoints.toList()
    } else {
      out += cp
    }
  }
  return out.toIntArray()
}

/**
 * [codePoints] with each combining mark composed onto the character before it when Unicode composes
 * the pair and the font has the composite ([hasGlyph]), as HarfBuzz's normalizer does: Arabic alef
 * and hamza above drawn as `أ`. As in canonical composition, a mark is blocked by a mark between
 * them of the same or a higher combining class. Text without marks is returned as is.
 */
internal fun compose(codePoints: IntArray, hasGlyph: (Int) -> Boolean): IntArray {
  if (codePoints.none(::isMark)) return codePoints
  val out = ArrayList<Int>(codePoints.size)
  var starter = -1
  for (cp in codePoints) {
    if (!isMark(cp)) {
      starter = out.size
      out += cp
      continue
    }
    val composite = if (starter >= 0) composite(out[starter], cp) else null
    val blocked = (starter + 1 until out.size).any { !sortsBefore(out[it], cp) }
    if (composite != null && !blocked && hasGlyph(composite)) out[starter] = composite
    else out += cp
  }
  return out.toIntArray()
}

/** The canonical composite of [base] and [mark], or null when Unicode composes no such pair. */
private fun composite(base: Int, mark: Int): Int? {
  val composed =
    Normalizer.normalize(
      String(Character.toChars(base)) + String(Character.toChars(mark)),
      Normalizer.Form.NFC,
    )
  return if (composed.codePointCount(0, composed.length) == 1) composed.codePointAt(0) else null
}

/**
 * Whether mark [a]'s combining class is lower than [b]'s: canonical order moves it first. A
 * modifier mark ahead of another mark is one [moveModifierMarksFirst] moved, which HarfBuzz gives a
 * class below every Arabic mark's.
 */
private fun sortsBefore(a: Int, b: Int): Boolean {
  if (a in MODIFIER_MARKS && b !in MODIFIER_MARKS) return true
  val pair = String(Character.toChars(b)) + String(Character.toChars(a))
  return Normalizer.normalize(pair, Normalizer.Form.NFD).codePointAt(0) == a
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

/**
 * [text]'s bidirectional runs in visual order: `(start, limit, rightToLeft)`. Text with nothing
 * right to left is one left-to-right run, without running the bidirectional algorithm.
 */
private fun bidiRuns(text: String): List<Triple<Int, Int, Boolean>> {
  val chars = text.toCharArray()
  if (!Bidi.requiresBidi(chars, 0, chars.size)) return listOf(Triple(0, text.length, false))
  val bidi = Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
  val runCount = bidi.runCount
  val levels = ByteArray(runCount) { bidi.getRunLevel(it).toByte() }
  val runs: Array<Any> = Array(runCount) { it }
  Bidi.reorderVisually(levels, 0, runs, 0, runCount)
  return runs.map {
    val r = it as Int
    Triple(bidi.getRunStart(r), bidi.getRunLimit(r), bidi.getRunLevel(r) % 2 == 1)
  }
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

private const val NONE = 0
private const val ISOL = 1
private const val FINA = 2
private const val MEDI = 4
private const val INIT = 8
private const val FIN2 = 16
private const val FIN3 = 32
private const val MED2 = 64

/** The positional-form features, each limited to the letters with its mask bit. */
private val FORMS =
  mapOf(
    "isol" to ISOL,
    "fina" to FINA,
    "fin2" to FIN2,
    "fin3" to FIN3,
    "medi" to MEDI,
    "med2" to MED2,
    "init" to INIT,
  )

/** The `GSUB` stages of HarfBuzz's Arabic shaper: each feature group applied in turn. */
private val JOINING_STAGES =
  listOf(
    setOf("ccmp", "locl"),
    setOf("isol"),
    setOf("fina"),
    setOf("fin2"),
    setOf("fin3"),
    setOf("medi"),
    setOf("med2"),
    setOf("init"),
    setOf("rlig"),
    setOf("calt", "liga", "clig", "mset"),
  )

/**
 * Each code point's positional form, as a [FORMS] mask bit: HarfBuzz's Arabic joining state machine
 * over [codePoints] in logical order, which is the Unicode joining algorithm plus Syriac's Alaph
 * forms. A letter joins the letter before it (on its right) when it can join on its right and that
 * letter can join on its left, and likewise the letter after it. Transparent characters, such as
 * vowel marks, are passed over and take no form, as do non-joining characters.
 *
 * Alaph joins only on its right, and takes a form of its own after a letter that cannot join it:
 * `fin3` after Dalath or Rish, `fin2` after others, and `med2` when it is joined and a letter
 * follows.
 */
internal fun joiningForms(codePoints: IntArray): IntArray {
  val forms = IntArray(codePoints.size)
  var prev = -1
  var state = 0
  for ((i, cp) in codePoints.withIndex()) {
    val column =
      when {
        cp == ALAPH -> JOIN_ALAPH
        cp in DALATH_RISH -> JOIN_DALATH_RISH
        else ->
          when (joiningType(cp)) {
            JoiningType.TRANSPARENT -> continue
            JoiningType.NON_JOINING -> JOIN_U
            JoiningType.LEFT -> JOIN_L
            JoiningType.RIGHT -> JOIN_R
            JoiningType.DUAL,
            JoiningType.JOIN_CAUSING -> JOIN_D
          }
      }
    val (prevAction, action, next) = JOINING_STATES[state][column]
    if (prevAction != NONE && prev >= 0) forms[prev] = prevAction
    forms[i] = action
    prev = i
    state = next
  }
  return forms
}

private const val ALAPH = 0x0710

/** Dalath, dotless Dalath-Rish, Rish and Persian Dhalath: the letters `fin3` follows. */
private val DALATH_RISH = setOf(0x0715, 0x0716, 0x072A, 0x072F)

private const val JOIN_U = 0
private const val JOIN_L = 1
private const val JOIN_R = 2
private const val JOIN_D = 3
private const val JOIN_ALAPH = 4
private const val JOIN_DALATH_RISH = 5

/**
 * HarfBuzz's `arabic_state_table`. Rows are states, columns the next character's joining type
 * (`JOIN_*`); each entry is the form to give the previous joining character (or [NONE] to leave
 * it), the form for this one, and the next state.
 */
private val JOINING_STATES: Array<Array<Triple<Int, Int, Int>>> =
  arrayOf(
    // 0: the previous character was non-joining; not willing to join.
    arrayOf(
      Triple(NONE, NONE, 0),
      Triple(NONE, ISOL, 2),
      Triple(NONE, ISOL, 1),
      Triple(NONE, ISOL, 2),
      Triple(NONE, ISOL, 1),
      Triple(NONE, ISOL, 6),
    ),
    // 1: it was right-joining, or an isolated Alaph; not willing to join.
    arrayOf(
      Triple(NONE, NONE, 0),
      Triple(NONE, ISOL, 2),
      Triple(NONE, ISOL, 1),
      Triple(NONE, ISOL, 2),
      Triple(NONE, FIN2, 5),
      Triple(NONE, ISOL, 6),
    ),
    // 2: it was dual- or left-joining, isolated so far; willing to join.
    arrayOf(
      Triple(NONE, NONE, 0),
      Triple(NONE, ISOL, 2),
      Triple(INIT, FINA, 1),
      Triple(INIT, FINA, 3),
      Triple(INIT, FINA, 4),
      Triple(INIT, FINA, 6),
    ),
    // 3: it was dual-joining, final so far; willing to join.
    arrayOf(
      Triple(NONE, NONE, 0),
      Triple(NONE, ISOL, 2),
      Triple(MEDI, FINA, 1),
      Triple(MEDI, FINA, 3),
      Triple(MEDI, FINA, 4),
      Triple(MEDI, FINA, 6),
    ),
    // 4: it was a final Alaph; not willing to join.
    arrayOf(
      Triple(NONE, NONE, 0),
      Triple(NONE, ISOL, 2),
      Triple(MED2, ISOL, 1),
      Triple(MED2, ISOL, 2),
      Triple(MED2, FIN2, 5),
      Triple(MED2, ISOL, 6),
    ),
    // 5: it was an Alaph in fin2 or fin3; not willing to join.
    arrayOf(
      Triple(NONE, NONE, 0),
      Triple(NONE, ISOL, 2),
      Triple(ISOL, ISOL, 1),
      Triple(ISOL, ISOL, 2),
      Triple(ISOL, FIN2, 5),
      Triple(ISOL, ISOL, 6),
    ),
    // 6: it was Dalath or Rish; not willing to join.
    arrayOf(
      Triple(NONE, NONE, 0),
      Triple(NONE, ISOL, 2),
      Triple(NONE, ISOL, 1),
      Triple(NONE, ISOL, 2),
      Triple(NONE, FIN3, 5),
      Triple(NONE, ISOL, 6),
    ),
  )

/**
 * [codePoints] split into runs of one script: `(from, to, tag)`, with `to` exclusive and `tag` an
 * OpenType script tag. Characters of no script of their own (common and inherited: spaces, digits,
 * punctuation, combining marks) join the run before them, or the first run when they lead.
 */
internal fun scriptRuns(codePoints: IntArray): List<Triple<Int, Int, String>> {
  val tags = codePoints.map(::scriptOf)
  var current = tags.firstOrNull { it != null } ?: "DFLT"
  val runs = mutableListOf<Triple<Int, Int, String>>()
  var from = 0
  for (i in codePoints.indices) {
    val tag = tags[i] ?: continue
    if (tag != current) {
      if (i > from) runs += Triple(from, i, current)
      from = i
      current = tag
    }
  }
  if (codePoints.size > from) runs += Triple(from, codePoints.size, current)
  return runs
}

/** [cp]'s OpenType script tag, or null for a character of no script of its own. */
private fun scriptOf(cp: Int): String? =
  when (val script = Character.UnicodeScript.of(cp)) {
    Character.UnicodeScript.COMMON,
    Character.UnicodeScript.INHERITED,
    Character.UnicodeScript.UNKNOWN -> null
    else -> SCRIPT_TAGS[script] ?: "DFLT"
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
