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

  /**
   * A `GSUB` table whose `DFLT` script's default language system has one required feature, with one
   * lookup of [type] holding one [subtable].
   */
  private fun gsub(type: Int, subtable: ByteArray): Gsub {
    val table =
      u16(1, 0, SCRIPT_LIST, FEATURE_LIST, LOOKUP_LIST) +
        // Script list: DFLT, whose script table has a default language system and no others.
        u16(1) +
        tag("DFLT") +
        u16(8) +
        u16(4, 0) +
        // Language system: required feature 0, no other features.
        u16(0, 0, 0) +
        // Feature list: feature 0 with lookup 0.
        u16(1) +
        tag("rqrd") +
        u16(8) +
        u16(0, 1, 0) +
        // Lookup list: lookup 0 with its one subtable.
        u16(1, 4) +
        u16(type, 0, 1, 8) +
        subtable
    return Gsub(FontBytes(table), 0) { 0 }
  }

  private fun u16(vararg values: Int) =
    ByteArray(values.size * 2) { (values[it / 2] shr (if (it % 2 == 0) 8 else 0)).toByte() }

  private fun tag(tag: String) = tag.toByteArray(Charsets.US_ASCII)

  private companion object {
    const val SINGLE = 1
    const val CONTEXT = 5
    const val CHAINING = 6
    const val SCRIPT_LIST = 10
    const val FEATURE_LIST = 28
    const val LOOKUP_LIST = 42
  }
}
