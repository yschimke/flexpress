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

package ee.schimke.flexpress.codegen

import com.google.common.truth.Truth.assertWithMessage
import ee.schimke.flexpress.testFonts
import java.io.File
import org.junit.Test

/**
 * The sources in `src/debug/.../generated` are what [VariableFontCodegen] makes of [specs]: the
 * recipe in `flexpress-codegen/README.md`. Set `CODEGEN_WRITE=1` to regenerate them.
 */
class VariableFontCodegenTest {
  @Test
  fun generatedSourcesAreUpToDate() {
    val update = System.getenv("CODEGEN_WRITE") == "1"
    val stale = specs.filterNot { it.write(update) }.map { it.function }
    if (!update) {
      assertWithMessage("stale generated sources; run with CODEGEN_WRITE=1").that(stale).isEmpty()
    }
  }

  internal class Spec(
    val function: String,
    val text: String,
    val axes: List<String>,
    val pixelSize: Float? = null,
    val tolerancePixels: Float = 1f / 16,
    val standalone: Boolean = false,
  ) {
    val font = testFonts[1]

    fun write(update: Boolean): Boolean =
      VariableFontCodegen.write(
        SOURCE_ROOT,
        PACKAGE,
        function,
        font.font,
        font.name,
        text,
        axes,
        pixelSize = pixelSize,
        tolerancePixels = tolerancePixels,
        fileHeader = LICENSE,
        update = update,
        standalone = standalone,
      )
  }

  companion object {
    const val PACKAGE = "ee.schimke.flexpress.generated"
    val SOURCE_ROOT = File("src/debug/kotlin")

    val LICENSE =
      """
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
      """
        .trimIndent()

    internal val specs =
      listOf(
        Spec("HamburgWght", "Hamburg", listOf("wght")),
        Spec("HamburgWghtSlnt", "Hamburg", listOf("wght", "slnt")),
        Spec("HamburgWghtSlntAt44Px", "Hamburg", listOf("wght", "slnt"), pixelSize = 44f),
        Spec("HelloWearWght", "Hello, Wear OS 12:45!", listOf("wght")),
        // Drawing it themselves, with no flexpress: key outlines, then forms.
        Spec("HamburgWghtStandalone", "Hamburg", listOf("wght"), standalone = true),
        Spec("HamburgWghtSlntStandalone", "Hamburg", listOf("wght", "slnt"), standalone = true),
      )
  }
}
