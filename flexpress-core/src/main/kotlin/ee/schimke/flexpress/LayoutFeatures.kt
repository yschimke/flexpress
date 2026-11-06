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
 * is set. When the table is version 1.1 or later and has `FeatureVariations` whose condition set
 * matches normalized [coords], the substituted feature tables are used. A script the table lacks
 * falls back to `DFLT`, then `latn`.
 */
internal fun FontBytes.featureLookups(
  table: Int,
  script: String,
  features: Set<String>,
  required: Boolean,
  coords: FloatArray = FloatArray(0),
): Map<String, List<Int>> {
  val scriptList = table + u16(table + 4)
  val featureList = table + u16(table + 6)
  val langSys =
    defaultLangSys(scriptList, script)
      ?: defaultLangSys(scriptList, "DFLT")
      ?: defaultLangSys(scriptList, "latn")
      ?: return emptyMap()
  val substitution = featureSubstitution(table, coords)
  val result = mutableMapOf<String, List<Int>>()
  val requiredIndex = u16(langSys + 2)
  val featureIndices =
    List(u16(langSys + 4)) { u16(langSys + 6 + it * 2) } +
      if (required && requiredIndex != 0xFFFF) listOf(requiredIndex) else emptyList()
  for (f in featureIndices) {
    val record = featureList + 2 + f * 6
    val tag = tag(record)
    if (tag !in features && !(required && f == requiredIndex)) continue
    val feature =
      (if (substitution != 0) substitutedFeature(substitution, f) else null)
        ?: (featureList + u16(record + 4))
    result[tag] = result[tag].orEmpty() + List(u16(feature + 2)) { u16(feature + 4 + it * 2) }
  }
  return result
}

/**
 * The `FeatureTableSubstitution` offset in [table]'s `FeatureVariations` (v1.1+) whose
 * `ConditionSet` first matches normalized [coords], or 0 when none applies.
 */
private fun FontBytes.featureSubstitution(table: Int, coords: FloatArray): Int {
  if (coords.isEmpty() || u16(table) != 1 || u16(table + 2) < 1) return 0
  val variationsOffset = u32(table + 10).toInt()
  if (variationsOffset == 0) return 0
  val variations = table + variationsOffset
  val recordCount = u32(variations + 4).toInt()
  for (r in 0 until recordCount) {
    val record = variations + 8 + r * 8
    val conditionSetOffset = u32(record).toInt()
    val substOffset = u32(record + 4).toInt()
    if (substOffset == 0) continue
    if (conditionSetOffset == 0 || matchesConditionSet(variations + conditionSetOffset, coords)) {
      return variations + substOffset
    }
  }
  return 0
}

private fun FontBytes.matchesConditionSet(conditionSet: Int, coords: FloatArray): Boolean {
  val count = u16(conditionSet)
  for (c in 0 until count) {
    val condition = conditionSet + u32(conditionSet + 2 + c * 4).toInt()
    if (u16(condition) != 1) return false
    val axisIndex = u16(condition + 2)
    val v = coords.getOrElse(axisIndex) { 0f }
    if (v < f2dot14(condition + 4) || v > f2dot14(condition + 6)) return false
  }
  return true
}

private fun FontBytes.substitutedFeature(substitution: Int, featureIndex: Int): Int? {
  val count = u16(substitution + 4)
  for (i in 0 until count) {
    val record = substitution + 6 + i * 6
    if (u16(record) == featureIndex) {
      return substitution + u32(record + 2).toInt()
    }
  }
  return null
}

/**
 * In the `GSUB` or `GPOS` table at [table], the tag of [script]'s default language system's
 * required feature, or null when it has none. The script falls back as in [featureLookups].
 */
internal fun FontBytes.requiredFeature(table: Int, script: String): String? {
  val scriptList = table + u16(table + 4)
  val langSys =
    defaultLangSys(scriptList, script)
      ?: defaultLangSys(scriptList, "DFLT")
      ?: defaultLangSys(scriptList, "latn")
      ?: return null
  val index = u16(langSys + 2)
  return if (index == 0xFFFF) null else tag(table + u16(table + 6) + 2 + index * 6)
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
