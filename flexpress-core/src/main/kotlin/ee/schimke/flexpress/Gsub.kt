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

import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap

/**
 * Glyph substitution from a font's `GSUB` table: the lookups of the requested features, for a
 * script, applied to a run of glyphs in lookup-list order, as a shaper applies them.
 *
 * It handles single (1), multiple (2), ligature (4), contextual (5) and chaining contextual (6)
 * substitutions in every format, directly or through extension lookups (7), with the lookup flags
 * that skip base glyphs, ligatures and marks by their `GDEF` class. Features can be limited to some
 * glyphs by a mask, as joining scripts' positional forms are, and applied in stages. It does not
 * apply alternate (3) or reverse chaining (8) substitutions or feature variations.
 */
internal class Gsub(
  private val data: FontBytes,
  private val offset: Int,
  /** `GDEF`'s glyph class of a glyph: 1 base, 2 ligature, 3 mark, 4 component, 0 none. */
  private val glyphClassOf: (Int) -> Int,
) {
  private val lookupList = offset + data.u16(offset + 8)

  /** [glyphs], with the lookups of [features] for [script] (an OpenType script tag) applied. */
  fun substitute(glyphs: List<Int>, script: String, features: Set<String>): List<Int> =
    substitute(glyphs, List(glyphs.size) { 0 }, script, listOf(features), emptyMap())

  /**
   * [glyphs], with the lookups of each stage's features for [script] applied, stage by stage. A
   * feature with a bit in [featureMasks] applies only to the glyphs whose entry in [masks] has that
   * bit; the others apply to every glyph. Glyphs a substitution produces keep the mask of the glyph
   * they replace.
   */
  fun substitute(
    glyphs: List<Int>,
    masks: List<Int>,
    script: String,
    stages: List<Set<String>>,
    featureMasks: Map<String, Int>,
  ): List<Int> {
    val buffer = Buffer(glyphs.toMutableList(), masks.toMutableList())
    // The language system's required feature, if any, applies once: in the stage of its tag, as
    // HarfBuzz schedules it, or the first stage when no stage names it.
    val requiredTag = data.requiredFeature(offset, script)
    val requiredStage = stages.indexOfFirst { requiredTag in it }.coerceAtLeast(0)
    for ((index, stage) in stages.withIndex()) {
      val lookupMasks = sortedMapOf<Int, Int>()
      val required = index == requiredStage
      for ((feature, lookups) in data.featureLookups(offset, script, stage, required)) {
        val mask = featureMasks[feature] ?: 0
        for (lookup in lookups) {
          // A lookup shared by several features applies wherever any of them does.
          val existing = lookupMasks[lookup]
          lookupMasks[lookup] =
            when {
              existing == null -> mask
              existing == 0 || mask == 0 -> 0
              else -> existing or mask
            }
        }
      }
      for ((lookup, mask) in lookupMasks) applyLookup(buffer, lookup, mask)
    }
    return buffer.ids
  }

  /** A run of glyphs, each with the mask of the features that apply to it. */
  private class Buffer(val ids: MutableList<Int>, val masks: MutableList<Int>) {
    val size: Int
      get() = ids.size

    operator fun get(i: Int): Int = ids[i]

    /** Replaces glyph [i], keeping its mask. */
    operator fun set(i: Int, glyph: Int) {
      ids[i] = glyph
    }

    fun mask(i: Int): Int = masks[i]

    fun removeAt(i: Int) {
      ids.removeAt(i)
      masks.removeAt(i)
    }

    /** Replaces glyph [i] with [glyphs], each with [i]'s mask. */
    fun replace(i: Int, glyphs: List<Int>) {
      val mask = masks[i]
      removeAt(i)
      ids.addAll(i, glyphs)
      masks.addAll(i, List(glyphs.size) { mask })
    }
  }

  /** Applies lookup [index] across [buffer], at the glyphs that have [mask] (all, for 0). */
  /** Each lookup this table has been asked for, parsed once: a font is shaped many times. */
  private val lookupCache = ConcurrentHashMap<Int, Lookup>()

  private fun lookup(index: Int): Lookup = lookupCache.computeIfAbsent(index) { Lookup(it) }

  private fun applyLookup(buffer: Buffer, index: Int, mask: Int) {
    val lookup = lookup(index)
    var i = 0
    while (i < buffer.size) {
      if (
        !lookup.canStartWith(buffer[i]) ||
          lookup.skips(buffer[i]) ||
          (mask != 0 && buffer.mask(i) and mask == 0)
      ) {
        i++
        continue
      }
      val next = lookup.applyAt(buffer, i)
      i = next ?: (i + 1)
    }
  }

  private inner class Lookup(index: Int) {
    private val table = lookupList + data.u16(lookupList + 2 + index * 2)
    private val flag = data.u16(table + 2)
    private val type: Int
    private val subtables: List<Int>

    init {
      val declared = data.u16(table)
      val subs = List(data.u16(table + 4)) { table + data.u16(table + 6 + it * 2) }
      if (declared == EXTENSION && subs.isNotEmpty()) {
        type = data.u16(subs[0] + 2)
        subtables = subs.map { it + data.u32(it + 4).toInt() }
      } else {
        type = declared
        subtables = subs
      }
    }

    /** Whether the lookup flag makes the lookup pass over [glyph]. */
    fun skips(glyph: Int): Boolean {
      if (flag and (IGNORE_BASE or IGNORE_LIGATURES or IGNORE_MARKS) == 0) return false
      return when (glyphClassOf(glyph)) {
        1 -> flag and IGNORE_BASE != 0
        2 -> flag and IGNORE_LIGATURES != 0
        3 -> flag and IGNORE_MARKS != 0
        else -> false
      }
    }

    /**
     * Every glyph a subtable's match can start with: the union of their first coverage tables, so a
     * glyph outside it is passed over without trying each subtable. Null when a subtable's first
     * glyph is not given by a coverage table, and any glyph may start a match.
     */
    private val starts: BitSet? by lazy {
      val set = BitSet()
      for (sub in subtables) {
        val coverage =
          when {
            type in SINGLE..LIGATURE -> sub + data.u16(sub + 2)
            (type == CONTEXT || type == CHAINING) && data.u16(sub) in 1..2 ->
              sub + data.u16(sub + 2)
            type == CONTEXT && data.u16(sub) == 3 -> sub + data.u16(sub + 6)
            type == CHAINING && data.u16(sub) == 3 -> {
              val input = sub + 4 + data.u16(sub + 2) * 2
              if (data.u16(input) == 0) return@lazy null
              sub + data.u16(input + 2)
            }
            else -> return@lazy null
          }
        data.addCoverage(coverage, set)
      }
      set
    }

    /**
     * Whether some subtable's match could start with [glyph]; never for the negative placeholder of
     * a default-ignorable character.
     */
    fun canStartWith(glyph: Int): Boolean = glyph >= 0 && (starts?.get(glyph) ?: true)

    /** The next unskipped position after [i], or -1. */
    fun next(buffer: Buffer, i: Int): Int {
      var j = i + 1
      while (j < buffer.size && skips(buffer[j])) j++
      return if (j < buffer.size) j else -1
    }

    /** The previous unskipped position before [i], or -1. */
    fun previous(buffer: Buffer, i: Int): Int {
      var j = i - 1
      while (j >= 0 && skips(buffer[j])) j--
      return j
    }

    /**
     * Applies the first subtable that matches at [i]; returns where to continue, or null when none
     * matched.
     */
    fun applyAt(buffer: Buffer, i: Int): Int? {
      for (sub in subtables) {
        val result =
          when (type) {
            SINGLE -> single(sub, buffer, i)
            MULTIPLE -> multiple(sub, buffer, i)
            LIGATURE -> ligature(sub, buffer, i)
            CONTEXT -> context(sub, buffer, i)
            CHAINING -> chaining(sub, buffer, i)
            else -> null
          }
        if (result != null) return result
      }
      return null
    }

    private fun single(sub: Int, buffer: Buffer, i: Int): Int? {
      val glyph = buffer[i]
      val c = data.coverageIndex(sub + data.u16(sub + 2), glyph) ?: return null
      buffer[i] =
        when (data.u16(sub)) {
          1 -> (glyph + data.i16(sub + 4)) and 0xFFFF
          2 -> data.u16(sub + 6 + c * 2)
          else -> return null
        }
      return i + 1
    }

    private fun multiple(sub: Int, buffer: Buffer, i: Int): Int? {
      if (data.u16(sub) != 1) return null
      val c = data.coverageIndex(sub + data.u16(sub + 2), buffer[i]) ?: return null
      val sequence = sub + data.u16(sub + 6 + c * 2)
      val replacement = List(data.u16(sequence)) { data.u16(sequence + 2 + it * 2) }
      buffer.replace(i, replacement)
      return i + replacement.size
    }

    private fun ligature(sub: Int, buffer: Buffer, i: Int): Int? {
      if (data.u16(sub) != 1) return null
      val c = data.coverageIndex(sub + data.u16(sub + 2), buffer[i]) ?: return null
      val set = sub + data.u16(sub + 6 + c * 2)
      for (l in 0 until data.u16(set)) {
        val lig = set + data.u16(set + 2 + l * 2)
        val count = data.u16(lig + 2)
        val positions = IntArray(count)
        positions[0] = i
        var ok = true
        for (k in 1 until count) {
          val p = next(buffer, positions[k - 1])
          if (p < 0 || buffer[p] != data.u16(lig + 4 + (k - 1) * 2)) {
            ok = false
            break
          }
          positions[k] = p
        }
        if (!ok) continue
        buffer[i] = data.u16(lig)
        for (k in count - 1 downTo 1) buffer.removeAt(positions[k])
        return i + 1
      }
      return null
    }

    /** Contextual substitution: an input sequence, then lookups at positions within it. */
    private fun context(sub: Int, buffer: Buffer, i: Int): Int? =
      when (data.u16(sub)) {
        1 -> {
          val c = data.coverageIndex(sub + data.u16(sub + 2), buffer[i]) ?: return null
          // A null rule set: the glyph is covered but no rule starts with it.
          val setOffset = data.u16(sub + 6 + c * 2)
          if (setOffset == 0) return null
          firstRule(sub + setOffset) { rule ->
            val inputCount = data.u16(rule)
            val input = IntArray(inputCount - 1) { data.u16(rule + 4 + it * 2) }
            val records = rule + 4 + (inputCount - 1) * 2
            tryRule(
              buffer,
              i,
              IntArray(0),
              input,
              IntArray(0),
              { _, g, v -> g == v },
              records,
              data.u16(rule + 2),
            )
          }
        }
        2 -> {
          data.coverageIndex(sub + data.u16(sub + 2), buffer[i]) ?: return null
          val classDef = sub + data.u16(sub + 4)
          val setOffset = data.u16(sub + 8 + data.glyphClass(classDef, buffer[i]) * 2)
          if (setOffset == 0) return null
          firstRule(sub + setOffset) { rule ->
            val inputCount = data.u16(rule)
            val input = IntArray(inputCount - 1) { data.u16(rule + 4 + it * 2) }
            val records = rule + 4 + (inputCount - 1) * 2
            tryRule(
              buffer,
              i,
              IntArray(0),
              input,
              IntArray(0),
              { _, g, v -> data.glyphClass(classDef, g) == v },
              records,
              data.u16(rule + 2),
            )
          }
        }
        3 -> {
          val inputCount = data.u16(sub + 2)
          val coverages = IntArray(inputCount) { sub + data.u16(sub + 6 + it * 2) }
          if (data.coverageIndex(coverages[0], buffer[i]) == null) return null
          tryRule(
            buffer,
            i,
            IntArray(0),
            coverages.copyOfRange(1, inputCount),
            IntArray(0),
            { _, g, v -> data.coverageIndex(v, g) != null },
            sub + 6 + inputCount * 2,
            data.u16(sub + 4),
          )
        }
        else -> null
      }

    /** Chaining contextual substitution: backtrack, input and lookahead sequences. */
    private fun chaining(sub: Int, buffer: Buffer, i: Int): Int? =
      when (data.u16(sub)) {
        1 -> {
          val c = data.coverageIndex(sub + data.u16(sub + 2), buffer[i]) ?: return null
          // A null rule set: the glyph is covered but no rule starts with it.
          val setOffset = data.u16(sub + 6 + c * 2)
          if (setOffset == 0) return null
          firstRule(sub + setOffset) { rule ->
            val (backtrack, input, lookahead, records, count) = chainRule(rule)
            tryRule(buffer, i, backtrack, input, lookahead, { _, g, v -> g == v }, records, count)
          }
        }
        2 -> {
          data.coverageIndex(sub + data.u16(sub + 2), buffer[i]) ?: return null
          val classDefs =
            intArrayOf(sub + data.u16(sub + 4), sub + data.u16(sub + 6), sub + data.u16(sub + 8))
          val setOffset = data.u16(sub + 12 + data.glyphClass(classDefs[1], buffer[i]) * 2)
          if (setOffset == 0) return null
          firstRule(sub + setOffset) { rule ->
            val (backtrack, input, lookahead, records, count) = chainRule(rule)
            tryRule(
              buffer,
              i,
              backtrack,
              input,
              lookahead,
              { which, g, v -> data.glyphClass(classDefs[which], g) == v },
              records,
              count,
            )
          }
        }
        3 -> {
          var p = sub + 2
          val backtrack = IntArray(data.u16(p)) { sub + data.u16(p + 2 + it * 2) }
          p += 2 + backtrack.size * 2
          val input = IntArray(data.u16(p)) { sub + data.u16(p + 2 + it * 2) }
          p += 2 + input.size * 2
          val lookahead = IntArray(data.u16(p)) { sub + data.u16(p + 2 + it * 2) }
          p += 2 + lookahead.size * 2
          if (input.isEmpty() || data.coverageIndex(input[0], buffer[i]) == null) return null
          tryRule(
            buffer,
            i,
            backtrack,
            input.copyOfRange(1, input.size),
            lookahead,
            { _, g, v -> data.coverageIndex(v, g) != null },
            p + 2,
            data.u16(p),
          )
        }
        else -> null
      }

    private fun chainRule(rule: Int): ChainRule {
      var p = rule
      val backtrack = IntArray(data.u16(p)) { data.u16(p + 2 + it * 2) }
      p += 2 + backtrack.size * 2
      val inputCount = data.u16(p)
      val input = IntArray(inputCount - 1) { data.u16(p + 2 + it * 2) }
      p += 2 + input.size * 2
      val lookahead = IntArray(data.u16(p)) { data.u16(p + 2 + it * 2) }
      p += 2 + lookahead.size * 2
      return ChainRule(backtrack, input, lookahead, p + 2, data.u16(p))
    }

    private inline fun firstRule(set: Int, rule: (Int) -> Int?): Int? {
      for (r in 0 until data.u16(set)) rule(set + data.u16(set + 2 + r * 2))?.let {
        return it
      }
      return null
    }

    /**
     * Matches a rule at [i] and, when it matches, applies its lookup records to the input sequence.
     * [matches] compares a glyph with a rule value; its first argument is 0 for backtrack, 1 for
     * input and 2 for lookahead. Returns where to continue.
     */
    private fun tryRule(
      buffer: Buffer,
      i: Int,
      backtrack: IntArray,
      input: IntArray,
      lookahead: IntArray,
      matches: (Int, Int, Int) -> Boolean,
      records: Int,
      recordCount: Int,
    ): Int? {
      val positions = IntArray(input.size + 1)
      positions[0] = i
      for (k in input.indices) {
        val p = next(buffer, positions[k])
        if (p < 0 || !matches(1, buffer[p], input[k])) return null
        positions[k + 1] = p
      }
      var p = i
      for (k in backtrack.indices) {
        p = previous(buffer, p)
        if (p < 0 || !matches(0, buffer[p], backtrack[k])) return null
      }
      p = positions.last()
      for (k in lookahead.indices) {
        p = next(buffer, p)
        if (p < 0 || !matches(2, buffer[p], lookahead[k])) return null
      }
      var end = positions.last() + 1
      for (r in 0 until recordCount) {
        val sequenceIndex = data.u16(records + r * 4)
        val lookupIndex = data.u16(records + r * 4 + 2)
        if (sequenceIndex >= positions.size) continue
        val at = positions[sequenceIndex]
        val before = buffer.size
        lookup(lookupIndex).applyAt(buffer, at)
        val change = buffer.size - before
        if (change != 0) {
          // The nested lookup changed the length: later positions move with it.
          for (k in sequenceIndex + 1 until positions.size) positions[k] += change
          end += change
        }
      }
      return maxOf(end, i + 1)
    }
  }

  private data class ChainRule(
    val backtrack: IntArray,
    val input: IntArray,
    val lookahead: IntArray,
    val records: Int,
    val count: Int,
  )

  private companion object {
    const val SINGLE = 1
    const val MULTIPLE = 2
    const val LIGATURE = 4
    const val CONTEXT = 5
    const val CHAINING = 6
    const val EXTENSION = 7
    const val IGNORE_BASE = 0x2
    const val IGNORE_LIGATURES = 0x4
    const val IGNORE_MARKS = 0x8
  }
}
