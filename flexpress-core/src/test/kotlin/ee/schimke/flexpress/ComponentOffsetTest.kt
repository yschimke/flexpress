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

/** A composite component's offset is transformed with it only when the font says so. */
class ComponentOffsetTest {
  @Test
  fun unscaledOffsetIsInTheCompositesUnits() {
    val c = Glyf.Component(0, 10, 20, 2f, 0f, 0f, 3f)
    assertThat(c.offsetX(10f, 20f)).isEqualTo(10f)
    assertThat(c.offsetY(10f, 20f)).isEqualTo(20f)
  }

  @Test
  fun scaledOffsetIsTransformedWithTheComponent() {
    val c = Glyf.Component(0, 10, 20, 2f, 0.5f, 0.25f, 3f, scaledOffset = true)
    assertThat(c.offsetX(10f, 20f)).isEqualTo(10f * 2f + 20f * 0.25f)
    assertThat(c.offsetY(10f, 20f)).isEqualTo(10f * 0.5f + 20f * 3f)
    val x = LinearForm(10f)
    val y = LinearForm(20f)
    assertThat(c.offsetX(x, y).constant).isEqualTo(25f)
    assertThat(c.offsetY(x, y).constant).isEqualTo(65f)
  }
}
