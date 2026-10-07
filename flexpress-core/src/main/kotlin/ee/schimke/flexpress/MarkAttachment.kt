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

/** Where a mark glyph goes: at glyph [base]'s origin, offset by ([dx], [dy]) in font units. */
internal data class Attachment(val base: Int, val dx: Float, val dy: Float)

/**
 * Mark positioning from a font's `GPOS` table: the `mark` and `mkmk` features' mark-to-base (4),
 * mark-to-ligature (5) and mark-to-mark (6) lookups for a run's script, directly or through
 * extension lookups, with anchor variation deltas from the `GDEF` item variation store. Lookup
 * flags, mark filtering sets and mark attachment types limit which marks a lookup positions.
 *
 * A mark attaches to the nearest glyph before it, in logical order, that is not a mark (for
 * mark-to-base and mark-to-ligature) or to the nearest mark before it that the lookup's filter
 * admits (for mark-to-mark). A mark on a ligature takes the ligature's last component's anchor,
 * since the shaper does not track which component each mark followed. Cursive attachment (3) is not
 * applied.
 */
internal class MarkAttachment(
  private val data: FontBytes,
  private val offset: Int,
  private val variations: ItemVariationStore?,
  /** `GDEF`'s glyph class of a glyph: 1 base, 2 ligature, 3 mark, 4 component, 0 none. */
  private val glyphClassOf: (Int) -> Int,
  /** `GDEF`'s mark attachment class of a glyph, or 0. */
  private val markAttachClassOf: (Int) -> Int = { 0 },
  /** Whether a glyph is in one of `GDEF`'s mark glyph sets, by set index then glyph. */
  private val inMarkSet: (Int, Int) -> Boolean = { _, _ -> true },
) {
  /** A mark lookup: its type, flag, mark filtering set (or -1) and subtables. */
  private class Lookup(val type: Int, val flag: Int, val filterSet: Int, val subtables: List<Int>)

  private val lookupsByScript = mutableMapOf<String, List<Lookup>>()

  /** The mark lookups of [script]'s default language system's `mark` and `mkmk`, in order. */
  private fun lookups(script: String): List<Lookup> =
    lookupsByScript.getOrPut(script) {
      val lookupList = offset + data.u16(offset + 8)
      val indices = sortedSetOf<Int>()
      data.featureLookups(offset, script, MARK_FEATURES, required = false).values.forEach {
        indices += it
      }
      indices.mapNotNull { index ->
        val lookup = lookupList + data.u16(lookupList + 2 + index * 2)
        var type = data.u16(lookup)
        val flag = data.u16(lookup + 2)
        val count = data.u16(lookup + 4)
        var subtables = List(count) { s -> lookup + data.u16(lookup + 6 + s * 2) }
        val filterSet =
          if (flag and USE_MARK_FILTERING_SET != 0) data.u16(lookup + 6 + count * 2) else -1
        if (type == EXTENSION && subtables.isNotEmpty()) {
          type = data.u16(subtables[0] + 2)
          subtables = subtables.map { it + data.u32(it + 4).toInt() }
        }
        if (type in MARK_TO_BASE..MARK_TO_MARK) Lookup(type, flag, filterSet, subtables) else null
      }
    }

  /**
   * The attachment of each of [glyphs], a run of [script] in logical order, at normalized [coords];
   * null for a glyph that does not attach. Later lookups override earlier ones, as a shaper applies
   * them.
   */
  fun attach(glyphs: List<Int>, script: String, coords: FloatArray): List<Attachment?> {
    val result = arrayOfNulls<Attachment>(glyphs.size)
    for (lookup in lookups(script)) {
      for (i in glyphs.indices) {
        // The lookup applies only to the marks its flag and filtering set admit.
        if (skips(lookup, glyphs[i])) continue
        for (sub in lookup.subtables) {
          val attachment =
            when (lookup.type) {
              MARK_TO_BASE,
              MARK_TO_LIGATURE -> toBase(sub, lookup.type, glyphs, i, coords)
              else -> toMark(sub, lookup, glyphs, i, coords)
            } ?: continue
          result[i] = attachment
          break
        }
      }
    }
    return result.toList()
  }

  /**
   * Whether [lookup] passes over [glyph]: by its class for the ignore flags, and, for a mark, by
   * the lookup's mark filtering set or mark attachment type.
   */
  private fun skips(lookup: Lookup, glyph: Int, ignoreFlags: Boolean = true): Boolean {
    val glyphClass = glyphClassOf(glyph)
    if (ignoreFlags) {
      val ignored =
        when (glyphClass) {
          BASE -> IGNORE_BASE
          LIGATURE -> IGNORE_LIGATURES
          MARK -> IGNORE_MARKS
          else -> 0
        }
      if (lookup.flag and ignored != 0) return true
    }
    if (glyphClass != MARK) return false
    if (lookup.filterSet >= 0) return !inMarkSet(lookup.filterSet, glyph)
    val attachType = lookup.flag ushr 8
    return attachType != 0 && markAttachClassOf(glyph) != attachType
  }

  private fun isMark(glyph: Int) = glyphClassOf(glyph) == MARK

  private fun toBase(
    sub: Int,
    type: Int,
    glyphs: List<Int>,
    i: Int,
    coords: FloatArray,
  ): Attachment? {
    val markIndex = data.coverageIndex(sub + data.u16(sub + 2), glyphs[i]) ?: return null
    var b = i - 1
    while (b >= 0 && isMark(glyphs[b])) b--
    if (b < 0) return null
    val baseIndex = data.coverageIndex(sub + data.u16(sub + 4), glyphs[b]) ?: return null
    val classCount = data.u16(sub + 6)
    val markArray = sub + data.u16(sub + 8)
    val markClass = data.u16(markArray + 2 + markIndex * 4)
    val markAnchor = markArray + data.u16(markArray + 2 + markIndex * 4 + 2)
    val baseAnchorOffset: Int
    val anchorBase: Int
    if (type == MARK_TO_BASE) {
      val baseArray = sub + data.u16(sub + 10)
      baseAnchorOffset = data.u16(baseArray + 2 + (baseIndex * classCount + markClass) * 2)
      anchorBase = baseArray
    } else {
      val ligatureArray = sub + data.u16(sub + 10)
      val attach = ligatureArray + data.u16(ligatureArray + 2 + baseIndex * 2)
      val components = data.u16(attach)
      if (components == 0) return null
      val component = components - 1
      baseAnchorOffset = data.u16(attach + 2 + (component * classCount + markClass) * 2)
      anchorBase = attach
    }
    if (baseAnchorOffset == 0) return null
    val (bx, by) = anchor(anchorBase + baseAnchorOffset, coords)
    val (mx, my) = anchor(markAnchor, coords)
    return Attachment(b, bx - mx, by - my)
  }

  private fun toMark(
    sub: Int,
    lookup: Lookup,
    glyphs: List<Int>,
    i: Int,
    coords: FloatArray,
  ): Attachment? {
    val markIndex = data.coverageIndex(sub + data.u16(sub + 2), glyphs[i]) ?: return null
    // The mark before it, passing over marks the lookup's filter excludes, as HarfBuzz does.
    var m = i - 1
    while (m >= 0 && isMark(glyphs[m]) && skips(lookup, glyphs[m], ignoreFlags = false)) m--
    if (m < 0 || !isMark(glyphs[m])) return null
    val mark2Index = data.coverageIndex(sub + data.u16(sub + 4), glyphs[m]) ?: return null
    val classCount = data.u16(sub + 6)
    val mark1Array = sub + data.u16(sub + 8)
    val markClass = data.u16(mark1Array + 2 + markIndex * 4)
    val markAnchor = mark1Array + data.u16(mark1Array + 2 + markIndex * 4 + 2)
    val mark2Array = sub + data.u16(sub + 10)
    val mark2AnchorOffset = data.u16(mark2Array + 2 + (mark2Index * classCount + markClass) * 2)
    if (mark2AnchorOffset == 0) return null
    val (bx, by) = anchor(mark2Array + mark2AnchorOffset, coords)
    val (mx, my) = anchor(markAnchor, coords)
    return Attachment(m, bx - mx, by - my)
  }

  /** The anchor at [table], with its variation deltas at [coords] for format 3. */
  private fun anchor(table: Int, coords: FloatArray): Pair<Float, Float> {
    var x = data.i16(table + 2).toFloat()
    var y = data.i16(table + 4).toFloat()
    if (data.u16(table) == 3) {
      x += device(table, data.u16(table + 6), coords)
      y += device(table, data.u16(table + 8), coords)
    }
    return x to y
  }

  private fun device(base: Int, device: Int, coords: FloatArray): Float {
    val store = variations ?: return 0f
    if (device == 0 || data.u16(base + device + 4) != VARIATION_INDEX) return 0f
    return store.delta(data.u16(base + device), data.u16(base + device + 2), coords)
  }

  private companion object {
    const val MARK_TO_BASE = 4
    const val MARK_TO_LIGATURE = 5
    const val MARK_TO_MARK = 6
    const val EXTENSION = 9
    const val BASE = 1
    const val LIGATURE = 2
    const val MARK = 3
    const val IGNORE_BASE = 2
    const val IGNORE_LIGATURES = 4
    const val IGNORE_MARKS = 8
    const val USE_MARK_FILTERING_SET = 0x10
    val MARK_FEATURES = setOf("mark", "mkmk")
    const val VARIATION_INDEX = 0x8000
  }
}
