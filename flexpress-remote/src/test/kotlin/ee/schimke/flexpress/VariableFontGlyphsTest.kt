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

import androidx.compose.remote.creation.compose.state.rf
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** The characters a [VariableFontGlyphs] can draw from a RemoteString. */
class VariableFontGlyphsTest {
  @Test
  fun charactersOutsideTheBmpAreRefused() {
    val e =
      assertThrows(IllegalArgumentException::class.java) {
        variableFontGlyphs(font, "a😀", mapOf("wght" to 400f.rf), emptyMap(), emptyMap())
      }
    assertThat(e).hasMessageThat().contains("U+1F600")
  }

  private companion object {
    val font = testFonts[1].font
  }
}
