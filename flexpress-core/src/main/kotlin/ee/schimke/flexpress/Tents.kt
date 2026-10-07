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
 * One axis' factor of a region scalar: rises from 0 at [start] to 1 at [peak] and falls back to 0
 * at [end], in normalized coordinates.
 */
@InternalFlexpressApi
data class Tent(val axis: Int, val start: Float, val peak: Float, val end: Float) {
  fun evaluate(n: Float): Float =
    when {
      n == peak -> 1f
      n <= start || end <= n -> 0f
      n < peak -> (n - start) / (peak - start)
      else -> (end - n) / (end - peak)
    }
}

/**
 * The factors of a region scalar that can vary. Axes whose peak is 0, and malformed ones, are
 * always 1 and are left out, exactly as [TupleRegion.scalar] skips them.
 */
@InternalFlexpressApi
fun TupleRegion.tents(): List<Tent> =
  peak.indices.mapNotNull { i ->
    val s = start[i]
    val p = peak[i]
    val e = end[i]
    if (p == 0f || s > p || p > e || (s < 0f && e > 0f)) null else Tent(i, s, p, e)
  }

/**
 * A [LinearForm] with the axes that are not animated folded in at their fixed values: what is left
 * is a constant plus, per distinct product of animated [Tent]s, a coefficient.
 */
@InternalFlexpressApi
data class AnimatedForm(val constant: Float, val terms: Map<List<Tent>, Float>) {
  val isConstant: Boolean
    get() = terms.isEmpty()

  fun evaluate(coords: FloatArray): Float =
    terms.entries.fold(constant) { sum, (tents, k) ->
      sum + k * tents.fold(1f) { p, t -> p * t.evaluate(coords[t.axis]) }
    }
}

/**
 * Specializes the forms of a font to a set of [animated] axes, holding every other axis at its
 * normalized value in [fixed].
 */
@InternalFlexpressApi
class AxisSpecialization(private val animated: Set<Int>, private val fixed: FloatArray) {
  private val regions = HashMap<TupleRegion, Pair<Float, List<Tent>>>()

  fun specialize(form: LinearForm): AnimatedForm {
    var constant = form.constant
    val terms = LinkedHashMap<List<Tent>, Float>()
    for ((region, k) in form.terms) {
      val (factor, tents) =
        regions.getOrPut(region) {
          val (moving, still) = region.tents().partition { it.axis in animated }
          still.fold(1f) { p, t -> p * t.evaluate(fixed[t.axis]) } to moving
        }
      if (factor == 0f) continue
      if (tents.isEmpty()) {
        constant += k * factor
      } else {
        val v = (terms[tents] ?: 0f) + k * factor
        if (v == 0f) terms.remove(tents) else terms[tents] = v
      }
    }
    return AnimatedForm(constant, terms)
  }
}

/**
 * A tent as a function of its axis' user value: piecewise linear, with corners where the font's
 * user-to-normalized mapping (`avar` included) has them and where the tent does. Returns its value
 * at the axis' minimum and the clamped ramps it adds, as (knot, width, slope).
 */
@InternalFlexpressApi
fun VariableFont.tentRamps(tent: Tent): Pair<Float, List<Triple<Float, Float, Float>>> {
  val info = axes[tent.axis]
  val f = { v: Float -> tent.evaluate(normalize(mapOf(info.tag to v))[tent.axis]) }
  val knots =
    (listOf(info.minValue, info.maxValue) +
        avarBreakpoints(tent.axis) +
        listOf(tent.start, tent.peak, tent.end).map { denormalize(tent.axis, it) })
      .filter { it in info.minValue..info.maxValue }
      .distinct()
      .sorted()
  val ys = knots.map(f)
  val ramps =
    (0 until knots.size - 1).mapNotNull { k ->
      val width = knots[k + 1] - knots[k]
      val rise = ys[k + 1] - ys[k]
      if (rise == 0f) null else Triple(knots[k], width, rise / width)
    }
  return ys.first() to ramps
}
