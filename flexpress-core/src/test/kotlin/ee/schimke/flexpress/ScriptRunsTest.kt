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

class ScriptRunsTest {
  @Test
  fun commonCharactersJoinTheScriptBeforeThem() {
    fun runs(text: String) =
      scriptRuns(text.codePoints().toArray()).map { (from, to, tag) ->
        text.substring(from, to) to tag
      }
    assertThat(runs("a1 αβ, γ")).containsExactly("a1 " to "latn", "αβ, γ" to "grek").inOrder()
    // Leading common characters take the first script; text of none at all is DFLT.
    assertThat(runs("(α) b")).containsExactly("(α) " to "grek", "b" to "latn").inOrder()
    assertThat(runs("12 ?")).containsExactly("12 ?" to "DFLT")
    // A combining mark stays with its letter.
    assertThat(runs("é́α")).containsExactly("é́" to "latn", "α" to "grek").inOrder()
  }

  @Test
  fun eachScriptInARunTakesItsOwnFeatures() {
    // latn has a ligature-like 1 → 2; grek has no features. 'a' and 'α' are both glyph 1.
    val gsub = gsub(scripts = mapOf("grek" to emptyList(), "latn" to listOf(0)))
    val glyphId = { cp: Int -> if (cp == 'a'.code || cp == 'α'.code) 1 else 0 }
    fun shape(text: String) = shapeText(text, glyphId, gsub).map { it.id }
    assertThat(shape("aα")).containsExactly(2, 1).inOrder()
    assertThat(shape("αa")).containsExactly(1, 2).inOrder()
  }

  /**
   * A `GSUB` table with one `liga` feature whose single substitution maps glyph 1 to 2, and a
   * default language system for each of [scripts] listing the features it has.
   */
  private fun gsub(scripts: Map<String, List<Int>>): Gsub {
    val subtable = u16(2, 8, 1, 2) + u16(1, 1, 1)
    // Script list: records, then each script table with its default language system inline.
    val recordsSize = 2 + scripts.size * 6
    var scriptAt = recordsSize
    var records = u16(scripts.size)
    var tables = ByteArray(0)
    for ((tag, features) in scripts) {
      records += tag(tag) + u16(scriptAt)
      val table = u16(4, 0) + u16(0, 0xFFFF, features.size) + u16(*features.toIntArray())
      tables += table
      scriptAt += table.size
    }
    val scriptList = records + tables
    val featureList = u16(1) + tag("liga") + u16(8) + u16(0, 1, 0)
    val lookupList = u16(1, 4) + u16(SINGLE, 0, 1, 8) + subtable
    val header = 10
    val table =
      u16(1, 0, header, header + scriptList.size, header + scriptList.size + featureList.size) +
        scriptList +
        featureList +
        lookupList
    return Gsub(FontBytes(table), 0) { 0 }
  }

  private fun u16(vararg values: Int) =
    ByteArray(values.size * 2) { (values[it / 2] shr (if (it % 2 == 0) 8 else 0)).toByte() }

  private fun tag(tag: String) = tag.toByteArray(Charsets.US_ASCII)

  private companion object {
    const val SINGLE = 1
  }
}
