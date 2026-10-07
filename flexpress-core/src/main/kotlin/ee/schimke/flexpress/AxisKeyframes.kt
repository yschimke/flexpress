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

/** A line of text laid out at one location in the design space, in font units (y up). */
@InternalFlexpressApi class TextOutline(val contours: List<Contour>, val advance: Float)

/**
 * Lays [text] out on one line at [location] using nominal glyph advances.
 *
 * The glyphs are [shaped][VariableFont.shape]: bidirectional runs in visual order, with the font's
 * ligatures and contextual alternates. They are placed by advance, with `GPOS` pair kerning at
 * [kerningLocation] when one is given, and marks attached by the font's anchors at
 * [kerningLocation], or at [location] without one. The point is that the result is structurally
 * identical at every location — the same glyphs, contours and points in the same order — so two
 * layouts can be tweened point by point.
 */
@InternalFlexpressApi
fun VariableFont.layout(
  text: String,
  location: Map<String, Float>,
  kerningLocation: Map<String, Float>? = null,
): TextOutline {
  val coords = normalize(location)
  val glyphs = shapePositioned(text, kerningLocation ?: location)
  val outlines = glyphs.map { outline(it.id, coords) }
  val placement =
    placeGlyphs(glyphs, kerningLocation, 0f, { outlines[it].advance }, { it }, Float::plus)
  val contours = mutableListOf<Contour>()
  glyphs.forEachIndexed { i, g ->
    outlines[i].contours.mapTo(contours) { it.transformed(1f, 0f, 0f, 1f, placement.x[i], g.dy) }
  }
  return TextOutline(contours, placement.advance)
}

/**
 * Lays [text] out like [layout], but for the whole design space at once: every coordinate and the
 * advance are [LinearForm]s, so the line can be re-evaluated at any location without the font.
 *
 * With [kerningLocation], each pair of glyphs is also kerned by the font's `GPOS` pair kerning at
 * that location, a constant; without it the layout is nominal advances only, like [layout]. Marks
 * are attached by their anchors at [kerningLocation], or at the default location without one.
 */
@InternalFlexpressApi
fun VariableFont.variedLayout(
  text: String,
  kerningLocation: Map<String, Float>? = null,
): VariedOutline {
  val glyphs = shapePositioned(text, kerningLocation ?: emptyMap())
  val outlines = glyphs.map { variedOutline(it.id) }
  val placement =
    placeGlyphs(
      glyphs,
      kerningLocation,
      LinearForm.ZERO,
      { outlines[it].advance },
      { LinearForm.of(it) },
      LinearForm::plus,
    )
  val contours = mutableListOf<VariedContour>()
  glyphs.forEachIndexed { i, g ->
    outlines[i].contours.mapTo(contours) {
      it.transformed(1f, 0f, 0f, 1f, placement.x[i], LinearForm.of(g.dy))
    }
  }
  return VariedOutline(contours, placement.advance)
}

/** Each glyph's origin x, and the line's advance, from [placeGlyphs]. */
internal class Placement<T>(val x: List<T>, val advance: T)

/**
 * Places [glyphs] on a line: the pen moves by each unattached glyph's [advance] and the pair
 * kerning at [kerningLocation] (between unattached glyphs, taken in logical order in right-to-left
 * runs), and each attached mark goes to its base's origin plus its offset. [T] is the number type,
 * a `Float` or a [LinearForm], built with [constant] and [plus].
 */
internal fun <T : Any> VariableFont.placeGlyphs(
  glyphs: List<ShapedGlyph>,
  kerningLocation: Map<String, Float>?,
  zero: T,
  advance: (Int) -> T,
  constant: (Float) -> T,
  plus: (T, T) -> T,
): Placement<T> {
  val x = arrayOfNulls<Any>(glyphs.size)
  var pen = zero
  var previous = -1
  for (i in glyphs.indices) {
    if (glyphs[i].base >= 0) continue
    if (previous >= 0 && kerningLocation != null) {
      val rtl = glyphs[i].rtl && glyphs[previous].rtl
      val first = glyphs[if (rtl) i else previous].id
      val second = glyphs[if (rtl) previous else i].id
      pen = plus(pen, constant(kerning(first, second, kerningLocation)))
    }
    x[i] = pen
    pen = plus(pen, advance(i))
    previous = i
  }
  fun resolve(i: Int, depth: Int): T {
    @Suppress("UNCHECKED_CAST")
    (x[i] as T?)?.let {
      return it
    }
    val g = glyphs[i]
    // A chain of attachments ends at an unattached glyph; a cycle would be a font error.
    val base =
      if (g.base in glyphs.indices && depth < glyphs.size) resolve(g.base, depth + 1) else zero
    val value = plus(base, constant(g.dx))
    x[i] = value
    return value
  }
  return Placement(glyphs.indices.map { resolve(it, 0) }, pen)
}

/** [text]'s glyphs, shaped and in visual order: see [VariableFont.shape]. */
@InternalFlexpressApi fun VariableFont.glyphIds(text: String): List<Int> = shape(text)

/**
 * The [axis] values at which [text] must be sampled so that linear interpolation between
 * consecutive samples reproduces the font exactly, with every other axis held at [location].
 *
 * Variable-font deltas are piecewise linear in normalized coordinates, with corners only where a
 * variation region starts, peaks or ends (and where `avar` bends the mapping). Sampling at those
 * corners — only the ones that affect the glyphs in [text], and every corner of the mark anchors'
 * variations when [text] has marks — gives an exact keyframe set, usually far smaller than sampling
 * at a fixed step: two for Google Sans Flex `ROND`.
 *
 * @param range restricts the keyframes to the part of the axis that will actually be animated.
 */
public fun VariableFont.axisKeyframes(
  text: String,
  axis: String,
  range: ClosedFloatingPointRange<Float>? = null,
): List<Float> {
  val index = axes.indexOfFirst { it.tag == axis }
  require(index >= 0) { "font has no '$axis' axis; it has ${axes.map { it.tag }}" }
  val fontAxis = axes[index]
  val lo = (range?.start ?: fontAxis.minValue).coerceIn(fontAxis.minValue, fontAxis.maxValue)
  val hi = (range?.endInclusive ?: fontAxis.maxValue).coerceIn(fontAxis.minValue, fontAxis.maxValue)
  val glyphs = shapePositioned(text, emptyMap())
  // Marks follow their anchors, whose variations have regions of their own.
  val anchors = if (glyphs.any { it.base >= 0 }) anchorBreakpoints(index) else emptyList()
  val values =
    (normalizedBreakpoints(index, glyphs.map { it.id }.toSet()).map { denormalize(index, it) } +
        avarBreakpoints(index) +
        anchors)
      .filter { it > lo && it < hi }
  return (listOf(lo) + values + listOf(hi)).distinct().sorted()
}

/** Receives a glyph outline as path commands, with coordinates of type [T]. */
@InternalFlexpressApi
interface PathSink<T> {
  fun moveTo(x: T, y: T)

  fun lineTo(x: T, y: T)

  fun quadTo(x1: T, y1: T, x2: T, y2: T)

  fun close()
}

/**
 * Emits [this] outline as TrueType quadratic path commands.
 *
 * The command sequence depends only on which points are on or off the curve, never on where they
 * are, so outlines of the same text at different locations always produce the same commands —
 * including degenerate segments, which a platform path would be free to drop.
 */
@InternalFlexpressApi
fun TextOutline.emit(sink: PathSink<Float>) {
  for (c in contours) {
    emitContour(c.size, c.onCurve, { c.xs[it] }, { c.ys[it] }, { a, b -> (a + b) / 2 }, sink)
  }
}

/** As [TextOutline.emit], with every coordinate a [LinearForm]. */
@InternalFlexpressApi
fun VariedOutline.emit(sink: PathSink<LinearForm>) {
  for (c in contours) {
    emitContour(c.size, c.onCurve, { c.xs[it] }, { c.ys[it] }, LinearForm::mid, sink)
  }
}

private fun <T> emitContour(
  n: Int,
  onCurve: BooleanArray,
  x: (Int) -> T,
  y: (Int) -> T,
  mid: (T, T) -> T,
  sink: PathSink<T>,
) {
  if (n == 0) return
  val first = (0 until n).firstOrNull { onCurve[it] }
  if (first == null) {
    // All off-curve: every on-curve point is implied, starting between the last and first.
    sink.moveTo(mid(x(n - 1), x(0)), mid(y(n - 1), y(0)))
    for (i in 0 until n) {
      val j = (i + 1) % n
      sink.quadTo(x(i), y(i), mid(x(i), x(j)), mid(y(i), y(j)))
    }
  } else {
    sink.moveTo(x(first), y(first))
    var pending = -1
    for (k in 1..n) {
      val i = (first + k) % n
      if (onCurve[i]) {
        if (pending >= 0) sink.quadTo(x(pending), y(pending), x(i), y(i))
        else sink.lineTo(x(i), y(i))
        pending = -1
      } else {
        if (pending >= 0) {
          sink.quadTo(x(pending), y(pending), mid(x(pending), x(i)), mid(y(pending), y(i)))
        }
        pending = i
      }
    }
  }
  sink.close()
}
