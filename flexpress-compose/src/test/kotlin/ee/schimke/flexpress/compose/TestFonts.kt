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

package ee.schimke.flexpress.compose

import ee.schimke.flexpress.VariableFont
import ee.schimke.flexpress.fontsDir
import java.io.File

/**
 * A test font: one of the repository's shared fonts in `fonts/res/raw` (Basic Latin subsets of OFL
 * variable fonts, see `fonts/README.md`), read from its file. The same fonts are added to this
 * module's debug resources, as `ee.schimke.flexpress.compose.R.raw.<resource>`.
 */
internal class TestFont(val name: String, val resource: String, val axes: List<String>) {
  val file: File = File(fontsDir, "res/raw/$resource.ttf")

  val font: VariableFont by lazy { VariableFont.parse(file.readBytes()) }

  override fun toString(): String = name
}

internal val googleSansFlex =
  TestFont("Google Sans Flex", "google_sans_flex_wght_rond", listOf("wght", "ROND"))

internal val robotoFlex =
  TestFont("Roboto Flex", "roboto_flex", listOf("wght", "wdth", "slnt", "opsz", "GRAD", "XTRA"))

internal val testFonts: List<TestFont> =
  listOf(
    googleSansFlex,
    robotoFlex,
    TestFont("Recursive", "recursive", listOf("wght", "slnt", "CASL", "MONO")),
    TestFont("Fraunces", "fraunces", listOf("wght", "opsz", "SOFT")),
    TestFont("Noto Sans", "noto_sans", listOf("wght", "wdth")),
    TestFont("Inter", "inter", listOf("wght", "opsz")),
  )
