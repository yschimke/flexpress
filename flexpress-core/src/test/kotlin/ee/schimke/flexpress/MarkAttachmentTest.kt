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
import java.io.File
import org.junit.Test

class MarkAttachmentTest {
  /**
   * A font may leave out `GDEF` glyph classes: marks are then known only by the lookups' coverage,
   * and still attach.
   */
  @Test
  fun marksAttachWithoutGlyphClasses() {
    val bytes = File(fontsDir, "res/raw/noto_sans_arabic.ttf").readBytes()
    val data = FontBytes(bytes)
    val gpos = tableOffset(data, "GPOS")
    // Meem and fatha, in logical order: shape returns them right to left.
    val glyphs = VariableFont.parse(bytes).shape("مَ").reversed()
    val noClasses = MarkAttachment(data, gpos, null, glyphClassOf = { 0 }, hasGlyphClasses = false)
    val attachments = noClasses.attach(glyphs, "arab", FloatArray(1))
    assertThat(attachments[0]).isNull()
    assertThat(attachments[1]?.base).isEqualTo(0)
  }

  /** The offset of table [tag] in the font [data], from its table directory. */
  private fun tableOffset(data: FontBytes, tag: String): Int {
    for (i in 0 until data.u16(4)) {
      val record = 12 + i * 16
      if (data.tag(record) == tag) return data.u32(record + 8).toInt()
    }
    error("no $tag table")
  }
}
