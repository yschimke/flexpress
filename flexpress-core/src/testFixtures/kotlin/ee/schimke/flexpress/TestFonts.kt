@file:OptIn(InternalFlexpressApi::class)

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

import java.io.File

/**
 * A test font: a Basic Latin subset of an OFL variable font from google/fonts, with the axes under
 * test kept and every other axis pinned to its default (see `fonts/README.md`).
 *
 * @param resource The file name under `fonts/res/raw`, without `.ttf`.
 * @param axes The axes the tests animate, all of which the subset keeps.
 */
/** The repository's `fonts` directory, from any module. */
val fontsDir: File =
  generateSequence(File("").absoluteFile) { it.parentFile }
    .map { File(it, "fonts") }
    .first { File(it, "res/raw").isDirectory }

class TestFont(val name: String, val resource: String, val axes: List<String>) {
  val font: VariableFont by lazy {
    VariableFont.parse(File(fontsDir, "res/raw/$resource.ttf").readBytes())
  }

  val golden: String = "${resource}_golden.json.gz"

  override fun toString(): String = name
}

val googleSansFlex =
  TestFont("Google Sans Flex", "google_sans_flex_wght_rond", listOf("wght", "ROND"))

val testFonts: List<TestFont> =
  listOf(
    googleSansFlex,
    TestFont("Roboto Flex", "roboto_flex", listOf("wght", "wdth", "slnt", "opsz", "GRAD", "XTRA")),
    // CRSV (cursive) is left out: it mostly swaps glyphs through GSUB feature variations, which
    // this reader's cmap-only layout does not apply.
    TestFont("Recursive", "recursive", listOf("wght", "slnt", "CASL", "MONO")),
    // WONK is left out for the same reason as Recursive's CRSV.
    TestFont("Fraunces", "fraunces", listOf("wght", "opsz", "SOFT")),
    TestFont("Noto Sans", "noto_sans", listOf("wght", "wdth")),
    TestFont("Inter", "inter", listOf("wght", "opsz")),
  )

val testFont: VariableFont
  get() = googleSansFlex.font
