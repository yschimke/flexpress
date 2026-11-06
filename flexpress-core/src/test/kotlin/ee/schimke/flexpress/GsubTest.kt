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

/** [Gsub] on minimal hand-built tables, for cases the bundled fonts don't have. */
class GsubTest {
  @Test
  fun requiredFeatureAppliesOnceAcrossStages() {
    // 1 → 2 and 2 → 3 in one lookup: applied twice, glyph 1 would become 3.
    val gsub = gsub(SINGLE, u16(2, 10, 2, 2, 3) + u16(1, 2, 1, 2))
    val stages = listOf(setOf("isol"), setOf("fina"), setOf("medi"))
    assertThat(gsub.substitute(listOf(1), listOf(0), "DFLT", stages, emptyMap())).containsExactly(2)
  }

  @Test
  fun requiredFeatureAppliesInTheStageOfItsTag() {
    // Required fina maps 1 → 2; optional isol maps 2 → 3. Run in fina's own stage, after isol,
    // glyph 1 becomes 2; run in the first stage, isol would then turn it into 3.
    val gsub =
      gsub(
        listOf(
          Feature("fina", SINGLE, u16(2, 8, 1, 2) + u16(1, 1, 1)),
          Feature("isol", SINGLE, u16(2, 8, 1, 3) + u16(1, 1, 2)),
        ),
        required = 0,
      )
    val stages = listOf(setOf("isol"), setOf("fina"))
    assertThat(gsub.substitute(listOf(1), listOf(0), "DFLT", stages, emptyMap())).containsExactly(2)
  }

  @Test
  fun nullContextRuleSetMatchesNothing() {
    // Format 1, glyph 1 covered, with a null rule set offset.
    val gsub = gsub(CONTEXT, u16(1, 8, 1, 0) + u16(1, 1, 1))
    assertThat(gsub.substitute(listOf(1, 1), "DFLT", emptySet())).containsExactly(1, 1)
  }

  @Test
  fun nullChainingRuleSetMatchesNothing() {
    val gsub = gsub(CHAINING, u16(1, 8, 1, 0) + u16(1, 1, 1))
    assertThat(gsub.substitute(listOf(1, 1), "DFLT", emptySet())).containsExactly(1, 1)
  }

  @Test
  fun featureVariationsSubstituteAlternateFeatureAtMatchingCoords() {
    val scriptList = u16(1) + tag("DFLT") + u16(8) + u16(4, 0) + u16(0, 0xFFFF, 1, 0)
    // Default feature 0 ("rvrn") has 0 lookups; FeatureVariations substitutes lookup 0 when
    // axis 0 is in [0.5, 1.0].
    val featureList = u16(1) + tag("rvrn") + u16(8) + u16(0, 0)
    val subtable = u16(1, 6, 5) + u16(1, 1, 1) // format 1, coverage [1], delta +5 (1 -> 6)
    val lookupList = u16(1, 4) + u16(SINGLE, 0, 1, 8) + subtable
    val conditionSet = u16(1, 0, 6) + u16(1, 0, 0x2000, 0x4000) // axis 0 in [0.5, 1.0]
    val featureSubst = u16(1, 0, 1, 0, 0, 12) + u16(0, 1, 0) // feature 0 -> lookup [0]
    val featureVariations =
      u16(1, 0, 0, 1, 0, 16, 0, 16 + conditionSet.size) + conditionSet + featureSubst
    val header = 14
    val flOffset = header + scriptList.size
    val llOffset = flOffset + featureList.size
    val fvOffset = llOffset + lookupList.size
    val table =
      u16(1, 1, header, flOffset, llOffset, 0, fvOffset) +
        scriptList +
        featureList +
        lookupList +
        featureVariations
    val gsub = Gsub(FontBytes(table), 0) { 0 }
    assertThat(gsub.substitute(listOf(1), "DFLT", setOf("rvrn"), floatArrayOf(0f)))
      .containsExactly(1)
    assertThat(gsub.substitute(listOf(1), "DFLT", setOf("rvrn"), floatArrayOf(0.25f)))
      .containsExactly(1)
    assertThat(gsub.substitute(listOf(1), "DFLT", setOf("rvrn"), floatArrayOf(0.75f)))
      .containsExactly(6)
  }

  @Test
  fun ligatureTracksSkippedMarkComponents() {
    // Ligature subtable: 1 + 2 -> 9 (format 1, coverage [1], 1 ligature set, 1 ligature 9 with
    // [2]).
    val subtable = u16(1, 8, 1, 14) + u16(1, 1, 1) + u16(1, 4) + u16(9, 2, 2)
    // Lookup flag 0x0008 (IGNORE_MARKS), GDEF class 3 (mark) for glyphs 10 and 11.
    val lookupList = u16(1, 4) + u16(4, 0x0008, 1, 8) + subtable
    val scriptList = u16(1) + tag("DFLT") + u16(8) + u16(4, 0) + u16(0, 0xFFFF, 1, 0)
    val featureList = u16(1) + tag("liga") + u16(8) + u16(0, 1, 0)
    val header = 10
    val table =
      u16(1, 0, header, header + scriptList.size, header + scriptList.size + featureList.size) +
        scriptList +
        featureList +
        lookupList
    val gsub = Gsub(FontBytes(table), 0) { if (it in 10..11) 3 else 1 }
    // Input: base 1, mark 10 (on component 0), base 2, mark 11 (after component 1).
    val (ids, components) =
      gsub.substituteWithComponents(
        listOf(1, 10, 2, 11),
        listOf(0, 0, 0, 0),
        "DFLT",
        listOf(setOf("liga")),
        emptyMap(),
      )
    assertThat(ids).containsExactly(9, 10, 11).inOrder()
    assertThat(components).containsExactly(-1, 0, -1).inOrder()
  }

  /**
   * A `GSUB` table whose `DFLT` script's default language system has one required feature, with one
   * lookup of [type] holding one [subtable].
   */
  private fun gsub(type: Int, subtable: ByteArray): Gsub =
    gsub(listOf(Feature("rqrd", type, subtable)), required = 0)

  /** A feature of a test table: its [tag] and one lookup of [type] holding one [subtable]. */
  private class Feature(val tag: String, val type: Int, val subtable: ByteArray)

  /**
   * A `GSUB` table whose `DFLT` script's default language system has [features], feature `i` with
   * lookup `i`, of which feature [required] is the required one and the rest are optional.
   */
  private fun gsub(features: List<Feature>, required: Int): Gsub {
    val n = features.size
    val optional = features.indices.filter { it != required }
    // Script list: DFLT, whose script table has a default language system and no others.
    val scriptList = u16(1) + tag("DFLT") + u16(8) + u16(4, 0) + u16(0, required, optional.size)
    val scripts = scriptList + u16(*optional.toIntArray())
    // Feature list: feature i with lookup i.
    val featureRecords = 2 + n * 6
    val featureList =
      u16(n) +
        features
          .mapIndexed { i, f -> tag(f.tag) + u16(featureRecords + i * 6) }
          .fold(ByteArray(0), ByteArray::plus) +
        features.indices.map { u16(0, 1, it) }.fold(ByteArray(0), ByteArray::plus)
    // Lookup list: lookup i with its one subtable, after the lookup tables.
    val lookupTables = 2 + n * 2
    var subtableAt = lookupTables + n * 8
    val lookupOffsets = mutableListOf<Int>()
    var lookups = ByteArray(0)
    for ((i, f) in features.withIndex()) {
      lookupOffsets += lookupTables + i * 8
      lookups += u16(f.type, 0, 1, subtableAt - (lookupTables + i * 8))
      subtableAt += f.subtable.size
    }
    val lookupList =
      u16(n) +
        u16(*lookupOffsets.toIntArray()) +
        lookups +
        features.map { it.subtable }.fold(ByteArray(0), ByteArray::plus)
    val header = 10
    val table =
      u16(1, 0, header, header + scripts.size, header + scripts.size + featureList.size) +
        scripts +
        featureList +
        lookupList
    return Gsub(FontBytes(table), 0) { 0 }
  }

  private fun u16(vararg values: Int) =
    ByteArray(values.size * 2) { (values[it / 2] shr (if (it % 2 == 0) 8 else 0)).toByte() }

  private fun tag(tag: String) = tag.toByteArray(Charsets.US_ASCII)

  private companion object {
    const val SINGLE = 1
    const val CONTEXT = 5
    const val CHAINING = 6
  }
}
