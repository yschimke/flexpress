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

/**
 * In the `GSUB` or `GPOS` table at [table], each of [features] that [script]'s default language
 * system has, with its lookup indices, and the language system's required feature when [required]
 * is set. A script the table lacks falls back to `DFLT`, then `latn`.
 */
internal fun FontBytes.featureLookups(
  table: Int,
  script: String,
  features: Set<String>,
  required: Boolean,
): Map<String, List<Int>> {
  val scriptList = table + u16(table + 4)
  val featureList = table + u16(table + 6)
  val langSys =
    defaultLangSys(scriptList, script)
      ?: defaultLangSys(scriptList, "DFLT")
      ?: defaultLangSys(scriptList, "latn")
      ?: return emptyMap()
  val result = mutableMapOf<String, List<Int>>()
  val requiredIndex = u16(langSys + 2)
  val featureIndices =
    List(u16(langSys + 4)) { u16(langSys + 6 + it * 2) } +
      if (required && requiredIndex != 0xFFFF) listOf(requiredIndex) else emptyList()
  for (f in featureIndices) {
    val record = featureList + 2 + f * 6
    val tag = tag(record)
    if (tag !in features && !(required && f == requiredIndex)) continue
    val feature = featureList + u16(record + 4)
    result[tag] = result[tag].orEmpty() + List(u16(feature + 2)) { u16(feature + 4 + it * 2) }
  }
  return result
}

/** The default language system of script [tag] in [scriptList], or its first one. */
private fun FontBytes.defaultLangSys(scriptList: Int, tag: String): Int? {
  for (s in 0 until u16(scriptList)) {
    val record = scriptList + 2 + s * 6
    if (tag(record) != tag) continue
    val script = scriptList + u16(record + 4)
    val default = u16(script)
    return when {
      default != 0 -> script + default
      u16(script + 2) > 0 -> script + u16(script + 4 + 4)
      else -> null
    }
  }
  return null
}
