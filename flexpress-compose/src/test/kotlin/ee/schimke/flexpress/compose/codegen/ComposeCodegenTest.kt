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

package ee.schimke.flexpress.compose.codegen

import com.google.common.truth.Truth.assertWithMessage
import ee.schimke.flexpress.codegen.CodegenTarget
import ee.schimke.flexpress.codegen.VariableFontCodegen
import ee.schimke.flexpress.compose.robotoFlex
import java.io.File
import org.junit.Test

/**
 * The sources in `src/debug/.../compose/generated` are what [VariableFontCodegen] makes of [specs]
 * for Compose UI. Set `CODEGEN_WRITE=1` to regenerate them.
 */
class ComposeCodegenTest {
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
    val standalone: Boolean,
  ) {
    fun write(update: Boolean): Boolean =
      VariableFontCodegen.write(
        File("src/debug/kotlin"),
        "ee.schimke.flexpress.compose.generated",
        function,
        robotoFlex.font,
        robotoFlex.name,
        text,
        axes,
        fileHeader =
          File("src/test/kotlin/ee/schimke/flexpress/compose/TestFonts.kt")
            .readLines()
            .take(15)
            .joinToString("\n"),
        update = update,
        target = CodegenTarget.ComposeUi,
        standalone = standalone,
      )
  }

  companion object {
    internal val specs =
      listOf(
        // Through flexpress-compose's VariableFontText.
        Spec("HamburgWght", "Hamburg", listOf("wght"), standalone = false),
        // Drawing it themselves, with only Compose UI: key outlines, then forms.
        Spec("HamburgWghtStandalone", "Hamburg", listOf("wght"), standalone = true),
        Spec("HamburgWghtSlntStandalone", "Hamburg", listOf("wght", "slnt"), standalone = true),
      )
  }
}
