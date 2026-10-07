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
 * Evaluates a [VariableTextOutline] on the device, for a renderer other than a Remote Compose
 * player: its path verbs, its box metrics, and its coordinates at given axis values, written into a
 * caller's array with no allocation.
 *
 * The outline's model is flattened into arrays once, here. Per evaluation each tent is computed
 * from its axis' user value (its clamped ramps), then either
 * - with key outlines, the default outline plus each non-zero tent's scalar times its delta, or
 * - with forms, each region scalar as a product of tents, and each coordinate as `constant + Σ
 *   coefficient × region scalar` (or a literal when it never moves).
 *
 * The outline can come from [VariableFont.outline] or from [VariableTextOutline.decode], so a
 * renderer built on this needs no font at run time.
 *
 * This is a bridge for `flexpress-compose`, not a stable API. An instance keeps scratch arrays and
 * is not thread-safe.
 */
@InternalFlexpressApi
public class VariableTextOutlineEvaluator(
  /** The outline evaluated. */
  public val outline: VariableTextOutline
) {
  /** The animated axes' tags, in the order [evaluate] takes their values. */
  public val axes: List<String> = outline.axes

  /** One of [VERB_MOVE], [VERB_LINE], [VERB_QUAD] or [VERB_CLOSE] per path command. */
  public val verbs: ByteArray = outline.verbs.copyOf()

  /**
   * The number of coordinates [evaluate] writes: x then y per point, in command order; a move or
   * line has one point, a quad two (control, then end), a close none.
   */
  public val coordinateCount: Int

  /** The font's units per em, the outline's coordinate space. */
  public val unitsPerEm: Int = outline.unitsPerEm

  /** The font's typographic ascender, in font units. */
  public val ascender: Int = outline.ascender

  /** The font's typographic descender (negative below the baseline), in font units. */
  public val descender: Int = outline.descender

  /** The widest advance anywhere the axes move, in font units: a box this wide never reflows. */
  public val width: Float = outline.width

  /** Whether the outline is held as key outlines tweened by one axis, rather than as forms. */
  public val isKeys: Boolean = outline.body is VariableTextOutline.Keys

  /** The number of tents: per-axis factors of the region scalars. */
  public val tentCount: Int = outline.tents.size

  /** The number of variation terms over all moving coordinates (0 with key outlines). */
  public val termCount: Int

  // Tents, as ramps of their axis' user value, CSR.
  private val tentAxis = IntArray(tentCount) { outline.tents[it].axis }
  private val tentY0 = FloatArray(tentCount) { outline.tents[it].y0 }
  private val rampStart = IntArray(tentCount + 1)
  private val rampKnot: FloatArray
  private val rampWidth: FloatArray
  private val rampSlope: FloatArray

  // Keys: base and per-tent delta.
  private val base: FloatArray?
  private val deltas: Array<FloatArray>?

  // Forms: regions (tent indices, CSR), forms (terms, CSR), per-coordinate slot or literal.
  private val regionStart: IntArray
  private val regionTents: IntArray
  private val constants: FloatArray
  private val termStart: IntArray
  private val regionOf: IntArray
  private val coefficients: FloatArray
  private val slots: IntArray
  private val literals: FloatArray

  private val tentValues = FloatArray(tentCount)
  private val regionScalars: FloatArray
  private val formValues: FloatArray

  init {
    var ramps = 0
    for (t in 0 until tentCount) {
      rampStart[t] = ramps
      ramps += outline.tents[t].knots.size
    }
    rampStart[tentCount] = ramps
    rampKnot = FloatArray(ramps)
    rampWidth = FloatArray(ramps)
    rampSlope = FloatArray(ramps)
    for (t in 0 until tentCount) {
      val tent = outline.tents[t]
      tent.knots.copyInto(rampKnot, rampStart[t])
      tent.widths.copyInto(rampWidth, rampStart[t])
      tent.slopes.copyInto(rampSlope, rampStart[t])
    }
    when (val body = outline.body) {
      is VariableTextOutline.Keys -> {
        coordinateCount = body.base.size
        base = body.base
        deltas =
          Array(body.keys.size) { k ->
            FloatArray(coordinateCount) { i -> body.keys[k][i] - body.base[i] }
          }
        regionStart = IntArray(1)
        regionTents = IntArray(0)
        constants = FloatArray(0)
        termStart = IntArray(1)
        regionOf = IntArray(0)
        coefficients = FloatArray(0)
        slots = IntArray(0)
        literals = FloatArray(0)
        termCount = 0
      }
      is VariableTextOutline.Forms -> {
        coordinateCount = body.slots.size
        base = null
        deltas = null
        regionStart = IntArray(body.regions.size + 1)
        var n = 0
        body.regions.forEachIndexed { r, tents ->
          regionStart[r] = n
          n += tents.size
        }
        regionStart[body.regions.size] = n
        regionTents = IntArray(n)
        body.regions.forEachIndexed { r, tents -> tents.copyInto(regionTents, regionStart[r]) }
        constants = body.constants
        termStart = body.termStart
        regionOf = body.regionOf
        coefficients = body.coefficients
        slots = body.slots
        literals = body.literals
        termCount = body.regionOf.size
      }
    }
    regionScalars = FloatArray(regionStart.size - 1)
    formValues = FloatArray(constants.size)
  }

  /**
   * Evaluates every coordinate at the user-space axis [values] (one per [axes], in that order;
   * clamped to each axis' range by the ramps) into [out], which must hold at least
   * [coordinateCount] values. Allocates nothing.
   */
  public fun evaluate(values: FloatArray, out: FloatArray) {
    require(values.size >= axes.size) { "need ${axes.size} axis values" }
    require(out.size >= coordinateCount) { "need room for $coordinateCount coordinates" }
    for (t in 0 until tentCount) {
      val v = values[tentAxis[t]]
      var s = tentY0[t]
      for (j in rampStart[t] until rampStart[t + 1]) {
        s += rampSlope[j] * (v - rampKnot[j]).coerceIn(0f, rampWidth[j])
      }
      tentValues[t] = s
    }
    val base = base
    val deltas = deltas
    if (base != null && deltas != null) {
      base.copyInto(out, 0, 0, coordinateCount)
      for (t in deltas.indices) {
        val s = tentValues[t]
        if (s == 0f) continue
        val delta = deltas[t]
        for (i in 0 until coordinateCount) out[i] += s * delta[i]
      }
      return
    }
    for (r in regionScalars.indices) {
      var p = 1f
      for (j in regionStart[r] until regionStart[r + 1]) p *= tentValues[regionTents[j]]
      regionScalars[r] = p
    }
    for (f in formValues.indices) {
      var v = constants[f]
      for (j in termStart[f] until termStart[f + 1]) v +=
        coefficients[j] * regionScalars[regionOf[j]]
      formValues[f] = v
    }
    for (i in 0 until coordinateCount) {
      val slot = slots[i]
      out[i] = if (slot < 0) literals[i] else formValues[slot]
    }
  }

  public companion object {
    /** Starts a contour at one point. */
    public const val VERB_MOVE: Byte = MOVE.toByte()

    /** A line to one point. */
    public const val VERB_LINE: Byte = LINE.toByte()

    /** A quadratic curve through a control point to an end point. */
    public const val VERB_QUAD: Byte = QUAD.toByte()

    /** Closes the contour. */
    public const val VERB_CLOSE: Byte = CLOSE.toByte()

    /**
     * As [VariableFont.outline] with no simplification, but with key outlines allowed or not, so
     * both forms can be checked against each other.
     */
    public fun exactOutline(
      font: VariableFont,
      text: String,
      axes: List<String>,
      location: Map<String, Float> = emptyMap(),
      kerningLocation: Map<String, Float> = location,
      allowKeys: Boolean = true,
    ): VariableTextOutline =
      font.outline(text, axes, location, kerningLocation, null, DEFAULT_TOLERANCE_PIXELS, allowKeys)

    /**
     * [text] in [font] instanced at one user-space [location], straight from the font's tables: the
     * coordinates in the same order [evaluate] writes them, for checking an outline against.
     */
    public fun instanceCoordinates(
      font: VariableFont,
      text: String,
      location: Map<String, Float>,
      kerningLocation: Map<String, Float> = location,
    ): FloatArray {
      val coordinates = ArrayList<Float>()
      font
        .layout(text, location, kerningLocation)
        .emit(
          object : PathSink<Float> {
            override fun moveTo(x: Float, y: Float) {
              coordinates += x
              coordinates += y
            }

            override fun lineTo(x: Float, y: Float) {
              coordinates += x
              coordinates += y
            }

            override fun quadTo(x1: Float, y1: Float, x2: Float, y2: Float) {
              coordinates += x1
              coordinates += y1
              coordinates += x2
              coordinates += y2
            }

            override fun close() {}
          }
        )
      return coordinates.toFloatArray()
    }

    /**
     * The x of each glyph's origin in [text] (one per code point) at user-space [location], in font
     * units, with pair kerning at [kerningLocation]: where a renderer of the outline places them.
     */
    public fun glyphOrigins(
      font: VariableFont,
      text: String,
      location: Map<String, Float>,
      kerningLocation: Map<String, Float> = location,
    ): FloatArray {
      val coords = font.normalize(location)
      val glyphs = font.glyphIds(text)
      val origins = FloatArray(glyphs.size)
      var x = 0f
      glyphs.forEachIndexed { i, glyph ->
        if (i > 0) x += font.kerning(glyphs[i - 1], glyph, kerningLocation)
        origins[i] = x
        x += font.outline(glyph, coords).advance
      }
      return origins
    }
  }
}
