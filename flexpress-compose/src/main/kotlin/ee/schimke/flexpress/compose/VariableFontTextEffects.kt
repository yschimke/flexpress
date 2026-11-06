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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import ee.schimke.flexpress.VariableFont
import ee.schimke.flexpress.VariableGlyphOutline
import ee.schimke.flexpress.VariableTextOutlineEvaluator
import ee.schimke.flexpress.cachedGlyphOutlines
import ee.schimke.flexpress.cachedOutline
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Falloff curve for a [RangeSelector] window across glyph indices. */
public enum class RangeShape {
  /** Raised-cosine bell curve (`0.5 * (1 + cos(π * d))`). */
  Smooth,
  /** Gaussian bell curve (`exp(-4.5 * d²)`). */
  Gaussian,
  /** Linear tent (`1 - |d|`). */
  Triangle,
  /** Linear ramp from 0 at the left edge to 1 at the right edge of the window. */
  RampUp,
  /** Linear ramp from 1 at the left edge to 0 at the right edge of the window. */
  RampDown,
}

/**
 * Selects a contiguous or wrapping window of characters in `[0, 1]` normalized index space, modeled
 * on Adobe After Effects' *Range Selector* and Cavalry's *Sub-Mesh Behaviour*.
 *
 * @param center Normalized center position of the selector (`0f` = first glyph, `1f` = last glyph).
 * @param width Half-width (radius) of the active falloff window in normalized index units.
 * @param shape Falloff shape across `[-width, +width]`.
 * @param wrap Whether the selector wraps around `[0f, 1f]` for seamless looping waves.
 */
public class RangeSelector(
  public val center: Float,
  public val width: Float = 0.45f,
  public val shape: RangeShape = RangeShape.Smooth,
  public val wrap: Boolean = true,
) {
  /** Returns the selector weight in `[0f, 1f]` for [glyphIndex] out of [glyphCount] glyphs. */
  public fun weight(glyphIndex: Int, glyphCount: Int): Float {
    if (width <= 0f) return 0f
    val norm = if (glyphCount <= 1) 0.5f else glyphIndex.toFloat() / (glyphCount - 1).toFloat()
    var delta = norm - center
    if (wrap) {
      delta = (delta + 0.5f).mod(1f) - 0.5f
    }
    val d = (delta / width).coerceIn(-1f, 1f)
    if (abs(delta) > width) return 0f
    return when (shape) {
      RangeShape.Smooth -> (0.5f * (1f + cos(PI.toFloat() * d))).coerceIn(0f, 1f)
      RangeShape.Gaussian -> exp(-4.5f * d * d).coerceIn(0f, 1f)
      RangeShape.Triangle -> (1f - abs(d)).coerceIn(0f, 1f)
      RangeShape.RampUp -> ((d + 1f) * 0.5f).coerceIn(0f, 1f)
      RangeShape.RampDown -> ((1f - d) * 0.5f).coerceIn(0f, 1f)
    }
  }
}

/**
 * **Effect 1 — Range Selector Wave** (After Effects *Range Selector* / Cavalry *Sub-Mesh*).
 *
 * Sweeps a [RangeSelector] across the shaped glyphs of [text], interpolating each glyph's variable
 * font axes between [baseAxes] and [peakAxes], lifting active glyphs by [peakOffsetY], and scaling
 * them by [peakScale] around their baseline midpoint while dynamically reflowing horizontal
 * advances using each glyph's live `HVAR`/`gvar` width.
 */
@Composable
public fun VariableFontRangeWaveText(
  text: String,
  font: VariableFont,
  baseAxes: Map<String, Float>,
  peakAxes: Map<String, Float>,
  selector: () -> RangeSelector,
  fontSize: TextUnit,
  modifier: Modifier = Modifier,
  peakOffsetY: Dp = (-6).dp,
  peakScale: Float = 1.12f,
  color: Color = Color.Unspecified,
  brush: Brush? = null,
  location: Map<String, Float> = emptyMap(),
  kerningLocation: Map<String, Float> = location,
) {
  requireEffectSp(fontSize)
  val tags = remember(baseAxes, peakAxes) { (baseAxes.keys + peakAxes.keys).distinct() }
  val baseValues = remember(tags, baseAxes, font) { resolveAxisValues(font, tags, baseAxes) }
  val peakValues = remember(tags, peakAxes, font) { resolveAxisValues(font, tags, peakAxes) }
  val density = LocalDensity.current
  val pixelSize = with(density) { fontSize.toPx() }
  val peakDyPx = with(density) { peakOffsetY.toPx() }
  val glyphs =
    remember(text, font, tags, location, kerningLocation, pixelSize) {
      font.cachedGlyphOutlines(text, tags, location, kerningLocation, pixelSize = pixelSize)
    }
  val runRenderer =
    remember(glyphs) { GlyphRunRenderer(glyphs, font.unitsPerEm, font.ascender, font.descender) }
  val fill = if (color.isSpecified) color else Color.White
  val padY = ceil(abs(peakDyPx) + pixelSize * 0.25f).toInt()
  GlyphRunLayout(
    runRenderer = runRenderer,
    fontSize = fontSize,
    extraTopPx = padY,
    extraBottomPx = padY,
    textSemantics = text,
    modifier = modifier,
  ) { scale, topPad ->
    val sel = selector()
    val count = runRenderer.size
    runRenderer.combinedPath.rewind()
    var penX = 0f
    for (i in 0 until count) {
      val w = sel.weight(i, count)
      for (a in tags.indices) {
        runRenderer.axisScratch[a] = baseValues[a] + (peakValues[a] - baseValues[a]) * w
      }
      penX += runRenderer.kerningBefore(i) * scale
      val adv = runRenderer.evaluateGlyphAdvance(i, runRenderer.axisScratch) * scale
      val s = 1f + (peakScale - 1f) * w
      val dy = peakDyPx * w
      runRenderer.appendGlyph(
        glyphIndex = i,
        axisValues = runRenderer.axisScratch,
        scale = scale,
        originX = penX,
        baselineY = topPad + runRenderer.ascender * scale + dy,
        pivotX = adv * 0.5f,
        scaleX = s,
        scaleY = s,
        rotationRad = 0f,
      )
      penX += adv
    }
    if (brush != null) {
      drawPath(runRenderer.combinedPath, brush)
    } else {
      drawPath(runRenderer.combinedPath, fill)
    }
  }
}

/**
 * **Effect 2 — Trim Paths / Write-On Outline** (After Effects *Trim Paths* / Rive *Stroke Trim*).
 *
 * Evaluates the variable-font outline of [text] at [axes] and extracts a stroked sub-path from
 * [startFraction] to [endFraction] (shifted by [offsetFraction]) using [PathMeasure], optionally
 * cross-fading the glyph fill via [fillAlpha].
 */
@Composable
public fun VariableFontTrimPathText(
  text: String,
  font: VariableFont,
  axes: Map<String, () -> Float>,
  fontSize: TextUnit,
  endFraction: () -> Float,
  modifier: Modifier = Modifier,
  startFraction: () -> Float = { 0f },
  offsetFraction: () -> Float = { 0f },
  strokeWidth: Dp = 2.dp,
  strokeCap: StrokeCap = StrokeCap.Round,
  strokeJoin: StrokeJoin = StrokeJoin.Round,
  strokeColor: Color = Color.White,
  strokeBrush: Brush? = null,
  fillColor: Color = Color.Unspecified,
  fillBrush: Brush? = null,
  fillAlpha: () -> Float = { 0f },
  location: Map<String, Float> = emptyMap(),
  kerningLocation: Map<String, Float> = location,
) {
  requireEffectSp(fontSize)
  val tags = axes.keys.toList()
  val density = LocalDensity.current
  val pixelSize = with(density) { fontSize.toPx() }
  val strokePx = with(density) { strokeWidth.toPx() }
  val outline =
    remember(text, font, tags, location, kerningLocation, pixelSize) {
      font.cachedOutline(text, tags, location, kerningLocation, pixelSize = pixelSize)
    }
  val renderer =
    remember(outline) { VariableFontTextRenderer(VariableTextOutlineEvaluator(outline)) }
  val evaluator = renderer.evaluator
  val sources = remember(outline, axes) { outline.axes.map { axes.getValue(it) } }
  val values = remember(outline) { FloatArray(outline.axes.size) }
  val coords = remember(evaluator) { FloatArray(evaluator.coordinateCount) }
  val contourPath = remember { Path() }
  val pathMeasure = remember { PathMeasure() }
  val trimmedPath = remember { Path() }
  val strokeStyle =
    remember(strokePx, strokeCap, strokeJoin) {
      Stroke(width = strokePx, cap = strokeCap, join = strokeJoin)
    }
  val pad = ceil(strokePx * 2f).toInt()
  SingleOutlineEffectLayout(
    evaluator = evaluator,
    fontSize = fontSize,
    extraHorizontalPx = pad,
    extraVerticalPx = pad,
    textSemantics = text,
    modifier = modifier,
  ) { scale, padX, padY ->
    for (i in values.indices) values[i] = sources[i]()
    renderer.update(values, scale)
    evaluator.evaluate(values, coords)
    val start = startFraction().coerceIn(0f, 1f)
    val end = endFraction().coerceIn(0f, 1f)
    val offset = offsetFraction()
    trimmedPath.rewind()
    if (end > start) {
      val ascender = evaluator.ascender.toFloat()
      var p = 0
      for (verb in evaluator.verbs) {
        when (verb) {
          VariableTextOutlineEvaluator.VERB_MOVE -> {
            contourPath.rewind()
            contourPath.moveTo(coords[p] * scale, (ascender - coords[p + 1]) * scale)
            p += 2
          }
          VariableTextOutlineEvaluator.VERB_LINE -> {
            contourPath.lineTo(coords[p] * scale, (ascender - coords[p + 1]) * scale)
            p += 2
          }
          VariableTextOutlineEvaluator.VERB_QUAD -> {
            contourPath.quadraticTo(
              coords[p] * scale,
              (ascender - coords[p + 1]) * scale,
              coords[p + 2] * scale,
              (ascender - coords[p + 3]) * scale,
            )
            p += 4
          }
          else -> {
            contourPath.close()
            pathMeasure.setPath(contourPath, false)
            val len = pathMeasure.length
            if (len > 0f) {
              extractWrappedSegment(pathMeasure, len, start, end, offset, trimmedPath)
            }
          }
        }
      }
    }
    withTransform({ translate(padX.toFloat(), padY.toFloat()) }) {
      val fAlpha = fillAlpha().coerceIn(0f, 1f)
      if (fAlpha > 0f) {
        if (fillBrush != null) {
          drawPath(renderer.path, fillBrush, alpha = fAlpha)
        } else if (fillColor.isSpecified) {
          drawPath(renderer.path, fillColor, alpha = fAlpha)
        } else {
          drawPath(renderer.path, strokeColor, alpha = fAlpha)
        }
      }
      if (strokeBrush != null) {
        drawPath(trimmedPath, strokeBrush, style = strokeStyle)
      } else {
        drawPath(trimmedPath, strokeColor, style = strokeStyle)
      }
    }
  }
}

/**
 * **Effect 3 — Wave Warp / Liquid Mesh Deformer** (After Effects *Wave Warp* / Cavalry *Deformer*).
 *
 * Evaluates the variable-font outline at [axes], subdivides straight segments so vertical and
 * horizontal stems bend smoothly, and displaces every on-curve and off-curve vertex by a travelling
 * sinusoidal wave field.
 */
@Composable
public fun VariableFontWaveWarpText(
  text: String,
  font: VariableFont,
  axes: Map<String, () -> Float>,
  fontSize: TextUnit,
  phase: () -> Float,
  modifier: Modifier = Modifier,
  amplitudeX: Dp = 1.5.dp,
  amplitudeY: Dp = 4.5.dp,
  wavelengthX: Dp = 56.dp,
  wavelengthY: Dp = 36.dp,
  color: Color = Color.Unspecified,
  brush: Brush? = null,
  location: Map<String, Float> = emptyMap(),
  kerningLocation: Map<String, Float> = location,
) {
  requireEffectSp(fontSize)
  val tags = axes.keys.toList()
  val density = LocalDensity.current
  val pixelSize = with(density) { fontSize.toPx() }
  val ampXPx = with(density) { amplitudeX.toPx() }
  val ampYPx = with(density) { amplitudeY.toPx() }
  val freqX = (2f * PI.toFloat()) / with(density) { wavelengthX.toPx().coerceAtLeast(1f) }
  val freqY = (2f * PI.toFloat()) / with(density) { wavelengthY.toPx().coerceAtLeast(1f) }
  val outline =
    remember(text, font, tags, location, kerningLocation, pixelSize) {
      font.cachedOutline(text, tags, location, kerningLocation, pixelSize = pixelSize)
    }
  val evaluator = remember(outline) { VariableTextOutlineEvaluator(outline) }
  val sources = remember(outline, axes) { outline.axes.map { axes.getValue(it) } }
  val values = remember(outline) { FloatArray(outline.axes.size) }
  val coords = remember(evaluator) { FloatArray(evaluator.coordinateCount) }
  val warpedPath = remember { Path() }
  val fill = if (color.isSpecified) color else Color.White
  val padX = ceil(abs(ampXPx) + 4f).toInt()
  val padY = ceil(abs(ampYPx) + 4f).toInt()
  SingleOutlineEffectLayout(
    evaluator = evaluator,
    fontSize = fontSize,
    extraHorizontalPx = padX,
    extraVerticalPx = padY,
    textSemantics = text,
    modifier = modifier,
  ) { scale, ox, oy ->
    for (i in values.indices) values[i] = sources[i]()
    evaluator.evaluate(values, coords)
    val ph = phase()
    val ascender = evaluator.ascender.toFloat()
    val maxSegPx = (pixelSize * 0.12f).coerceAtLeast(3f)

    fun warpX(x: Float, y: Float): Float = ox + x + ampXPx * sin(ph + y * freqY + x * freqX * 0.35f)
    fun warpY(x: Float, y: Float): Float = oy + y + ampYPx * sin(ph + x * freqX)

    warpedPath.rewind()
    var p = 0
    var curX = 0f
    var curY = 0f
    for (verb in evaluator.verbs) {
      when (verb) {
        VariableTextOutlineEvaluator.VERB_MOVE -> {
          curX = coords[p] * scale
          curY = (ascender - coords[p + 1]) * scale
          warpedPath.moveTo(warpX(curX, curY), warpY(curX, curY))
          p += 2
        }
        VariableTextOutlineEvaluator.VERB_LINE -> {
          val nx = coords[p] * scale
          val ny = (ascender - coords[p + 1]) * scale
          val dist = hypot(nx - curX, ny - curY)
          val steps = ceil(dist / maxSegPx).toInt().coerceIn(1, 16)
          for (s in 1..steps) {
            val t = s.toFloat() / steps.toFloat()
            val ix = curX + (nx - curX) * t
            val iy = curY + (ny - curY) * t
            warpedPath.lineTo(warpX(ix, iy), warpY(ix, iy))
          }
          curX = nx
          curY = ny
          p += 2
        }
        VariableTextOutlineEvaluator.VERB_QUAD -> {
          val cx = coords[p] * scale
          val cy = (ascender - coords[p + 1]) * scale
          val nx = coords[p + 2] * scale
          val ny = (ascender - coords[p + 3]) * scale
          warpedPath.quadraticTo(warpX(cx, cy), warpY(cx, cy), warpX(nx, ny), warpY(nx, ny))
          curX = nx
          curY = ny
          p += 4
        }
        else -> warpedPath.close()
      }
    }
    if (brush != null) {
      drawPath(warpedPath, brush)
    } else {
      drawPath(warpedPath, fill)
    }
  }
}

/**
 * **Effect 4 — Kinetic Cascade / Elastic Squash & Stretch** (Apple Motion *Sequence Text* / GSAP
 * *SplitText*).
 *
 * Runs a staggered per-character elastic hop where each glyph stretches in flight (`scaleY > 1`,
 * narrow/light [flightAxes]), squashes on impact (`scaleX > 1`, heavy/wide [impactAxes]) around its
 * baseline center, and settles into [restAxes], with live variable-font advance reflow.
 */
@Composable
public fun VariableFontKineticCascadeText(
  text: String,
  font: VariableFont,
  progress: () -> Float,
  fontSize: TextUnit,
  modifier: Modifier = Modifier,
  flightAxes: Map<String, Float> = mapOf("wght" to 220f, "wdth" to 65f),
  impactAxes: Map<String, Float> = mapOf("wght" to 1000f, "wdth" to 140f),
  restAxes: Map<String, Float> = mapOf("wght" to 600f, "wdth" to 100f),
  jumpHeight: Dp = 14.dp,
  stagger: Float = 0.11f,
  squashAmount: Float = 0.28f,
  color: Color = Color.Unspecified,
  brush: Brush? = null,
  location: Map<String, Float> = emptyMap(),
  kerningLocation: Map<String, Float> = location,
) {
  requireEffectSp(fontSize)
  val tags =
    remember(flightAxes, impactAxes, restAxes) {
      (flightAxes.keys + impactAxes.keys + restAxes.keys).distinct()
    }
  val flightVals = remember(tags, flightAxes, font) { resolveAxisValues(font, tags, flightAxes) }
  val impactVals = remember(tags, impactAxes, font) { resolveAxisValues(font, tags, impactAxes) }
  val restVals = remember(tags, restAxes, font) { resolveAxisValues(font, tags, restAxes) }
  val density = LocalDensity.current
  val pixelSize = with(density) { fontSize.toPx() }
  val jumpPx = with(density) { jumpHeight.toPx() }
  val glyphs =
    remember(text, font, tags, location, kerningLocation, pixelSize) {
      font.cachedGlyphOutlines(text, tags, location, kerningLocation, pixelSize = pixelSize)
    }
  val runRenderer =
    remember(glyphs) { GlyphRunRenderer(glyphs, font.unitsPerEm, font.ascender, font.descender) }
  val fill = if (color.isSpecified) color else Color.White
  val topExtra = ceil(jumpPx + pixelSize * 0.35f).toInt()
  val bottomExtra = ceil(pixelSize * 0.15f).toInt()
  GlyphRunLayout(
    runRenderer = runRenderer,
    fontSize = fontSize,
    extraTopPx = topExtra,
    extraBottomPx = bottomExtra,
    textSemantics = text,
    modifier = modifier,
  ) { scale, topPad ->
    val prog = progress()
    val count = runRenderer.size
    runRenderer.combinedPath.rewind()
    var penX = 0f
    for (i in 0 until count) {
      val local = (prog - i * stagger).mod(1f)
      var dy = 0f
      var sx = 1f
      var sy = 1f
      when {
        local < 0.45f -> {
          // Flight phase: parabolic/sine hop + vertical stretch, morphing toward flightAxes.
          val u = local / 0.45f
          val arc = sin(u * PI.toFloat())
          dy = -jumpPx * arc
          sy = 1f + squashAmount * 0.85f * arc
          sx = 1f / sy
          for (a in tags.indices) {
            runRenderer.axisScratch[a] = restVals[a] + (flightVals[a] - restVals[a]) * arc
          }
        }
        local < 0.75f -> {
          // Impact squash & damped rebound on baseline, morphing toward impactAxes.
          val u = (local - 0.45f) / 0.30f
          val squash = sin(u * PI.toFloat()) * (1f - u * 0.4f)
          sx = 1f + squashAmount * squash
          sy = 1f / sx
          for (a in tags.indices) {
            runRenderer.axisScratch[a] = restVals[a] + (impactVals[a] - restVals[a]) * squash
          }
        }
        else -> {
          for (a in tags.indices) {
            runRenderer.axisScratch[a] = restVals[a]
          }
        }
      }
      penX += runRenderer.kerningBefore(i) * scale
      val adv = runRenderer.evaluateGlyphAdvance(i, runRenderer.axisScratch) * scale
      runRenderer.appendGlyph(
        glyphIndex = i,
        axisValues = runRenderer.axisScratch,
        scale = scale,
        originX = penX,
        baselineY = topPad + runRenderer.ascender * scale + dy,
        pivotX = adv * 0.5f,
        scaleX = sx,
        scaleY = sy,
        rotationRad = 0f,
      )
      penX += adv
    }
    if (brush != null) {
      drawPath(runRenderer.combinedPath, brush)
    } else {
      drawPath(runRenderer.combinedPath, fill)
    }
  }
}

/**
 * **Effect 5 — Time-Shifted Echo / Motion Trail** (After Effects *Echo* / Cavalry *Duplicator*).
 *
 * Draws [echoCount] historical ghost copies behind the primary text (`lagIndex = echoCount downTo
 * 1`) followed by the foreground text (`lagIndex = 0`). Each copy evaluates the variable-font
 * outline at its own time-delayed axis values from [axesAtLag], shifted by `lagIndex *
 * (echoOffsetX, echoOffsetY)`.
 */
@Composable
public fun VariableFontEchoText(
  text: String,
  font: VariableFont,
  axesAtLag: Map<String, (lagIndex: Int) -> Float>,
  fontSize: TextUnit,
  modifier: Modifier = Modifier,
  echoCount: Int = 5,
  echoOffsetX: Dp = 3.dp,
  echoOffsetY: Dp = (-2.5).dp,
  foregroundColor: Color = Color.White,
  echoColors: List<Color> =
    listOf(
      Color(0xFF00E5FF),
      Color(0xFF7C4DFF),
      Color(0xFFFF4081),
      Color(0xFFFF9100),
      Color(0xFFFFEA00),
    ),
  location: Map<String, Float> = emptyMap(),
  kerningLocation: Map<String, Float> = location,
) {
  requireEffectSp(fontSize)
  val tags = axesAtLag.keys.toList()
  val density = LocalDensity.current
  val pixelSize = with(density) { fontSize.toPx() }
  val dxPx = with(density) { echoOffsetX.toPx() }
  val dyPx = with(density) { echoOffsetY.toPx() }
  val outline =
    remember(text, font, tags, location, kerningLocation, pixelSize) {
      font.cachedOutline(text, tags, location, kerningLocation, pixelSize = pixelSize)
    }
  val renderer =
    remember(outline) { VariableFontTextRenderer(VariableTextOutlineEvaluator(outline)) }
  val sources = remember(outline, axesAtLag) { outline.axes.map { axesAtLag.getValue(it) } }
  val values = remember(outline) { FloatArray(outline.axes.size) }
  val padX = ceil(abs(dxPx) * echoCount + 4f).toInt()
  val padY = ceil(abs(dyPx) * echoCount + 4f).toInt()
  SingleOutlineEffectLayout(
    evaluator = renderer.evaluator,
    fontSize = fontSize,
    extraHorizontalPx = padX,
    extraVerticalPx = padY,
    textSemantics = text,
    modifier = modifier,
  ) { scale, ox, oy ->
    for (lag in echoCount downTo 0) {
      for (i in values.indices) values[i] = sources[i](lag)
      renderer.update(values, scale)
      val tx = ox + lag * dxPx
      val ty = oy + lag * dyPx
      withTransform({ translate(tx, ty) }) {
        if (lag == 0) {
          drawPath(renderer.path, foregroundColor)
        } else {
          val c = echoColors[(lag - 1) % echoColors.size]
          val alpha = ((echoCount - lag + 1).toFloat() / (echoCount + 1).toFloat()) * 0.78f
          drawPath(renderer.path, c, alpha = alpha.coerceIn(0f, 1f))
        }
      }
    }
  }
}

/**
 * **Effect 6 — Chromatic Glitch / RGB Split & Scanline Slice** (After Effects *RGB Split +
 * Displacement Map*).
 *
 * Splits the variable-font outline into additive Red, Green, and Blue channel passes
 * (`BlendMode.Plus`) and slices the text into horizontal scanline bands that shift horizontally
 * during deterministic glitch bursts driven by [time].
 */
@Composable
public fun VariableFontGlitchText(
  text: String,
  font: VariableFont,
  axes: Map<String, () -> Float>,
  fontSize: TextUnit,
  time: () -> Float,
  modifier: Modifier = Modifier,
  maxRgbSplit: Dp = 4.dp,
  maxSliceShift: Dp = 8.dp,
  sliceCount: Int = 4,
  location: Map<String, Float> = emptyMap(),
  kerningLocation: Map<String, Float> = location,
) {
  requireEffectSp(fontSize)
  val tags = axes.keys.toList()
  val density = LocalDensity.current
  val pixelSize = with(density) { fontSize.toPx() }
  val rgbSplitPx = with(density) { maxRgbSplit.toPx() }
  val sliceShiftPx = with(density) { maxSliceShift.toPx() }
  val outline =
    remember(text, font, tags, location, kerningLocation, pixelSize) {
      font.cachedOutline(text, tags, location, kerningLocation, pixelSize = pixelSize)
    }
  val renderer =
    remember(outline) { VariableFontTextRenderer(VariableTextOutlineEvaluator(outline)) }
  val sources = remember(outline, axes) { outline.axes.map { axes.getValue(it) } }
  val values = remember(outline) { FloatArray(outline.axes.size) }
  val padX = ceil(rgbSplitPx + sliceShiftPx + 4f).toInt()
  val padY = 6
  SingleOutlineEffectLayout(
    evaluator = renderer.evaluator,
    fontSize = fontSize,
    extraHorizontalPx = padX,
    extraVerticalPx = padY,
    textSemantics = text,
    modifier = modifier,
  ) { scale, ox, oy ->
    val t = time()
    val step = floor(t * 12f).toInt()
    // Deterministic burst envelope: active ~65% of steps with varying strength
    val burstNoise = abs(hashSigned(step, 17))
    val burst = if (burstNoise > 0.25f) (burstNoise - 0.25f) / 0.75f else 0.18f
    for (i in values.indices) {
      val base = sources[i]()
      val jitter = hashSigned(step, i + 3) * burst * 120f
      values[i] = base + jitter
    }
    renderer.update(values, scale)
    val splitX = rgbSplitPx * (0.3f + 0.7f * burst) * hashSigned(step, 99)
    val splitY = rgbSplitPx * 0.25f * burst * hashSigned(step, 101)
    val bandHeight = size.height / sliceCount.coerceAtLeast(1)
    val red = Color(0xFFFF1744)
    val green = Color(0xFF00E676)
    val blue = Color(0xFF2979FF)
    for (s in 0 until sliceCount) {
      val top = s * bandHeight
      val bottom = if (s == sliceCount - 1) size.height else (s + 1) * bandHeight
      val sliceShift =
        if (abs(hashSigned(step, s + 31)) > 0.35f) {
          sliceShiftPx * burst * hashSigned(step, s + 71)
        } else {
          0f
        }
      clipRect(left = 0f, top = top, right = size.width, bottom = bottom) {
        withTransform({ translate(ox + sliceShift - splitX, oy - splitY) }) {
          drawPath(renderer.path, red, blendMode = BlendMode.Plus)
        }
        withTransform({ translate(ox + sliceShift, oy.toFloat()) }) {
          drawPath(renderer.path, green, blendMode = BlendMode.Plus)
        }
        withTransform({ translate(ox + sliceShift + splitX, oy + splitY) }) {
          drawPath(renderer.path, blue, blendMode = BlendMode.Plus)
        }
      }
    }
  }
}

/**
 * **Effect 7 — Specular Light Sweep / Metallic Shimmer** (After Effects *CC Light Sweep* / Rive
 * *Animated Gradient*).
 *
 * Fills the variable-font outline of [text] with an angled metallic/shimmer linear gradient whose
 * specular highlight band sweeps across the text bounds according to [sweepProgress] in `[0f, 1f]`.
 */
@Composable
public fun VariableFontShimmerText(
  text: String,
  font: VariableFont,
  axes: Map<String, () -> Float>,
  fontSize: TextUnit,
  sweepProgress: () -> Float,
  modifier: Modifier = Modifier,
  baseColors: List<Color> = listOf(Color(0xFF00B4DB), Color(0xFF8E54E9), Color(0xFFFF416C)),
  highlightColor: Color = Color(0xFFFFFFFF),
  bandWidthFraction: Float = 0.28f,
  angleDegrees: Float = 24f,
  location: Map<String, Float> = emptyMap(),
  kerningLocation: Map<String, Float> = location,
) {
  requireEffectSp(fontSize)
  val tags = axes.keys.toList()
  val density = LocalDensity.current
  val pixelSize = with(density) { fontSize.toPx() }
  val outline =
    remember(text, font, tags, location, kerningLocation, pixelSize) {
      font.cachedOutline(text, tags, location, kerningLocation, pixelSize = pixelSize)
    }
  val renderer =
    remember(outline) { VariableFontTextRenderer(VariableTextOutlineEvaluator(outline)) }
  val sources = remember(outline, axes) { outline.axes.map { axes.getValue(it) } }
  val values = remember(outline) { FloatArray(outline.axes.size) }
  val baseBrush = remember(baseColors) { Brush.horizontalGradient(baseColors) }
  SingleOutlineEffectLayout(
    evaluator = renderer.evaluator,
    fontSize = fontSize,
    extraHorizontalPx = 4,
    extraVerticalPx = 4,
    textSemantics = text,
    modifier = modifier,
  ) { scale, ox, oy ->
    for (i in values.indices) values[i] = sources[i]()
    renderer.update(values, scale)
    val w = size.width.coerceAtLeast(1f)
    val h = size.height.coerceAtLeast(1f)
    val bandW = w * bandWidthFraction.coerceIn(0.05f, 0.8f)
    val p = sweepProgress().mod(1f)
    val centerX = -bandW + p * (w + 2f * bandW)
    val rad = angleDegrees * (PI.toFloat() / 180f)
    val dx = cos(rad) * bandW
    val dy = sin(rad) * h
    val shimmerBrush =
      Brush.linearGradient(
        colors = listOf(Color.Transparent, highlightColor.copy(alpha = 0.92f), Color.Transparent),
        start = Offset(centerX - dx, h * 0.5f - dy),
        end = Offset(centerX + dx, h * 0.5f + dy),
      )
    withTransform({ translate(ox.toFloat(), oy.toFloat()) }) {
      drawPath(renderer.path, baseBrush)
      drawPath(renderer.path, shimmerBrush, blendMode = BlendMode.Plus)
    }
  }
}

/**
 * **Effect 8 — Wiggly Selector / Boiling Hand-Drawn Text** (After Effects *Wiggly Selector* &
 * *Posterize Time*).
 *
 * Quantizes [timeSeconds] to [stepsPerSecond] (stop-motion cadence) and applies deterministic
 * per-glyph noise to variable-font axes ([baseAxes] ± [axisWiggle]), baseline position
 * ([offsetJitter]), rotation ([rotationJitterDegrees]), and contour vertices ([vertexJitter]).
 */
@Composable
public fun VariableFontWigglyText(
  text: String,
  font: VariableFont,
  baseAxes: Map<String, Float>,
  axisWiggle: Map<String, Float>,
  fontSize: TextUnit,
  timeSeconds: () -> Float,
  modifier: Modifier = Modifier,
  stepsPerSecond: Float = 10f,
  offsetJitter: Dp = 2.5.dp,
  rotationJitterDegrees: Float = 4.5f,
  vertexJitter: Dp = 0.75.dp,
  color: Color = Color.Unspecified,
  brush: Brush? = null,
  location: Map<String, Float> = emptyMap(),
  kerningLocation: Map<String, Float> = location,
) {
  requireEffectSp(fontSize)
  val tags = remember(baseAxes, axisWiggle) { (baseAxes.keys + axisWiggle.keys).distinct() }
  val baseValues = remember(tags, baseAxes, font) { resolveAxisValues(font, tags, baseAxes) }
  val wiggleAmounts =
    remember(tags, axisWiggle) { FloatArray(tags.size) { axisWiggle[tags[it]] ?: 0f } }
  val density = LocalDensity.current
  val pixelSize = with(density) { fontSize.toPx() }
  val offsetJitterPx = with(density) { offsetJitter.toPx() }
  val vertexJitterPx = with(density) { vertexJitter.toPx() }
  val glyphs =
    remember(text, font, tags, location, kerningLocation, pixelSize) {
      font.cachedGlyphOutlines(text, tags, location, kerningLocation, pixelSize = pixelSize)
    }
  val runRenderer =
    remember(glyphs) { GlyphRunRenderer(glyphs, font.unitsPerEm, font.ascender, font.descender) }
  val fill = if (color.isSpecified) color else Color.White
  val pad = ceil(offsetJitterPx + vertexJitterPx + pixelSize * 0.2f).toInt()
  GlyphRunLayout(
    runRenderer = runRenderer,
    fontSize = fontSize,
    extraTopPx = pad,
    extraBottomPx = pad,
    textSemantics = text,
    modifier = modifier,
  ) { scale, topPad ->
    val step = floor(timeSeconds() * stepsPerSecond).toInt()
    val count = runRenderer.size
    runRenderer.combinedPath.rewind()
    var penX = 0f
    for (i in 0 until count) {
      for (a in tags.indices) {
        val n = hashSigned(step * 31 + i, a + 11)
        runRenderer.axisScratch[a] = baseValues[a] + n * wiggleAmounts[a]
      }
      penX += runRenderer.kerningBefore(i) * scale
      val adv = runRenderer.evaluateGlyphAdvance(i, runRenderer.axisScratch) * scale
      val dx = hashSigned(step * 17 + i, 101) * offsetJitterPx * 0.6f
      val dy = hashSigned(step * 17 + i, 103) * offsetJitterPx
      val rotRad = hashSigned(step * 17 + i, 107) * rotationJitterDegrees * (PI.toFloat() / 180f)
      runRenderer.appendGlyph(
        glyphIndex = i,
        axisValues = runRenderer.axisScratch,
        scale = scale,
        originX = penX + dx,
        baselineY = topPad + runRenderer.ascender * scale + dy,
        pivotX = adv * 0.5f,
        scaleX = 1f,
        scaleY = 1f,
        rotationRad = rotRad,
        vertexJitterPx = vertexJitterPx,
        jitterSeed = step * 97 + i * 13,
      )
      penX += adv
    }
    if (brush != null) {
      drawPath(runRenderer.combinedPath, brush)
    } else {
      drawPath(runRenderer.combinedPath, fill)
    }
  }
}

/**
 * **Effect 9 — Text on a Path / Baseline Wave** (After Effects *Path Options* / Cavalry *Path
 * Distribute*).
 *
 * Places each shaped glyph cluster along a sinusoidal baseline curve using its live variable-font
 * advance and rotates each glyph to match the curve's tangent angle at its baseline midpoint.
 */
@Composable
public fun VariableFontPathText(
  text: String,
  font: VariableFont,
  axes: Map<String, () -> Float>,
  fontSize: TextUnit,
  phase: () -> Float,
  modifier: Modifier = Modifier,
  waveAmplitude: Dp = 8.dp,
  wavelength: Dp = 150.dp,
  alignToPath: Boolean = true,
  color: Color = Color.Unspecified,
  brush: Brush? = null,
  location: Map<String, Float> = emptyMap(),
  kerningLocation: Map<String, Float> = location,
) {
  requireEffectSp(fontSize)
  val tags = axes.keys.toList()
  val sources = remember(tags, axes) { tags.map { axes.getValue(it) } }
  val density = LocalDensity.current
  val pixelSize = with(density) { fontSize.toPx() }
  val ampPx = with(density) { waveAmplitude.toPx() }
  val kFreq = (2f * PI.toFloat()) / with(density) { wavelength.toPx().coerceAtLeast(1f) }
  val glyphs =
    remember(text, font, tags, location, kerningLocation, pixelSize) {
      font.cachedGlyphOutlines(text, tags, location, kerningLocation, pixelSize = pixelSize)
    }
  val runRenderer =
    remember(glyphs) { GlyphRunRenderer(glyphs, font.unitsPerEm, font.ascender, font.descender) }
  val fill = if (color.isSpecified) color else Color.White
  val padY = ceil(abs(ampPx) + pixelSize * 0.25f).toInt()
  GlyphRunLayout(
    runRenderer = runRenderer,
    fontSize = fontSize,
    extraTopPx = padY,
    extraBottomPx = padY,
    textSemantics = text,
    modifier = modifier,
  ) { scale, topPad ->
    val ph = phase()
    for (a in tags.indices) {
      runRenderer.axisScratch[a] = sources[a]()
    }
    val count = runRenderer.size
    runRenderer.combinedPath.rewind()
    var penX = 0f
    for (i in 0 until count) {
      penX += runRenderer.kerningBefore(i) * scale
      val adv = runRenderer.evaluateGlyphAdvance(i, runRenderer.axisScratch) * scale
      val midX = penX + adv * 0.5f
      val angleArg = midX * kFreq + ph
      val curveY = ampPx * sin(angleArg)
      val slope = if (alignToPath) ampPx * kFreq * cos(angleArg) else 0f
      val theta = if (alignToPath) atan(slope) else 0f
      runRenderer.appendGlyph(
        glyphIndex = i,
        axisValues = runRenderer.axisScratch,
        scale = scale,
        originX = penX,
        baselineY = topPad + runRenderer.ascender * scale + curveY,
        pivotX = adv * 0.5f,
        scaleX = 1f,
        scaleY = 1f,
        rotationRad = theta,
      )
      penX += adv
    }
    if (brush != null) {
      drawPath(runRenderer.combinedPath, brush)
    } else {
      drawPath(runRenderer.combinedPath, fill)
    }
  }
}

/**
 * **Effect 10 — Neon Glow & Hollow Variable Core** (After Effects *Glow + Offset Paths*).
 *
 * Renders concentric translucent outer glow strokes around the variable-font outline of [text] at
 * [axes] using additive blending, topped by a bright inner neon core tube (and optional core fill).
 */
@Composable
public fun VariableFontNeonText(
  text: String,
  font: VariableFont,
  axes: Map<String, () -> Float>,
  fontSize: TextUnit,
  modifier: Modifier = Modifier,
  glowIntensity: () -> Float = { 1f },
  glowColor: Color = Color(0xFFFF007F),
  secondaryGlowColor: Color = Color(0xFF00E5FF),
  coreColor: Color = Color.White,
  maxGlowWidth: Dp = 11.dp,
  coreStrokeWidth: Dp = 1.5.dp,
  fillCoreAlpha: Float = 0.22f,
  location: Map<String, Float> = emptyMap(),
  kerningLocation: Map<String, Float> = location,
) {
  requireEffectSp(fontSize)
  val tags = axes.keys.toList()
  val density = LocalDensity.current
  val pixelSize = with(density) { fontSize.toPx() }
  val maxGlowPx = with(density) { maxGlowWidth.toPx() }
  val coreStrokePx = with(density) { coreStrokeWidth.toPx() }
  val outline =
    remember(text, font, tags, location, kerningLocation, pixelSize) {
      font.cachedOutline(text, tags, location, kerningLocation, pixelSize = pixelSize)
    }
  val renderer =
    remember(outline) { VariableFontTextRenderer(VariableTextOutlineEvaluator(outline)) }
  val sources = remember(outline, axes) { outline.axes.map { axes.getValue(it) } }
  val values = remember(outline) { FloatArray(outline.axes.size) }
  val glowStrokes =
    remember(maxGlowPx, coreStrokePx) {
      arrayOf(
        Stroke(width = maxGlowPx, cap = StrokeCap.Round, join = StrokeJoin.Round),
        Stroke(width = maxGlowPx * 0.65f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        Stroke(width = maxGlowPx * 0.38f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        Stroke(width = coreStrokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
      )
    }
  val pad = ceil(maxGlowPx + 4f).toInt()
  SingleOutlineEffectLayout(
    evaluator = renderer.evaluator,
    fontSize = fontSize,
    extraHorizontalPx = pad,
    extraVerticalPx = pad,
    textSemantics = text,
    modifier = modifier,
  ) { scale, ox, oy ->
    for (i in values.indices) values[i] = sources[i]()
    renderer.update(values, scale)
    val intensity = glowIntensity().coerceIn(0f, 1.5f)
    withTransform({ translate(ox.toFloat(), oy.toFloat()) }) {
      drawPath(
        renderer.path,
        secondaryGlowColor,
        alpha = (0.18f * intensity).coerceIn(0f, 1f),
        style = glowStrokes[0],
        blendMode = BlendMode.Plus,
      )
      drawPath(
        renderer.path,
        glowColor,
        alpha = (0.34f * intensity).coerceIn(0f, 1f),
        style = glowStrokes[1],
        blendMode = BlendMode.Plus,
      )
      drawPath(
        renderer.path,
        glowColor,
        alpha = (0.62f * intensity).coerceIn(0f, 1f),
        style = glowStrokes[2],
        blendMode = BlendMode.Plus,
      )
      if (fillCoreAlpha > 0f) {
        drawPath(
          renderer.path,
          glowColor,
          alpha = (fillCoreAlpha * intensity).coerceIn(0f, 1f),
          style = Fill,
        )
      }
      drawPath(
        renderer.path,
        coreColor,
        alpha = (0.85f + 0.15f * intensity).coerceIn(0f, 1f),
        style = glowStrokes[3],
      )
    }
  }
}

private fun requireEffectSp(fontSize: TextUnit) {
  require(fontSize.isSpecified && fontSize.isSp) { "fontSize must be in sp, was $fontSize" }
}

private fun resolveAxisValues(
  font: VariableFont,
  tags: List<String>,
  map: Map<String, Float>,
): FloatArray =
  FloatArray(tags.size) { i ->
    val tag = tags[i]
    map[tag] ?: font.axes.firstOrNull { it.tag == tag }?.defaultValue ?: 0f
  }

/** Deterministic signed hash in `[-1f, 1f]` with no allocation or state. */
private fun hashSigned(a: Int, b: Int): Float {
  var h = a * 0x27d4eb2d xor (b * 0x165667b1)
  h = (h xor (h ushr 15)) * -0x7a143595
  h = (h xor (h ushr 13)) * -0x3d4d51cb
  h = h xor (h ushr 16)
  return (h and 0x7fffffff).toFloat() / 1073741823.5f - 1f
}

private fun extractWrappedSegment(
  measure: PathMeasure,
  length: Float,
  startFraction: Float,
  endFraction: Float,
  offsetFraction: Float,
  out: Path,
) {
  val span = (endFraction - startFraction).coerceIn(0f, 1f)
  if (span <= 0f) return
  if (span >= 0.9999f) {
    measure.getSegment(0f, length, out, true)
    return
  }
  val s = (startFraction + offsetFraction).mod(1f)
  val e = s + span
  if (e <= 1f) {
    measure.getSegment(s * length, e * length, out, true)
  } else {
    measure.getSegment(s * length, length, out, true)
    measure.getSegment(0f, (e - 1f) * length, out, true)
  }
}

@Composable
private fun SingleOutlineEffectLayout(
  evaluator: VariableTextOutlineEvaluator,
  fontSize: TextUnit,
  extraHorizontalPx: Int,
  extraVerticalPx: Int,
  textSemantics: String,
  modifier: Modifier,
  onDraw: DrawScope.(scale: Float, offsetX: Int, offsetY: Int) -> Unit,
) {
  val measurePolicy =
    remember(evaluator, fontSize, extraHorizontalPx, extraVerticalPx) {
      MeasurePolicy { _, constraints ->
        val scale = fontSize.toPx() / evaluator.unitsPerEm
        val width = ceil(evaluator.width * scale).toInt() + extraHorizontalPx * 2
        val height =
          ceil((evaluator.ascender - evaluator.descender) * scale).toInt() + extraVerticalPx * 2
        val baseline = extraVerticalPx + (evaluator.ascender * scale).roundToInt()
        layout(
          constraints.constrainWidth(width),
          constraints.constrainHeight(height),
          mapOf(FirstBaseline to baseline, LastBaseline to baseline),
        ) {}
      }
    }
  Layout(
    modifier =
      modifier
        .semantics { text = AnnotatedString(textSemantics) }
        .drawBehind {
          val scale = fontSize.toPx() / evaluator.unitsPerEm
          onDraw(scale, extraHorizontalPx, extraVerticalPx)
        },
    measurePolicy = measurePolicy,
  )
}

@Composable
private fun GlyphRunLayout(
  runRenderer: GlyphRunRenderer,
  fontSize: TextUnit,
  extraTopPx: Int,
  extraBottomPx: Int,
  textSemantics: String,
  modifier: Modifier,
  onDraw: DrawScope.(scale: Float, topPad: Int) -> Unit,
) {
  val measurePolicy =
    remember(runRenderer, fontSize, extraTopPx, extraBottomPx) {
      MeasurePolicy { _, constraints ->
        val scale = fontSize.toPx() / runRenderer.unitsPerEm
        val width = ceil(runRenderer.maxWidth * scale).toInt()
        val height =
          ceil((runRenderer.ascender - runRenderer.descender) * scale).toInt() +
            extraTopPx +
            extraBottomPx
        val baseline = extraTopPx + (runRenderer.ascender * scale).roundToInt()
        layout(
          constraints.constrainWidth(width),
          constraints.constrainHeight(height),
          mapOf(FirstBaseline to baseline, LastBaseline to baseline),
        ) {}
      }
    }
  Layout(
    modifier =
      modifier
        .semantics { text = AnnotatedString(textSemantics) }
        .drawBehind {
          val scale = fontSize.toPx() / runRenderer.unitsPerEm
          onDraw(scale, extraTopPx)
        },
    measurePolicy = measurePolicy,
  )
}

/**
 * Evaluates a list of [VariableGlyphOutline]s and their live variable advances into one reused
 * [combinedPath] with zero per-frame allocations.
 */
internal class GlyphRunRenderer(
  glyphs: List<VariableGlyphOutline>,
  val unitsPerEm: Int,
  val ascender: Int,
  val descender: Int,
) {
  val size: Int = glyphs.size
  private val outlineEvaluators = Array(size) { VariableTextOutlineEvaluator(glyphs[it].outline) }
  private val advanceEvaluators = Array(size) { VariableTextOutlineEvaluator(glyphs[it].advance) }
  private val kernings = FloatArray(size) { glyphs[it].kerningBefore }
  private val coordinates = Array(size) { FloatArray(outlineEvaluators[it].coordinateCount) }
  private val advanceScratch = FloatArray(advanceEvaluators.maxOfOrNull { it.coordinateCount } ?: 4)
  val axisScratch = FloatArray(if (size > 0) outlineEvaluators[0].axes.size else 0)
  val maxWidth: Float =
    glyphs.fold(0f) { acc, g -> acc + max(0f, g.kerningBefore) + g.outline.width }
  val combinedPath: Path = Path()

  fun kerningBefore(index: Int): Float = kernings[index]

  fun evaluateGlyphAdvance(index: Int, axisValues: FloatArray): Float {
    advanceEvaluators[index].evaluate(axisValues, advanceScratch)
    return advanceScratch[0]
  }

  fun appendGlyph(
    glyphIndex: Int,
    axisValues: FloatArray,
    scale: Float,
    originX: Float,
    baselineY: Float,
    pivotX: Float,
    scaleX: Float,
    scaleY: Float,
    rotationRad: Float,
    vertexJitterPx: Float = 0f,
    jitterSeed: Int = 0,
  ) {
    val evaluator = outlineEvaluators[glyphIndex]
    val c = coordinates[glyphIndex]
    evaluator.evaluate(axisValues, c)
    val cosA = if (rotationRad == 0f) 1f else cos(rotationRad)
    val sinA = if (rotationRad == 0f) 0f else sin(rotationRad)
    var ptIdx = 0

    fun tx(fontX: Float, fontY: Float): Float {
      val lx = (fontX * scale - pivotX) * scaleX
      val ly = (-fontY * scale) * scaleY
      val rx = lx * cosA - ly * sinA
      val jitter =
        if (vertexJitterPx > 0f) hashSigned(jitterSeed, ptIdx++ * 2) * vertexJitterPx else 0f
      return originX + pivotX + rx + jitter
    }

    fun ty(fontX: Float, fontY: Float): Float {
      val lx = (fontX * scale - pivotX) * scaleX
      val ly = (-fontY * scale) * scaleY
      val ry = lx * sinA + ly * cosA
      val jitter =
        if (vertexJitterPx > 0f) hashSigned(jitterSeed, ptIdx++ * 2 + 1) * vertexJitterPx else 0f
      return baselineY + ry + jitter
    }

    var p = 0
    for (verb in evaluator.verbs) {
      when (verb) {
        VariableTextOutlineEvaluator.VERB_MOVE -> {
          val x = c[p]
          val y = c[p + 1]
          combinedPath.moveTo(tx(x, y), ty(x, y))
          p += 2
        }
        VariableTextOutlineEvaluator.VERB_LINE -> {
          val x = c[p]
          val y = c[p + 1]
          combinedPath.lineTo(tx(x, y), ty(x, y))
          p += 2
        }
        VariableTextOutlineEvaluator.VERB_QUAD -> {
          val x1 = c[p]
          val y1 = c[p + 1]
          val x2 = c[p + 2]
          val y2 = c[p + 3]
          combinedPath.quadraticTo(tx(x1, y1), ty(x1, y1), tx(x2, y2), ty(x2, y2))
          p += 4
        }
        else -> combinedPath.close()
      }
    }
  }
}
