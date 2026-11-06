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

import android.annotation.SuppressLint
import androidx.compose.remote.core.operations.paint.PaintBundle
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression as AFE
import androidx.compose.remote.creation.compose.capture.RemoteComposeCreationState
import androidx.compose.remote.creation.compose.layout.RemoteCanvas
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.width
import androidx.compose.remote.creation.compose.state.RemoteDp
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.abs
import androidx.compose.remote.creation.compose.state.asRdp
import androidx.compose.remote.creation.compose.state.atan2
import androidx.compose.remote.creation.compose.state.clamp
import androidx.compose.remote.creation.compose.state.cos
import androidx.compose.remote.creation.compose.state.floor
import androidx.compose.remote.creation.compose.state.max
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.sin
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI

/*
 * The Remote Compose counterparts of flexpress-compose's text effects, for a RemoteString the
 * player may change: a clock, a counter, a label from a data source. Each draws one line through a
 * RemoteGlyphLine, one player loop over the text's positions, so the document holds each glyph
 * set's outlines once whatever maxLength is. Every driver (phase, progress, time, light angle) is a
 * RemoteFloat, so the player animates them with no host.
 *
 * Effects that change a glyph's axes per position (range wave, cascade, wiggle) blend each glyph
 * between its outlines at two design-space locations, as the player cannot give each position its
 * own axis values: linear between the two, where Compose UI evaluates the font at each value.
 */

private const val RAD_TO_DEG = (180.0 / PI).toFloat()

/** What a flat effect's body draws with: the line, its origin and the canvas. */
@SuppressLint("RestrictedApi")
internal class FlatLineScope(
  val state: RemoteComposeCreationState,
  val line: RemoteGlyphLine,
  /** Where the line starts, in pixels from the canvas' left. */
  val originX: RemoteFloat,
  /** The baseline, in pixels from the canvas' top. */
  val baseline: RemoteFloat,
  val canvasWidth: RemoteFloat,
  val canvasHeight: RemoteFloat,
  /** Pixels per font unit. */
  val scale: RemoteFloat,
) {
  val writer = state.document

  fun id(value: RemoteFloat): Float = line.id(value)

  fun paint(
    color: Color,
    alpha: Float = 1f,
    style: Int = PaintBundle.STYLE_FILL,
    strokeWidth: Float = 0f,
    blendMode: Int = PaintBundle.BLEND_MODE_SRC_OVER,
  ) {
    writer.rcPaint
      .setAntiAlias(true)
      .setColor(color.copy(alpha = (color.alpha * alpha).coerceIn(0f, 1f)).toArgb())
      .setStyle(style)
      .setStrokeWidth(strokeWidth)
      .setBlendMode(blendMode)
      .commit()
  }

  /**
   * Draws every position's glyph with a linear gradient from canvas point ([x0], [y0]) to ([x1],
   * [y1]) over the whole line. A paint's gradient is read in the coordinates it is drawn in, and
   * each glyph is drawn in its own font units, so each position sets the gradient's ends in its
   * glyph's coordinates first.
   */
  fun drawLineWithGradient(
    x0: RemoteFloat,
    y0: RemoteFloat,
    x1: RemoteFloat,
    y1: RemoteFloat,
    colors: IntArray,
    blendMode: Int = PaintBundle.BLEND_MODE_SRC_OVER,
  ) {
    val ox = id(originX)
    val oy = id(baseline)
    line.forEachSlot { slot ->
      val left = originX + slot.pen
      fun localX(x: RemoteFloat) = id((x - left) / slot.unitScale)
      fun localY(y: RemoteFloat) = id((baseline - y) / slot.unitScale)
      writer.rcPaint
        .setAntiAlias(true)
        .setStyle(PaintBundle.STYLE_FILL)
        .setBlendMode(blendMode)
        .setLinearGradient(localX(x0), localY(y0), localX(x1), localY(y1), colors, null, 0)
        .commit()
      line.drawRigid(slot) {
        writer.translate(ox, oy)
        writer.translate(id(slot.pen), 0f)
      }
    }
  }

  /** Draws every position's glyph rigidly at its place on the baseline, shifted by ([dx], [dy]). */
  fun drawLine(dx: RemoteFloat = 0f.rf, dy: RemoteFloat = 0f.rf) {
    val x0 = id(originX + dx)
    val y0 = id(baseline + dy)
    line.forEachSlot { slot ->
      line.drawRigid(slot) {
        writer.translate(x0, y0)
        writer.translate(id(slot.pen), 0f)
      }
    }
  }
}

/**
 * A canvas for one flat line of up to [maxLength] of [glyphs]' widest characters, padded by [padX]
 * and [padY], whose [body] lays the line out and draws it.
 */
@SuppressLint("RestrictedApi")
@Composable
private fun FlatLineCanvas(
  text: RemoteString,
  maxLength: Int,
  glyphs: VariableFontGlyphs,
  fontSize: RemoteDp,
  modifier: RemoteModifier,
  padX: Dp,
  padY: Dp,
  tabularFigures: Boolean = false,
  /** The glyph sets the line's advances may come from: the box fits the widest of them. */
  instances: List<VariableFontGlyphs> = listOf(glyphs),
  prepare: (RemoteGlyphLine) -> Unit = {},
  body: FlatLineScope.() -> Unit,
) {
  val widest = instances.maxOf { it.widest }
  val font = glyphs.font
  val em = 1f / font.unitsPerEm
  val padXr = padX.asRdp()
  val padYr = padY.asRdp()
  val width =
    fontSize * ((maxLength * widest + (maxLength - 1) * glyphs.fonts.widestKern) * em) + padXr * 2f
  val height = fontSize * ((font.ascender - font.descender) * em) + padYr * 2f
  val pixelSize = constantPixelSize(fontSize)
  RemoteCanvas(modifier = modifier.width(width).height(height)) {
    val state = remoteComposeCreationState
    val scale = fontScale(fontSize, pixelSize, font.unitsPerEm)
    val line = RemoteGlyphLine(state, glyphs, text, maxLength, scale, tabularFigures)
    val originX = padXr.toPx()
    val baseline = padYr.toPx() + scale * font.ascender.toFloat()
    for (v in listOf(originX, baseline, this.width, this.height)) line.id(v)
    prepare(line)
    FlatLineScope(state, line, originX, baseline, this.width, this.height, scale).body()
  }
}

/** A raised-cosine window: 1 at [delta] 0, falling to 0 at ±[width]. */
@SuppressLint("RestrictedApi")
private fun window(delta: RemoteFloat, width: Float): RemoteFloat {
  val d = clamp(delta / width, -1f, 1f)
  return (cos(d * PI.toFloat()) + 1f) * 0.5f
}

/** A deterministic hash of [x] in `[-1, 1]`. */
@SuppressLint("RestrictedApi")
private fun hash(x: RemoteFloat): RemoteFloat {
  val s = sin(x * 12.9898f) * 43758.547f
  return (s - floor(s)) * 2f - 1f
}

/**
 * **Text on a baseline wave**: `VariableFontPathText`'s RemoteString counterpart. Each glyph sits
 * on a sine baseline and, with [alignToPath], turns to its slope.
 */
@SuppressLint("RestrictedApi")
@Composable
@RemoteComposable
public fun RemoteVariableFontBaselineWaveText(
  text: RemoteString,
  maxLength: Int,
  glyphs: VariableFontGlyphs,
  fontSize: RemoteDp,
  phase: RemoteFloat,
  modifier: RemoteModifier = RemoteModifier,
  amplitude: Dp = 8.dp,
  wavelength: Dp = 150.dp,
  alignToPath: Boolean = true,
  color: Color = Color.White,
) {
  FlatLineCanvas(text, maxLength, glyphs, fontSize, modifier, 4.dp, amplitude + 4.dp) {
    val amp = id(amplitude.asRdp().toPx()).let { RemoteFloat(it) }
    val k = RemoteFloat(id((2f * PI.toFloat()).rf / wavelength.asRdp().toPx()))
    val ph = RemoteFloat(id(phase))
    paint(color)
    line.layout()
    line.forEachSlot { slot ->
      val mid = slot.pen + slot.advance * 0.5f
      val arg = mid * k + ph
      val y = amp * sin(arg)
      val turn = if (alignToPath) atan2(amp * k * cos(arg), 1f) * RAD_TO_DEG else 0f.rf
      val x = id(originX + mid)
      val yy = id(baseline + y)
      val deg = id(turn)
      val back = id(slot.advance * -0.5f)
      line.drawRigid(slot) {
        writer.translate(x, yy)
        writer.rotate(deg)
        writer.translate(back, 0f)
      }
    }
  }
}

/**
 * **Wave warp**: `VariableFontWaveWarpText`'s RemoteString counterpart. Every outline point is
 * displaced by a travelling sine field, built on the player, so stems bend.
 */
@SuppressLint("RestrictedApi")
@Composable
@RemoteComposable
public fun RemoteVariableFontWaveWarpText(
  text: RemoteString,
  maxLength: Int,
  glyphs: VariableFontGlyphs,
  fontSize: RemoteDp,
  phase: RemoteFloat,
  modifier: RemoteModifier = RemoteModifier,
  amplitudeX: Dp = 1.5.dp,
  amplitudeY: Dp = 4.5.dp,
  wavelengthX: Dp = 56.dp,
  wavelengthY: Dp = 36.dp,
  color: Color = Color.White,
) {
  FlatLineCanvas(
    text,
    maxLength,
    glyphs,
    fontSize,
    modifier,
    amplitudeX + 4.dp,
    amplitudeY + 4.dp,
    prepare = { it.prepareWarp(glyphs.font.unitsPerEm * 0.12f) },
  ) {
    val ax = RemoteFloat(id(amplitudeX.asRdp().toPx()))
    val ay = RemoteFloat(id(amplitudeY.asRdp().toPx()))
    val fx = RemoteFloat(id((2f * PI.toFloat()).rf / wavelengthX.asRdp().toPx()))
    val fy = RemoteFloat(id((2f * PI.toFloat()).rf / wavelengthY.asRdp().toPx()))
    val ph = RemoteFloat(id(phase))
    paint(color)
    line.layout()
    line.forEachSlot { slot ->
      line.drawWarped(slot) { u, v ->
        val y = baseline - v
        WarpedPoint(
          originX + u + ax * sin(ph + y * fy + u * fx * 0.35f),
          y + ay * sin(ph + u * fx),
        )
      }
    }
  }
}

/** The glyphs of [glyphs]' characters at one fixed design-space [location], for blending. */
@Composable
private fun instance(glyphs: VariableFontGlyphs, location: Map<String, Float>): VariableFontGlyphs =
  rememberVariableFontGlyphs(glyphs.font, glyphs.set.joinToString(""), emptyMap(), location)

/**
 * **Range selector wave**: `VariableFontRangeWaveText`'s RemoteString counterpart. A window sweeps
 * across the positions ([center], `0` first to `1` last, wrapping), blending each glyph from
 * [baseLocation] to [peakLocation], lifting it by [peakOffsetY] and scaling it by [peakScale], with
 * the advances following.
 *
 * @param characters Every character the text may contain.
 */
@SuppressLint("RestrictedApi")
@Composable
@RemoteComposable
public fun RemoteVariableFontRangeWaveText(
  text: RemoteString,
  characters: String,
  maxLength: Int,
  font: VariableFont,
  baseLocation: Map<String, Float>,
  peakLocation: Map<String, Float>,
  center: RemoteFloat,
  fontSize: RemoteDp,
  modifier: RemoteModifier = RemoteModifier,
  width: Float = 0.38f,
  peakOffsetY: Dp = (-7).dp,
  peakScale: Float = 1.15f,
  color: Color = Color.White,
) {
  val base = rememberVariableFontGlyphs(font, characters, emptyMap(), baseLocation)
  val peak = instance(base, peakLocation)
  val lift = if (peakOffsetY.value < 0) -peakOffsetY else peakOffsetY
  FlatLineCanvas(
    text,
    maxLength,
    base,
    fontSize,
    modifier,
    6.dp,
    lift + 6.dp,
    instances = listOf(base, peak),
  ) {
    val peakWritten = peak.write(state)
    val peakAdvances = line.characterArray(peakWritten.advances.map { it * scale })
    val c = RemoteFloat(id(center))
    val dyPx = RemoteFloat(id(peakOffsetY.asRdp().toPx()))
    val weightAt = { i: RemoteFloat ->
      val n = line.length
      val norm = i / max(n - 1f, 1f)
      val delta = (norm - c + 10.5f) % 1f - 0.5f
      window(delta, width)
    }
    paint(color)
    line.layout { i, character, own ->
      own + (line.readCharacter(peakAdvances, character) - own) * weightAt(i)
    }
    line.forEachSlot { slot ->
      val w = weightAt(slot.index)
      val s = w * (peakScale - 1f) + 1f
      val x = id(originX + slot.pen + slot.advance * 0.5f)
      val y = id(baseline + dyPx * w)
      val sid = id(s)
      // Back by half the unscaled advance after the scale, so the glyph scales about its middle.
      val back = id(slot.advance * -0.5f)
      line.drawTween(slot, peakWritten, w) {
        writer.translate(x, y)
        writer.scale(sid, sid)
        writer.translate(back, 0f)
      }
    }
  }
}

/**
 * **Kinetic cascade**: `VariableFontKineticCascadeText`'s RemoteString counterpart. A staggered
 * elastic hop per position: stretched and light in flight, squashed and heavy on impact, settling
 * at rest, with the advances following.
 */
@SuppressLint("RestrictedApi")
@Composable
@RemoteComposable
public fun RemoteVariableFontKineticCascadeText(
  text: RemoteString,
  characters: String,
  maxLength: Int,
  font: VariableFont,
  progress: RemoteFloat,
  fontSize: RemoteDp,
  modifier: RemoteModifier = RemoteModifier,
  flightLocation: Map<String, Float> = mapOf("wght" to 220f, "wdth" to 65f),
  impactLocation: Map<String, Float> = mapOf("wght" to 1000f, "wdth" to 140f),
  restLocation: Map<String, Float> = mapOf("wght" to 600f, "wdth" to 100f),
  jumpHeight: Dp = 14.dp,
  stagger: Float = 0.11f,
  squashAmount: Float = 0.28f,
  color: Color = Color.White,
) {
  val rest = rememberVariableFontGlyphs(font, characters, emptyMap(), restLocation)
  val flight = instance(rest, flightLocation)
  val impact = instance(rest, impactLocation)
  FlatLineCanvas(
    text,
    maxLength,
    rest,
    fontSize,
    modifier,
    6.dp,
    jumpHeight + 8.dp,
    instances = listOf(rest, flight, impact),
  ) {
    val flightWritten = flight.write(state)
    val impactWritten = impact.write(state)
    val flightAdvances = line.characterArray(flightWritten.advances.map { it * scale })
    val impactAdvances = line.characterArray(impactWritten.advances.map { it * scale })
    val p = RemoteFloat(id(progress))
    val jump = RemoteFloat(id(jumpHeight.asRdp().toPx()))
    val pi = PI.toFloat()
    // Each position's phase in its hop: in flight below 0.45, on impact to 0.75, then at rest.
    val localAt = { i: RemoteFloat -> (p - i * stagger + 10f) % 1f }
    val inFlight = { local: RemoteFloat -> clamp((0.45f.rf - local) * 1000f, 0f, 1f) }
    val arcAt = { local: RemoteFloat -> sin(clamp(local / 0.45f, 0f, 1f) * pi) }
    val squashAt = { local: RemoteFloat ->
      val u = clamp((local - 0.45f) / 0.30f, 0f, 1f)
      sin(u * pi) * (1f.rf - u * 0.4f)
    }
    paint(color)
    line.layout { i, character, own ->
      val local = localAt(i)
      val f = inFlight(local)
      val toFlight = (line.readCharacter(flightAdvances, character) - own) * arcAt(local) * f
      val toImpact =
        (line.readCharacter(impactAdvances, character) - own) * squashAt(local) * (1f.rf - f)
      own + toFlight + toImpact
    }
    line.forEachSlot { slot ->
      val local = localAt(slot.index)
      val f = inFlight(local)
      val arc = arcAt(local) * f
      val squash = squashAt(local) * (1f.rf - f)
      val sy = (arc * (squashAmount * 0.85f) + 1f) / (squash * squashAmount + 1f)
      val sx = (squash * squashAmount + 1f) / (arc * (squashAmount * 0.85f) + 1f)
      val x = id(originX + slot.pen + slot.advance * 0.5f)
      val y = id(baseline - jump * arc)
      val sxId = id(sx)
      val syId = id(sy)
      val back = id(slot.advance * -0.5f)
      val transform = {
        writer.translate(x, y)
        writer.scale(sxId, syId)
        writer.translate(back, 0f)
      }
      // In flight the glyph blends toward its flight outline, otherwise toward its impact one; the
      // other is drawn at zero size.
      val flightOn = id(f)
      val impactOn = id(1f.rf - f)
      line.drawTween(slot, flightWritten, arc) {
        transform()
        writer.scale(flightOn, flightOn)
      }
      line.drawTween(slot, impactWritten, squash) {
        transform()
        writer.scale(impactOn, impactOn)
      }
    }
  }
}

/**
 * **Wiggly, boiling text**: `VariableFontWigglyText`'s RemoteString counterpart. At
 * [stepsPerSecond] the player picks, per position, a blend between [lowLocation] and
 * [highLocation], an offset and a turn, from a hash of the step and position.
 */
@SuppressLint("RestrictedApi")
@Composable
@RemoteComposable
public fun RemoteVariableFontWigglyText(
  text: RemoteString,
  characters: String,
  maxLength: Int,
  font: VariableFont,
  lowLocation: Map<String, Float>,
  highLocation: Map<String, Float>,
  timeSeconds: RemoteFloat,
  fontSize: RemoteDp,
  modifier: RemoteModifier = RemoteModifier,
  stepsPerSecond: Float = 10f,
  offsetJitter: Dp = 2.5.dp,
  rotationJitterDegrees: Float = 4.5f,
  color: Color = Color.White,
) {
  val low = rememberVariableFontGlyphs(font, characters, emptyMap(), lowLocation)
  val high = instance(low, highLocation)
  FlatLineCanvas(
    text,
    maxLength,
    low,
    fontSize,
    modifier,
    offsetJitter + 6.dp,
    offsetJitter + 6.dp,
    instances = listOf(low, high),
  ) {
    val highWritten = high.write(state)
    val highAdvances = line.characterArray(highWritten.advances.map { it * scale })
    val step = RemoteFloat(id(floor(timeSeconds * stepsPerSecond)))
    val jitter = RemoteFloat(id(offsetJitter.asRdp().toPx()))
    val blendAt = { i: RemoteFloat -> (hash(step * 31f + i) + 1f) * 0.5f }
    paint(color)
    line.layout { i, character, own ->
      own + (line.readCharacter(highAdvances, character) - own) * blendAt(i)
    }
    line.forEachSlot { slot ->
      val seed = step * 17f + slot.index
      val x = id(originX + slot.pen + slot.advance * 0.5f + hash(seed + 101f) * jitter * 0.6f)
      val y = id(baseline + hash(seed + 103f) * jitter)
      val deg = id(hash(seed + 107f) * rotationJitterDegrees)
      val back = id(slot.advance * -0.5f)
      line.drawTween(slot, highWritten, blendAt(slot.index)) {
        writer.translate(x, y)
        writer.rotate(deg)
        writer.translate(back, 0f)
      }
    }
  }
}

/**
 * **Trim paths, write-on**: `VariableFontTrimPathText`'s RemoteString counterpart. Each glyph's
 * outline is stroked from [startFraction] to [endFraction] of its length, and its fill fades in by
 * [fillAlpha].
 */
@SuppressLint("RestrictedApi")
@Composable
@RemoteComposable
public fun RemoteVariableFontTrimPathText(
  text: RemoteString,
  maxLength: Int,
  glyphs: VariableFontGlyphs,
  fontSize: RemoteDp,
  endFraction: RemoteFloat,
  modifier: RemoteModifier = RemoteModifier,
  startFraction: RemoteFloat = 0f.rf,
  fillAlpha: Float = 0f,
  strokeWidth: Dp = 2.dp,
  strokeColor: Color = Color.White,
  fillColor: Color = strokeColor,
) {
  FlatLineCanvas(text, maxLength, glyphs, fontSize, modifier, strokeWidth * 2, strokeWidth * 2) {
    line.layout()
    if (fillAlpha > 0f) {
      paint(fillColor, alpha = fillAlpha)
      drawLine()
    }
    val start = id(clamp(startFraction, 0f, 1f))
    val end = id(clamp(endFraction, 0f, 1f))
    // The stroke is drawn in font units, so its width is divided by the scale.
    val width = id(strokeWidth.asRdp().toPx() / scale)
    writer.rcPaint
      .setAntiAlias(true)
      .setColor(strokeColor.toArgb())
      .setStyle(PaintBundle.STYLE_STROKE)
      .setStrokeWidth(width)
      .setBlendMode(PaintBundle.BLEND_MODE_SRC_OVER)
      .commit()
    val x0 = id(originX)
    val y0 = id(baseline)
    line.forEachSlot { slot ->
      line.drawTrimmed(slot, start, end) {
        writer.translate(x0, y0)
        writer.translate(id(slot.pen), 0f)
      }
    }
  }
}

/**
 * **Echo, motion trail**: `VariableFontEchoText`'s RemoteString counterpart. [glyphsAtLag] holds
 * the foreground's glyphs first, then each echo's, every one made for the same characters with its
 * own (typically delayed) axes; each echo is drawn behind, shifted by its lag times ([echoOffsetX],
 * [echoOffsetY]), in its color, fading.
 */
@SuppressLint("RestrictedApi")
@Composable
@RemoteComposable
public fun RemoteVariableFontEchoText(
  text: RemoteString,
  maxLength: Int,
  glyphsAtLag: List<VariableFontGlyphs>,
  fontSize: RemoteDp,
  modifier: RemoteModifier = RemoteModifier,
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
) {
  require(glyphsAtLag.isNotEmpty())
  val echoes = glyphsAtLag.size - 1
  val reachX = (if (echoOffsetX.value < 0) -echoOffsetX else echoOffsetX) * echoes
  val reachY = (if (echoOffsetY.value < 0) -echoOffsetY else echoOffsetY) * echoes
  FlatLineCanvas(
    text,
    maxLength,
    glyphsAtLag[0],
    fontSize,
    modifier,
    reachX + 4.dp,
    reachY + 4.dp,
  ) {
    val dx = echoOffsetX.asRdp().toPx()
    val dy = echoOffsetY.asRdp().toPx()
    for (lag in echoes downTo 0) {
      val lagLine =
        if (lag == 0) line else RemoteGlyphLine(state, glyphsAtLag[lag], text, maxLength, scale)
      val lagScope =
        FlatLineScope(state, lagLine, originX, baseline, canvasWidth, canvasHeight, scale)
      lagLine.layout()
      if (lag == 0) {
        lagScope.paint(foregroundColor)
      } else {
        val alpha = (echoes - lag + 1).toFloat() / (echoes + 1) * 0.78f
        lagScope.paint(echoColors[(lag - 1) % echoColors.size], alpha = alpha)
      }
      lagScope.drawLine(dx * lag.toFloat(), dy * lag.toFloat())
    }
  }
}

/**
 * **Chromatic glitch**: `VariableFontGlitchText`'s RemoteString counterpart. Red, green and blue
 * passes added together, split apart and cut into horizontal slices that jump sideways in bursts
 * driven by [time].
 */
@SuppressLint("RestrictedApi")
@Composable
@RemoteComposable
public fun RemoteVariableFontGlitchText(
  text: RemoteString,
  maxLength: Int,
  glyphs: VariableFontGlyphs,
  fontSize: RemoteDp,
  time: RemoteFloat,
  modifier: RemoteModifier = RemoteModifier,
  maxRgbSplit: Dp = 4.dp,
  maxSliceShift: Dp = 8.dp,
  sliceCount: Int = 4,
) {
  FlatLineCanvas(
    text,
    maxLength,
    glyphs,
    fontSize,
    modifier,
    maxRgbSplit + maxSliceShift + 4.dp,
    6.dp,
  ) {
    val step = floor(time * 12f)
    val noise = abs(hash(step + 17f))
    val burst = RemoteFloat(id(max((noise - 0.25f) / 0.75f, 0.18f)))
    val split =
      RemoteFloat(id(maxRgbSplit.asRdp().toPx() * (burst * 0.7f + 0.3f) * hash(step + 99f)))
    val splitY = RemoteFloat(id(maxRgbSplit.asRdp().toPx() * 0.25f * burst * hash(step + 101f)))
    val shiftMax = maxSliceShift.asRdp().toPx()
    line.layout()
    val bands = sliceCount.coerceAtLeast(1)
    for (s in 0 until bands) {
      val top = id(canvasHeight * (s.toFloat() / bands))
      val bottom = id(canvasHeight * ((s + 1).toFloat() / bands))
      // A slice jumps when its own hash clears a threshold, by a hashed amount.
      val jumps = clamp((abs(hash(step + (s + 31).toFloat())) - 0.35f) * 1000f, 0f, 1f)
      val shift = shiftMax * burst * hash(step + (s + 71).toFloat()) * jumps
      writer.save()
      writer.clipRect(0f, top, id(canvasWidth), bottom)
      for ((color, sign) in
        listOf(Color(0xFFFF1744) to -1f, Color(0xFF00E676) to 0f, Color(0xFF2979FF) to 1f)) {
        paint(color, blendMode = PaintBundle.BLEND_MODE_PLUS)
        drawLine(shift + split * sign, splitY * sign)
      }
      writer.restore()
    }
  }
}

/**
 * **Specular shimmer**: `VariableFontShimmerText`'s RemoteString counterpart. A gradient fill with
 * a bright band sweeping across it as [sweepProgress] runs from 0 to 1.
 */
@SuppressLint("RestrictedApi")
@Composable
@RemoteComposable
public fun RemoteVariableFontShimmerText(
  text: RemoteString,
  maxLength: Int,
  glyphs: VariableFontGlyphs,
  fontSize: RemoteDp,
  sweepProgress: RemoteFloat,
  modifier: RemoteModifier = RemoteModifier,
  baseColors: List<Color> = listOf(Color(0xFF00B4DB), Color(0xFF8E54E9), Color(0xFFFF416C)),
  highlightColor: Color = Color.White,
  bandWidthFraction: Float = 0.28f,
) {
  FlatLineCanvas(text, maxLength, glyphs, fontSize, modifier, 4.dp, 4.dp) {
    line.layout()
    drawLineWithGradient(
      0f.rf,
      0f.rf,
      canvasWidth,
      0f.rf,
      baseColors.map { it.toArgb() }.toIntArray(),
    )
    val band = canvasWidth * bandWidthFraction.coerceIn(0.05f, 0.8f)
    val center = band * -1f + (sweepProgress % 1f) * (canvasWidth + band * 2f)
    drawLineWithGradient(
      center - band,
      0f.rf,
      center + band,
      canvasHeight * 0.4f,
      intArrayOf(
        Color.Transparent.toArgb(),
        highlightColor.copy(alpha = 0.92f).toArgb(),
        Color.Transparent.toArgb(),
      ),
      PaintBundle.BLEND_MODE_PLUS,
    )
  }
}

/**
 * **Neon glow**: `VariableFontNeonText`'s RemoteString counterpart. Concentric glow strokes added
 * together around the outline, a faint fill and a bright core tube, the glow scaled by
 * [glowIntensity].
 */
@SuppressLint("RestrictedApi")
@Composable
@RemoteComposable
public fun RemoteVariableFontNeonText(
  text: RemoteString,
  maxLength: Int,
  glyphs: VariableFontGlyphs,
  fontSize: RemoteDp,
  modifier: RemoteModifier = RemoteModifier,
  glowIntensity: Float = 1f,
  glowColor: Color = Color(0xFFFF007F),
  secondaryGlowColor: Color = Color(0xFF00E5FF),
  coreColor: Color = Color.White,
  maxGlowWidth: Dp = 11.dp,
  coreStrokeWidth: Dp = 1.5.dp,
  fillCoreAlpha: Float = 0.22f,
) {
  FlatLineCanvas(
    text,
    maxLength,
    glyphs,
    fontSize,
    modifier,
    maxGlowWidth + 4.dp,
    maxGlowWidth + 4.dp,
  ) {
    line.layout()
    val i = glowIntensity.coerceIn(0f, 1.5f)
    // Strokes are drawn in font units, so their widths are divided by the scale.
    fun units(width: Dp): Float = id(width.asRdp().toPx() / scale)
    val plus = PaintBundle.BLEND_MODE_PLUS
    val stroke = PaintBundle.STYLE_STROKE
    paint(secondaryGlowColor, 0.18f * i, stroke, units(maxGlowWidth), plus)
    drawLine()
    paint(glowColor, 0.34f * i, stroke, units(maxGlowWidth * 0.65f), plus)
    drawLine()
    paint(glowColor, 0.62f * i, stroke, units(maxGlowWidth * 0.38f), plus)
    drawLine()
    if (fillCoreAlpha > 0f) {
      paint(glowColor, fillCoreAlpha * i)
      drawLine()
    }
    paint(coreColor, 0.85f + 0.15f * i, stroke, units(coreStrokeWidth))
    drawLine()
  }
}

/**
 * **Emboss**: `VariableFontEmbossText`'s RemoteString counterpart, lit from [lightAngleDegrees]
 * (toward the light, clockwise from 3 o'clock). Without layers on the player, the relief is built
 * from offset copies: a soft cast shadow of stacked translucent copies, a lit rim and a shaded rim
 * outside the face, the face, then inside it (clipped to each glyph) bevel strokes, lit on the
 * edges toward the light and shaded on the others.
 */
@SuppressLint("RestrictedApi")
@Composable
@RemoteComposable
public fun RemoteVariableFontEmbossText(
  text: RemoteString,
  maxLength: Int,
  glyphs: VariableFontGlyphs,
  fontSize: RemoteDp,
  lightAngleDegrees: RemoteFloat,
  modifier: RemoteModifier = RemoteModifier,
  depth: Dp = 2.dp,
  elevation: Dp = 3.dp,
  color: Color = Color(0xFFC9CDD3),
  highlightColor: Color = Color.White,
  shadowColor: Color = Color.Black,
) {
  FlatLineCanvas(
    text,
    maxLength,
    glyphs,
    fontSize,
    modifier,
    elevation * 3 + depth + 4.dp,
    elevation * 3 + depth + 4.dp,
  ) {
    line.layout()
    val rad = lightAngleDegrees * (PI.toFloat() / 180f)
    val lx = RemoteFloat(id(cos(rad)))
    val ly = RemoteFloat(id(sin(rad)))
    val elev = elevation.asRdp().toPx()
    val d = depth.asRdp().toPx()
    // The cast shadow: copies stepping away from the light, each faint, so they pile up softly.
    val steps = 6
    for (k in steps downTo 1) {
      val f = k.toFloat() / steps
      paint(shadowColor, 0.09f)
      drawLine(lx * elev * (-f), ly * elev * (-f) * 1.2f)
    }
    // Rims outside the face: lit toward the light, shaded away from it.
    paint(highlightColor, 0.55f)
    drawLine(lx * d * 0.5f, ly * d * 0.5f)
    paint(shadowColor, 0.45f)
    drawLine(lx * d * (-0.5f), ly * d * (-0.5f))
    paint(color)
    drawLine()
    // Inside the face: bevel strokes of the outline, shifted so the lit one shows along the edges
    // facing the light and the shaded one along the others, clipped to the glyph.
    val x0 = id(originX)
    val y0 = id(baseline)
    val bevels =
      listOf(
        Triple(highlightColor, 0.7f, -1f),
        Triple(shadowColor, 0.45f, 1f),
        Triple(highlightColor, 0.9f, -0.35f),
      )
    for ((bevelColor, alpha, toward) in bevels) {
      // In font units: the stroke is drawn inside the glyph's own scale.
      val shiftX = id(lx * d * toward / scale)
      val shiftY = id(ly * d * toward / scale * -1f)
      writer.rcPaint
        .setAntiAlias(true)
        .setColor(bevelColor.copy(alpha = alpha).toArgb())
        .setStyle(PaintBundle.STYLE_STROKE)
        .setStrokeWidth(id(d * 1.6f / scale))
        .setBlendMode(PaintBundle.BLEND_MODE_SRC_OVER)
        .commit()
      line.forEachSlot { slot ->
        line.drawClippedToGlyph(slot, shiftX, shiftY) {
          writer.translate(x0, y0)
          writer.translate(id(slot.pen), 0f)
        }
      }
    }
  }
}

/**
 * **Text warped along a path**: `VariableFontPathWarpText`'s RemoteString counterpart. A point `u`
 * along the line and `v` above the baseline goes to the point `u` along [path] (plus [offset] of
 * its length), moved `v` along its normal, built on the player for every outline point, so glyphs
 * bend with the path. [path] is sampled into position and tangent tables in the document when it is
 * made; text past an open path's ends stays at its end, and a closed path wraps.
 *
 * @param path The baseline, in dp. Its bounds, grown by the text's extent, set the box, and it is
 *   drawn where its bounds fall in that box.
 * @param offset Where the start of the text sits, as a fraction of the path's length; it may change
 *   on the player, to run the text along the path.
 */
@SuppressLint("RestrictedApi")
@Composable
@RemoteComposable
public fun RemoteVariableFontPathWarpText(
  text: RemoteString,
  maxLength: Int,
  glyphs: VariableFontGlyphs,
  fontSize: RemoteDp,
  path: androidx.compose.ui.graphics.Path,
  modifier: RemoteModifier = RemoteModifier,
  offset: RemoteFloat = 0f.rf,
  color: Color = Color.White,
) {
  val font = glyphs.font
  val em = 1f / font.unitsPerEm
  val samples = androidx.compose.runtime.remember(path) { PathSamples(path) }
  val pixelSize = constantPixelSize(fontSize)
  val extentEm = kotlin.math.max(font.ascender, -font.descender) * em
  val pad = fontSize * (extentEm + 0.1f)
  val bounds = samples.bounds
  val width = pad * 2f + RemoteDp(bounds.width.rf)
  val height = pad * 2f + RemoteDp(bounds.height.rf)
  RemoteCanvas(modifier = modifier.width(width).height(height)) {
    val state = remoteComposeCreationState
    val writer = state.document
    val scale = fontScale(fontSize, pixelSize, font.unitsPerEm)
    val line = RemoteGlyphLine(state, glyphs, text, maxLength, scale)
    line.prepareWarp(font.unitsPerEm * 0.1f)
    val padPx = pad.toPx()
    // The path is in dp: its tables are scaled to pixels on the player.
    val density = RemoteFloat(line.id(RemoteDp(1f.rf).toPx()))
    val ox = RemoteFloat(line.id(padPx - density * bounds.left))
    val oy = RemoteFloat(line.id(padPx - density * bounds.top))
    val lengthPx = RemoteFloat(line.id(density * samples.length))
    val start = RemoteFloat(line.id(offset * lengthPx))
    val xs = writer.addFloatArray(samples.xs)
    val ys = writer.addFloatArray(samples.ys)
    val txs = writer.addFloatArray(samples.txs)
    val tys = writer.addFloatArray(samples.tys)

    writer.rcPaint
      .setAntiAlias(true)
      .setColor(color.toArgb())
      .setStyle(PaintBundle.STYLE_FILL)
      .commit()
    line.layout()
    line.forEachSlot { slot ->
      line.drawWarped(slot) { u, v ->
        val raw = u + start
        val d = if (samples.closed) (raw % lengthPx + lengthPx) % lengthPx else raw
        // A_LERP reads a table at a fraction of its length, interpolating and clamping at the ends.
        val f = line.id(d / lengthPx)
        fun at(table: Float) = RemoteFloat(writer.floatExpression(table, f, AFE.A_LERP))
        val px = at(xs) * density
        val py = at(ys) * density
        val tx = at(txs)
        val ty = at(tys)
        // Up from the baseline is the left-hand normal of the direction of travel (y grows down).
        WarpedPoint(ox + px + ty * v, oy + py - tx * v)
      }
    }
  }
}

/** [path]'s first contour sampled every [SAMPLE_STEP] dp: positions and unit tangents. */
internal class PathSamples(path: androidx.compose.ui.graphics.Path) {
  val bounds = path.getBounds()
  val length: Float
  val count: Int
  val step: Float
  val xs: FloatArray
  val ys: FloatArray
  val txs: FloatArray
  val tys: FloatArray
  val closed: Boolean

  init {
    val measure = androidx.compose.ui.graphics.PathMeasure()
    measure.setPath(path, false)
    length = measure.length
    count = kotlin.math.max(2, kotlin.math.ceil(length / SAMPLE_STEP).toInt() + 1)
    step = if (length > 0f) length / (count - 1) else 1f
    xs = FloatArray(count)
    ys = FloatArray(count)
    txs = FloatArray(count)
    tys = FloatArray(count)
    for (i in 0 until count) {
      val d = kotlin.math.min(length, i * step)
      val p = measure.getPosition(d)
      val t = measure.getTangent(d)
      val l = kotlin.math.hypot(t.x, t.y)
      xs[i] = p.x
      ys[i] = p.y
      txs[i] = if (l > 0f) t.x / l else 1f
      tys[i] = if (l > 0f) t.y / l else 0f
    }
    closed = length > 0f && kotlin.math.hypot(xs[0] - xs[count - 1], ys[0] - ys[count - 1]) < 0.5f
  }

  private companion object {
    const val SAMPLE_STEP = 3f
  }
}
