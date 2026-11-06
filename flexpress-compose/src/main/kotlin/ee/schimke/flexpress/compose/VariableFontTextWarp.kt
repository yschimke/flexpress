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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.isSpecified
import ee.schimke.flexpress.VariableFont
import ee.schimke.flexpress.VariableTextOutlineEvaluator
import ee.schimke.flexpress.cachedGlyphOutlines
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Which way text runs around a [VariableFontCurvedText] circle, as Wear Compose's
 * `CurvedDirection.Angular`.
 */
public enum class CurvedTextDirection {
  /** Reads clockwise with the glyphs standing outside the circle: upright at the top. */
  Clockwise,
  /** Reads counter-clockwise with the glyphs hanging inside the circle: upright at the bottom. */
  CounterClockwise,
}

/**
 * **Curved text** — a line of variable-font text bent around a circle, like Wear Compose's
 * `curvedText`.
 *
 * Wear Compose bends text by drawing it flat and warping the pixels in a shader. This warps the
 * outline instead: every outline point at distance `u` along the line and height `v` above the
 * baseline goes to angle `anchor + u / radius` at distance `radius + v` from the center, so each
 * glyph bends with the circle (the tops of letters on the outside of the curve spread, the bottoms
 * pinch) and the result is still a vector path, sharp at any scale and fillable with any [Brush].
 * Lines and curves are split finely enough to follow the arc and each piece is re-fitted as a
 * quadratic through its warped midpoint.
 *
 * The box is a square around the whole circle and its glyphs; the circle's center is the center of
 * the box.
 *
 * @param radius The radius of the baseline.
 * @param anchorAngleDegrees Where the anchor point of the text sits on the circle, in degrees
 *   clockwise from 3 o'clock (`-90f` is 12 o'clock, `90f` is 6 o'clock). Read while drawing, so it
 *   can spin the text.
 * @param textAnchor Which point of the line is placed at [anchorAngleDegrees], as a fraction of its
 *   live width: `0f` the start, `0.5f` the middle, `1f` the end.
 * @param direction Which way the text runs; see [CurvedTextDirection].
 */
@Composable
public fun VariableFontCurvedText(
  text: String,
  font: VariableFont,
  axes: Map<String, () -> Float>,
  fontSize: TextUnit,
  radius: Dp,
  modifier: Modifier = Modifier,
  anchorAngleDegrees: () -> Float = { -90f },
  textAnchor: Float = 0.5f,
  direction: CurvedTextDirection = CurvedTextDirection.Clockwise,
  color: Color = Color.Unspecified,
  brush: Brush? = null,
  location: Map<String, Float> = emptyMap(),
  kerningLocation: Map<String, Float> = location,
) {
  requireWarpSp(fontSize)
  val density = LocalDensity.current
  val radiusPx = with(density) { radius.toPx() }.coerceAtLeast(1f)
  val warp = remember { CircleWarp() }
  WarpedText(
    text = text,
    font = font,
    axes = axes,
    fontSize = fontSize,
    modifier = modifier,
    textAnchor = textAnchor,
    color = color,
    brush = brush,
    location = location,
    kerningLocation = kerningLocation,
    measure = { extent ->
      val side = ceil(2f * (radiusPx + extent)).toInt()
      WarpBox(side, side)
    },
    prepare = { scope, pixelSize ->
      warp.centerX = scope.size.width * 0.5f
      warp.centerY = scope.size.height * 0.5f
      warp.radius = radiusPx
      warp.anchorRad = anchorAngleDegrees() * (PI.toFloat() / 180f)
      warp.clockwise = direction == CurvedTextDirection.Clockwise
      warp.maxSegmentPx = max(1.5f, min(pixelSize * 0.2f, radiusPx * 0.1f))
      warp
    },
  )
}

/**
 * **Text warped along a path** — a line of variable-font text bent along the first contour of
 * [path].
 *
 * Like [VariableFontCurvedText] the outline itself is warped, without a shader: a point at distance
 * `u` along the line and height `v` above the baseline goes to the point `u` along [path], moved
 * `v` along the path's normal (to the left of its direction of travel). Glyphs bend and stretch
 * with the path rather than being placed rigidly on it, as [VariableFontPathText] does.
 *
 * [path] is in pixels. The box is the path's bounds grown by the text's ascender or descender
 * (whichever is larger) on every side, and the path is drawn where its bounds fall in that box, so
 * its own origin does not matter. The path is sampled once per instance and size into a lookup
 * table; text past either end of an open path continues along the end tangent, and a closed path
 * (one that ends where it starts) wraps around.
 *
 * @param path The baseline, in pixels. Treat it as immutable: it is sampled when it changes
 *   identity, not when its contents do.
 * @param offset Where the anchor point of the text sits, as a fraction of the path's length. Read
 *   while drawing, so it can run the text along the path.
 * @param textAnchor Which point of the line is placed at [offset], as a fraction of its live width.
 */
@Composable
public fun VariableFontPathWarpText(
  text: String,
  font: VariableFont,
  axes: Map<String, () -> Float>,
  fontSize: TextUnit,
  path: Path,
  modifier: Modifier = Modifier,
  offset: () -> Float = { 0f },
  textAnchor: Float = 0f,
  color: Color = Color.Unspecified,
  brush: Brush? = null,
  location: Map<String, Float> = emptyMap(),
  kerningLocation: Map<String, Float> = location,
) {
  requireWarpSp(fontSize)
  val warp = remember(path) { PathWarp(path) }
  WarpedText(
    text = text,
    font = font,
    axes = axes,
    fontSize = fontSize,
    modifier = modifier,
    textAnchor = textAnchor,
    color = color,
    brush = brush,
    location = location,
    kerningLocation = kerningLocation,
    measure = { extent ->
      val b = warp.bounds
      warp.originX = extent - b.left
      warp.originY = extent - b.top
      WarpBox(ceil(b.width + 2f * extent).toInt(), ceil(b.height + 2f * extent).toInt())
    },
    prepare = { _, pixelSize ->
      warp.offsetPx = offset() * warp.length
      warp.maxSegmentPx = max(1.5f, pixelSize * 0.15f)
      warp
    },
  )
}

private fun requireWarpSp(fontSize: TextUnit) {
  require(fontSize.isSpecified && fontSize.isSp) { "fontSize must be in sp, was $fontSize" }
}

internal class WarpBox(val width: Int, val height: Int)

/**
 * Lays out and draws [text] through a [TextWarp]: shapes it into per-glyph outlines once, and per
 * frame evaluates each glyph and its live advance at the current axes and writes the warped
 * outlines into one reused path.
 */
@Composable
private fun WarpedText(
  text: String,
  font: VariableFont,
  axes: Map<String, () -> Float>,
  fontSize: TextUnit,
  modifier: Modifier,
  textAnchor: Float,
  color: Color,
  brush: Brush?,
  location: Map<String, Float>,
  kerningLocation: Map<String, Float>,
  measure: (extentPx: Float) -> WarpBox,
  prepare: (scope: DrawScope, pixelSize: Float) -> TextWarp,
) {
  val tags = axes.keys.toList()
  val sources = remember(tags, axes) { tags.map { axes.getValue(it) } }
  val pixelSize = with(LocalDensity.current) { fontSize.toPx() }
  val glyphs =
    remember(text, font, tags, location, kerningLocation, pixelSize) {
      font.cachedGlyphOutlines(text, tags, location, kerningLocation, pixelSize = pixelSize)
    }
  val runRenderer =
    remember(glyphs) { GlyphRunRenderer(glyphs, font.unitsPerEm, font.ascender, font.descender) }
  val advances = remember(runRenderer) { FloatArray(runRenderer.size) }
  val builder = remember { WarpedPathBuilder() }
  val fill = if (color.isSpecified) color else Color.White
  val measurePolicy =
    remember(runRenderer, pixelSize, measure) {
      MeasurePolicy { _, constraints ->
        val scale = pixelSize / runRenderer.unitsPerEm
        val extent = max(runRenderer.ascender, -runRenderer.descender) * scale + 2f
        val box = measure(extent)
        layout(constraints.constrainWidth(box.width), constraints.constrainHeight(box.height)) {}
      }
    }
  Layout(
    modifier =
      modifier
        .semantics { this.text = AnnotatedString(text) }
        .drawBehind {
          val scale = pixelSize / runRenderer.unitsPerEm
          val warp = prepare(this, pixelSize)
          for (a in sources.indices) runRenderer.axisScratch[a] = sources[a]()
          val count = runRenderer.size
          var width = 0f
          for (i in 0 until count) {
            advances[i] = runRenderer.evaluateGlyphAdvance(i, runRenderer.axisScratch) * scale
            width += runRenderer.kerningBefore(i) * scale + advances[i]
          }
          builder.begin(warp)
          var penX = -width * textAnchor
          for (i in 0 until count) {
            penX += runRenderer.kerningBefore(i) * scale
            val c = runRenderer.evaluateGlyph(i, runRenderer.axisScratch)
            builder.appendOutline(runRenderer.glyphVerbs(i), c, scale, penX)
            penX += advances[i]
          }
          if (brush != null) drawPath(builder.path, brush) else drawPath(builder.path, fill)
        },
    measurePolicy = measurePolicy,
  )
}

/**
 * Maps a point of the flat line — `u` pixels along the baseline from the text's anchor, `v` pixels
 * above it — to the canvas, into [x] and [y]. Implementations hold their per-frame parameters in
 * fields, so a frame allocates nothing.
 */
internal abstract class TextWarp {
  @JvmField var x: Float = 0f
  @JvmField var y: Float = 0f

  /** The longest piece of a flat segment, in pixels, mapped as one warped quadratic. */
  @JvmField var maxSegmentPx: Float = 4f

  abstract fun map(u: Float, v: Float)
}

internal class CircleWarp : TextWarp() {
  @JvmField var centerX = 0f
  @JvmField var centerY = 0f
  @JvmField var radius = 1f
  @JvmField var anchorRad = 0f
  @JvmField var clockwise = true

  override fun map(u: Float, v: Float) {
    val angle: Float
    val r: Float
    if (clockwise) {
      angle = anchorRad + u / radius
      r = radius + v
    } else {
      angle = anchorRad - u / radius
      r = radius - v
    }
    x = centerX + r * cos(angle)
    y = centerY + r * sin(angle)
  }
}

/**
 * Warps along the first contour of a path, through positions and unit tangents sampled every
 * [SAMPLE_STEP_PX] when constructed.
 */
internal class PathWarp(path: Path) : TextWarp() {
  val bounds: Rect = path.getBounds()
  val length: Float
  private val step: Float
  private val samples: FloatArray // x, y, tx, ty per sample
  private val sampleCount: Int
  private val closed: Boolean
  @JvmField var originX = 0f
  @JvmField var originY = 0f
  @JvmField var offsetPx = 0f

  init {
    val measure = PathMeasure()
    measure.setPath(path, false)
    length = measure.length
    sampleCount = max(2, ceil(length / SAMPLE_STEP_PX).toInt() + 1)
    step = if (length > 0f) length / (sampleCount - 1) else 0f
    samples = FloatArray(sampleCount * 4)
    for (i in 0 until sampleCount) {
      val d = min(length, i * step)
      val pos = measure.getPosition(d)
      val tan = measure.getTangent(d)
      val tl = hypot(tan.x, tan.y)
      samples[i * 4] = pos.x
      samples[i * 4 + 1] = pos.y
      samples[i * 4 + 2] = if (tl > 0f) tan.x / tl else 1f
      samples[i * 4 + 3] = if (tl > 0f) tan.y / tl else 0f
    }
    val last = (sampleCount - 1) * 4
    closed = length > 0f && hypot(samples[0] - samples[last], samples[1] - samples[last + 1]) < 0.5f
  }

  override fun map(u: Float, v: Float) {
    var d = u + offsetPx
    var px: Float
    var py: Float
    var tx: Float
    var ty: Float
    if (step <= 0f) {
      px = samples[0] + d
      py = samples[1]
      tx = 1f
      ty = 0f
    } else {
      if (closed) d = d.mod(length)
      if (d <= 0f || d >= length) {
        // Past an open end: continue straight along the end tangent.
        val base = if (d <= 0f) 0 else (sampleCount - 1) * 4
        val over = if (d <= 0f) d else d - length
        tx = samples[base + 2]
        ty = samples[base + 3]
        px = samples[base] + tx * over
        py = samples[base + 1] + ty * over
      } else {
        val f = d / step
        val i = min(floor(f).toInt(), sampleCount - 2)
        val t = f - i
        val a = i * 4
        val b = a + 4
        px = samples[a] + (samples[b] - samples[a]) * t
        py = samples[a + 1] + (samples[b + 1] - samples[a + 1]) * t
        tx = samples[a + 2] + (samples[b + 2] - samples[a + 2]) * t
        ty = samples[a + 3] + (samples[b + 3] - samples[a + 3]) * t
        val tl = hypot(tx, ty)
        if (tl > 1e-6f) {
          tx /= tl
          ty /= tl
        }
      }
    }
    // Up from the baseline is the left-hand normal of the direction of travel (y grows down).
    x = originX + px + ty * v
    y = originY + py - tx * v
  }

  private companion object {
    const val SAMPLE_STEP_PX = 1.5f
  }
}

/**
 * Writes glyph outlines through a [TextWarp] into [path]. Each line and quadratic of the flat
 * outline is split into pieces no longer than [TextWarp.maxSegmentPx], and each piece is emitted as
 * the quadratic through its warped start, midpoint and end (the control point `2·mid − (start +
 * end) / 2`), so straight stems become smooth curves.
 */
internal class WarpedPathBuilder {
  val path: Path = Path()
  private lateinit var warp: TextWarp
  private var lastX = 0f
  private var lastY = 0f

  fun begin(warp: TextWarp) {
    this.warp = warp
    path.rewind()
  }

  /** Appends one glyph's outline [c] (font units, y up) with its origin at [originX] pixels. */
  fun appendOutline(verbs: ByteArray, c: FloatArray, scale: Float, originX: Float) {
    var p = 0
    var curU = 0f
    var curV = 0f
    var startU = 0f
    var startV = 0f
    for (verb in verbs) {
      when (verb) {
        VariableTextOutlineEvaluator.VERB_MOVE -> {
          curU = originX + c[p] * scale
          curV = c[p + 1] * scale
          startU = curU
          startV = curV
          warp.map(curU, curV)
          lastX = warp.x
          lastY = warp.y
          path.moveTo(lastX, lastY)
          p += 2
        }
        VariableTextOutlineEvaluator.VERB_LINE -> {
          val nu = originX + c[p] * scale
          val nv = c[p + 1] * scale
          line(curU, curV, nu, nv)
          curU = nu
          curV = nv
          p += 2
        }
        VariableTextOutlineEvaluator.VERB_QUAD -> {
          val cu = originX + c[p] * scale
          val cv = c[p + 1] * scale
          val nu = originX + c[p + 2] * scale
          val nv = c[p + 3] * scale
          quad(curU, curV, cu, cv, nu, nv)
          curU = nu
          curV = nv
          p += 4
        }
        else -> {
          // Close back to the contour's start along the warp, not with a straight chord.
          if (curU != startU || curV != startV) line(curU, curV, startU, startV)
          path.close()
          curU = startU
          curV = startV
        }
      }
    }
  }

  private fun pieces(length: Float): Int =
    ceil(length / warp.maxSegmentPx).toInt().coerceIn(1, MAX_PIECES)

  private fun line(u0: Float, v0: Float, u1: Float, v1: Float) {
    val n = pieces(hypot(u1 - u0, v1 - v0))
    if (n == 1 && abs(v1 - v0) == 0f && abs(u1 - u0) == 0f) return
    for (k in 1..n) {
      val tm = (k - 0.5f) / n
      val te = k.toFloat() / n
      warp.map(u0 + (u1 - u0) * tm, v0 + (v1 - v0) * tm)
      val mx = warp.x
      val my = warp.y
      warp.map(u0 + (u1 - u0) * te, v0 + (v1 - v0) * te)
      emit(mx, my, warp.x, warp.y)
    }
  }

  private fun quad(u0: Float, v0: Float, cu: Float, cv: Float, u1: Float, v1: Float) {
    val polygon = hypot(cu - u0, cv - v0) + hypot(u1 - cu, v1 - cv)
    val n = pieces(polygon)
    for (k in 1..n) {
      val tm = (k - 0.5f) / n
      val te = k.toFloat() / n
      mapQuad(u0, v0, cu, cv, u1, v1, tm)
      val mx = warp.x
      val my = warp.y
      mapQuad(u0, v0, cu, cv, u1, v1, te)
      emit(mx, my, warp.x, warp.y)
    }
  }

  private fun mapQuad(u0: Float, v0: Float, cu: Float, cv: Float, u1: Float, v1: Float, t: Float) {
    val mt = 1f - t
    val a = mt * mt
    val b = 2f * mt * t
    val d = t * t
    warp.map(a * u0 + b * cu + d * u1, a * v0 + b * cv + d * v1)
  }

  /** Emits the quadratic from the last point through ([mx], [my]) to ([ex], [ey]). */
  private fun emit(mx: Float, my: Float, ex: Float, ey: Float) {
    val cx = 2f * mx - 0.5f * (lastX + ex)
    val cy = 2f * my - 0.5f * (lastY + ey)
    path.quadraticTo(cx, cy, ex, ey)
    lastX = ex
    lastY = ey
  }

  private companion object {
    const val MAX_PIECES = 48
  }
}
