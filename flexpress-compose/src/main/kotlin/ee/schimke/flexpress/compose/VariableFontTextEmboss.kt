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

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import ee.schimke.flexpress.VariableFont
import ee.schimke.flexpress.VariableTextOutlineEvaluator
import ee.schimke.flexpress.cachedOutline
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Whether [VariableFontEmbossText] raises the letters off the surface or presses them into it. */
public enum class EmbossStyle {
  /** Letters stand proud of the surface: a bevelled face, lit edges toward the light, a shadow. */
  Raised,
  /** Letters are stamped into the surface: shadowed walls toward the light, a lit far lip. */
  Pressed,
}

/**
 * **Emboss** — variable-font text lit as a physical relief: a bevelled face catching a directional
 * light, a crisp specular rim, and a soft cast and contact shadow (or, [EmbossStyle.Pressed], a
 * recess with a shadowed wall and a lit lip).
 *
 * Built only from fills of the evaluated outline, no shader: each lit or shaded bevel is the glyph
 * shape minus copies of itself shifted along the light direction (`DST_OUT` in a layer), with a
 * falloff shaped like a rounded bevel, and the shadows are blurred, offset fills. Animate
 * [lightAngleDegrees] to swing the light around the letters, and the axes to watch the bevel follow
 * the weight.
 *
 * The bevels are stepped erasures, which need no filter. The shadows are blurred with
 * `Paint.setShadowLayer`, which hardware-accelerated canvases draw from API 28 (mask filters they
 * never draw); below that, on a hardware canvas, the shadows are drawn unblurred.
 *
 * @param lightAngleDegrees The direction toward the light, in degrees clockwise from 3 o'clock
 *   (`-135f` is the top left). Read while drawing.
 * @param depth The width of the bevel.
 * @param elevation How far the letters stand off the surface, which sets the shadow's offset and
 *   softness. Unused by [EmbossStyle.Pressed].
 * @param color The material of the face, used unless [brush] is given.
 * @param lightIntensity Scales the highlights and shadows, `0f` (flat) to about `1.5f`. Read while
 *   drawing.
 */
@Composable
public fun VariableFontEmbossText(
  text: String,
  font: VariableFont,
  axes: Map<String, () -> Float>,
  fontSize: TextUnit,
  modifier: Modifier = Modifier,
  lightAngleDegrees: () -> Float = { -135f },
  style: EmbossStyle = EmbossStyle.Raised,
  depth: Dp = 2.dp,
  elevation: Dp = 3.dp,
  color: Color = Color(0xFFC9CDD3),
  brush: Brush? = null,
  highlightColor: Color = Color.White,
  shadowColor: Color = Color.Black,
  lightIntensity: () -> Float = { 1f },
  location: Map<String, Float> = emptyMap(),
  kerningLocation: Map<String, Float> = location,
) {
  require(fontSize.isSpecified && fontSize.isSp) { "fontSize must be in sp, was $fontSize" }
  val tags = axes.keys.toList()
  val density = LocalDensity.current
  val pixelSize = with(density) { fontSize.toPx() }
  val depthPx = with(density) { depth.toPx() }.coerceAtLeast(0.5f)
  val elevationPx =
    if (style == EmbossStyle.Raised) with(density) { elevation.toPx() }.coerceAtLeast(0f) else 0f
  val outline =
    remember(text, font, tags, location, kerningLocation, pixelSize) {
      font.cachedOutline(text, tags, location, kerningLocation, pixelSize = pixelSize)
    }
  val renderer =
    remember(outline) { VariableFontTextRenderer(VariableTextOutlineEvaluator(outline)) }
  val sources = remember(outline, axes) { outline.axes.map { axes.getValue(it) } }
  val values = remember(outline) { FloatArray(outline.axes.size) }
  val painter = remember(depthPx, elevationPx) { EmbossPainter(depthPx, elevationPx) }
  val pad = ceil(elevationPx * 2.5f + depthPx * 2f + 4f).toInt()
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
    val rad = lightAngleDegrees() * (PI.toFloat() / 180f)
    val lx = cos(rad)
    val ly = sin(rad)
    val intensity = lightIntensity().coerceIn(0f, 2f)
    val path = renderer.path.asAndroidPath()
    val w = size.width
    val h = size.height
    withTransform({ translate(ox.toFloat(), oy.toFloat()) }) {
      drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        if (style == EmbossStyle.Raised) {
          painter.drawCastShadows(nc, path, lx, ly, shadowColor, intensity)
        } else {
          painter.drawLip(nc, path, lx, ly, w, h, highlightColor, intensity)
        }
      }
      // The face.
      if (brush != null) drawPath(renderer.path, brush) else drawPath(renderer.path, color)
      // A broad falloff across the face, lighter toward the light.
      val half = hypot(w, h) * 0.5f
      val cx = w * 0.5f - ox
      val cy = h * 0.5f - oy
      drawPath(
        renderer.path,
        painter.faceShading(
          if (style == EmbossStyle.Raised) highlightColor else shadowColor,
          shadowColor,
          Offset(cx + lx * half, cy + ly * half),
          Offset(cx - lx * half, cy - ly * half),
          intensity,
          style,
        ),
      )
      drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        if (style == EmbossStyle.Raised) {
          painter.drawRaisedBevel(nc, path, lx, ly, w, h, highlightColor, shadowColor, intensity)
        } else {
          painter.drawPressedBevel(nc, path, lx, ly, w, h, highlightColor, shadowColor, intensity)
        }
      }
    }
  }
}

/**
 * The paints, shadows and falloff of one [VariableFontEmbossText], reused across frames. Every pass
 * is a fill of the glyph path, so the relief follows the live outline exactly.
 */
internal class EmbossPainter(private val depthPx: Float, private val elevationPx: Float) {
  private val fill = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)
  private val erase =
    AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
      xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
  private val shadow = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)
  private val ambientRadius = elevationPx * 1.1f + 0.5f
  private val contactRadius = elevationPx * 0.35f + 0.35f

  // The bevel's falloff: after k of the BEVEL_STEPS shifted erasures, (1 - k/n)^γ of the light is
  // left, so the band is brightest at the very edge and rolls off like a rounded chamfer.
  private val bevelAlphas =
    FloatArray(BEVEL_STEPS) { i ->
      val before = (1f - i.toFloat() / BEVEL_STEPS).pow(BEVEL_GAMMA)
      val after = (1f - (i + 1).toFloat() / BEVEL_STEPS).pow(BEVEL_GAMMA)
      1f - after / before
    }

  private var shadingBrush: Brush? = null
  private var shadingStart = Offset.Zero
  private var shadingEnd = Offset.Zero
  private var shadingIntensity = 0f
  private var shadingStyle = EmbossStyle.Raised
  private var shadingNear = Color.Unspecified
  private var shadingFar = Color.Unspecified

  fun drawCastShadows(
    nc: AndroidCanvas,
    path: AndroidPath,
    lx: Float,
    ly: Float,
    shadowColor: Color,
    intensity: Float,
  ) {
    if (elevationPx <= 0f) return
    val ambient = shadowColor.scaledAlpha(0.32f * intensity)
    val contact = shadowColor.scaledAlpha(0.5f * intensity)
    castShadow(nc, path, -lx * elevationPx, -ly * elevationPx * 1.2f, ambientRadius, ambient)
    castShadow(
      nc,
      path,
      -lx * elevationPx * 0.3f,
      -ly * elevationPx * 0.35f,
      contactRadius,
      contact,
    )
  }

  /**
   * A blurred copy of [path] at ([dx], [dy]) in [color]. The shape itself is drawn far off the
   * canvas and only its shadow layer, offset back by as much, lands here: shadow layers are the
   * blur hardware canvases draw (from API 28), and mask filters are not.
   */
  private fun castShadow(
    nc: AndroidCanvas,
    path: AndroidPath,
    dx: Float,
    dy: Float,
    radius: Float,
    color: Color,
  ) {
    if (color.alpha <= 0f) return
    if (Build.VERSION.SDK_INT < 28 && nc.isHardwareAccelerated) {
      fill.color = color.toArgb()
      drawShifted(nc, path, dx, dy, fill)
      return
    }
    shadow.color = android.graphics.Color.BLACK
    // A translucent shadow color sets the shadow's own alpha, whatever the paint's.
    shadow.setShadowLayer(
      radius,
      OFFSTAGE + dx,
      dy,
      color.copy(alpha = min(color.alpha, 0.996f)).toArgb(),
    )
    drawShifted(nc, path, -OFFSTAGE, 0f, shadow)
  }

  /** The lit lip of the surface just outside the far edge of a pressed letter. */
  fun drawLip(
    nc: AndroidCanvas,
    path: AndroidPath,
    lx: Float,
    ly: Float,
    w: Float,
    h: Float,
    highlightColor: Color,
    intensity: Float,
  ) {
    val rim = max(0.75f, depthPx * 0.45f)
    val layer = nc.saveLayer(-w, -h, w * 2f, h * 2f, null)
    fill.color = highlightColor.scaledAlpha(0.7f * intensity).toArgb()
    drawShifted(nc, path, -lx * rim, -ly * rim, fill)
    erase.alpha = 255
    nc.drawPath(path, erase)
    nc.restoreToCount(layer)
  }

  fun drawRaisedBevel(
    nc: AndroidCanvas,
    path: AndroidPath,
    lx: Float,
    ly: Float,
    w: Float,
    h: Float,
    highlightColor: Color,
    shadowColor: Color,
    intensity: Float,
  ) {
    // Faces turned toward the light: the shape minus itself pushed away from the light.
    band(
      nc,
      path,
      -lx,
      -ly,
      depthPx,
      w,
      h,
      highlightColor,
      0.8f * intensity,
      stepped = true,
    )
    // Faces turned away.
    band(nc, path, lx, ly, depthPx, w, h, shadowColor, 0.55f * intensity, stepped = true)
    // The specular glint where the face meets the lit bevel, and a dark line on the far edge.
    val rim = max(0.6f, depthPx * 0.2f)
    band(nc, path, -lx, -ly, rim, w, h, highlightColor, 0.95f * intensity, stepped = false)
    band(nc, path, lx, ly, rim, w, h, shadowColor, 0.45f * intensity, stepped = false)
  }

  fun drawPressedBevel(
    nc: AndroidCanvas,
    path: AndroidPath,
    lx: Float,
    ly: Float,
    w: Float,
    h: Float,
    highlightColor: Color,
    shadowColor: Color,
    intensity: Float,
  ) {
    // The wall under the near lip is in shadow, and the lip casts a soft shadow onto the floor.
    band(
      nc,
      path,
      -lx,
      -ly,
      depthPx * 1.6f,
      w,
      h,
      shadowColor,
      0.6f * intensity,
      true,
    )
    band(nc, path, -lx, -ly, depthPx, w, h, shadowColor, 0.55f * intensity, true)
    // The far wall faces the light.
    band(nc, path, lx, ly, depthPx, w, h, highlightColor, 0.55f * intensity, true)
    val rim = max(0.6f, depthPx * 0.2f)
    band(nc, path, -lx, -ly, rim, w, h, shadowColor, 0.7f * intensity, stepped = false)
  }

  fun faceShading(
    near: Color,
    far: Color,
    start: Offset,
    end: Offset,
    intensity: Float,
    style: EmbossStyle,
  ): Brush {
    val cached = shadingBrush
    if (
      cached != null &&
        start == shadingStart &&
        end == shadingEnd &&
        intensity == shadingIntensity &&
        style == shadingStyle &&
        near == shadingNear &&
        far == shadingFar
    ) {
      return cached
    }
    val nearAlpha = if (style == EmbossStyle.Raised) 0.16f else 0.22f
    val farAlpha = if (style == EmbossStyle.Raised) 0.2f else 0.04f
    val brush =
      Brush.linearGradient(
        0f to near.scaledAlpha(nearAlpha * intensity),
        0.5f to Color.Transparent,
        1f to far.scaledAlpha(farAlpha * intensity),
        start = start,
        end = end,
      )
    shadingBrush = brush
    shadingStart = start
    shadingEnd = end
    shadingIntensity = intensity
    shadingStyle = style
    shadingNear = near
    shadingFar = far
    return brush
  }

  /**
   * Fills the band of the glyph within [width] of its edges facing away from ([dx], [dy]): the
   * glyph in [color], with copies shifted by up to [width] along ([dx], [dy]) erased from it.
   * [stepped] erases [BEVEL_STEPS] copies along the bevel falloff instead of one.
   */
  private fun band(
    nc: AndroidCanvas,
    path: AndroidPath,
    dx: Float,
    dy: Float,
    width: Float,
    w: Float,
    h: Float,
    color: Color,
    alpha: Float,
    stepped: Boolean,
  ) {
    if (alpha <= 0f) return
    val layer = nc.saveLayer(-w, -h, w * 2f, h * 2f, null)
    fill.color = color.scaledAlpha(alpha).toArgb()
    nc.drawPath(path, fill)
    if (stepped) {
      for (k in 0 until BEVEL_STEPS) {
        val d = width * (k + 1) / BEVEL_STEPS
        erase.alpha = (bevelAlphas[k] * 255f).toInt().coerceIn(0, 255)
        drawShifted(nc, path, dx * d, dy * d, erase)
      }
    } else {
      erase.alpha = 255
      drawShifted(nc, path, dx * width, dy * width, erase)
    }
    nc.restoreToCount(layer)
  }

  private fun drawShifted(
    nc: AndroidCanvas,
    path: AndroidPath,
    dx: Float,
    dy: Float,
    paint: AndroidPaint,
  ) {
    nc.save()
    nc.translate(dx, dy)
    nc.drawPath(path, paint)
    nc.restore()
  }

  private companion object {
    const val BEVEL_STEPS = 8

    /** How far off the canvas a shadow's own shape is drawn. */
    const val OFFSTAGE = 32768f
    const val BEVEL_GAMMA = 1.6f
  }
}

/**
 * This color with its own alpha scaled by [factor], so a translucent or transparent color stays so.
 */
private fun Color.scaledAlpha(factor: Float): Color =
  copy(alpha = (alpha * factor).coerceIn(0f, 1f))
