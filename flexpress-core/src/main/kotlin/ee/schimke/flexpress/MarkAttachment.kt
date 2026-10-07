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
 * mark-to-ligature (5) and mark-to-mark (6) lookups, directly or through extension lookups, with
 * anchor variation deltas from the `GDEF` item variation store.
 *
 * A mark attaches to the nearest glyph before it, in logical order, that is not a mark (for
 * mark-to-base and mark-to-ligature) or to the mark just before it (for mark-to-mark). A mark on a
 * ligature takes the ligature's last component's anchor, since the shaper does not track which
 * component each mark followed. Cursive attachment (3) is not applied.
 */
internal class MarkAttachment(
  private val data: FontBytes,
  private val offset: Int,
  private val variations: ItemVariationStore?,
  /** `GDEF`'s glyph class of a glyph: 1 base, 2 ligature, 3 mark, 4 component, 0 none. */
  private val glyphClassOf: (Int) -> Int,
) {
  /** Each mark lookup's type and subtables, in lookup order. */
  private val lookups: List<Pair<Int, List<Int>>> = run {
    val featureList = offset + data.u16(offset + 6)
    val lookupList = offset + data.u16(offset + 8)
    val indices = sortedSetOf<Int>()
    for (f in 0 until data.u16(featureList)) {
      val record = featureList + 2 + f * 6
      if (data.tag(record) != "mark" && data.tag(record) != "mkmk") continue
      val feature = featureList + data.u16(record + 4)
      for (l in 0 until data.u16(feature + 2)) indices += data.u16(feature + 4 + l * 2)
    }
    indices.mapNotNull { index ->
      val lookup = lookupList + data.u16(lookupList + 2 + index * 2)
      var type = data.u16(lookup)
      var subtables = List(data.u16(lookup + 4)) { s -> lookup + data.u16(lookup + 6 + s * 2) }
      if (type == EXTENSION && subtables.isNotEmpty()) {
        type = data.u16(subtables[0] + 2)
        subtables = subtables.map { it + data.u32(it + 4).toInt() }
      }
      if (type in MARK_TO_BASE..MARK_TO_MARK) type to subtables else null
    }
  }

  /**
   * The attachment of each of [glyphs], a run in logical order, at normalized [coords]; null for a
   * glyph that does not attach. Later lookups override earlier ones, as a shaper applies them.
   */
  fun attach(glyphs: List<Int>, coords: FloatArray): List<Attachment?> {
    val result = arrayOfNulls<Attachment>(glyphs.size)
    for ((type, subtables) in lookups) {
      for (i in glyphs.indices) {
        for (sub in subtables) {
          val attachment =
            when (type) {
              MARK_TO_BASE,
              MARK_TO_LIGATURE -> toBase(sub, type, glyphs, i, coords)
              else -> toMark(sub, glyphs, i, coords)
            } ?: continue
          result[i] = attachment
          break
        }
      }
    }
    return result.toList()
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

  private fun toMark(sub: Int, glyphs: List<Int>, i: Int, coords: FloatArray): Attachment? {
    if (i == 0) return null
    val markIndex = data.coverageIndex(sub + data.u16(sub + 2), glyphs[i]) ?: return null
    val m = i - 1
    if (!isMark(glyphs[m])) return null
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
    const val MARK = 3
    const val VARIATION_INDEX = 0x8000
  }
}
