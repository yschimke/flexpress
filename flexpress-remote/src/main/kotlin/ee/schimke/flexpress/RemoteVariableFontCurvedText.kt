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
import androidx.compose.remote.creation.compose.layout.RemoteCanvas
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.width
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteDp
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.animateRemoteFloatAsState
import androidx.compose.remote.creation.compose.state.clamp
import androidx.compose.remote.creation.compose.state.cos
import androidx.compose.remote.creation.compose.state.max
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.remoteTween
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.sin
import androidx.compose.remote.creation.compose.state.toRad
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import kotlin.math.max as maxOf

/**
 * Draws a [RemoteString] [text], which may change on the player, bent around a circle: the Remote
 * Compose counterpart of a Wear `curvedText` or `TimeText` whose time the player keeps.
 *
 * As `VariableFontCurvedText` in Compose UI, the outline itself is warped, with no shader: a point
 * `u` along the line and `v` above the baseline goes to the angle `anchor + u / radius` at
 * `radius + v` from the center, so each glyph bends with the circle. The player builds each glyph's
 * warped outline from its points, inside one loop over the text's positions, so the document grows
 * with the [glyphs]' characters and not with [maxLength].
 *
 * The box is a square around the circle and its glyphs, with the circle's center at its center.
 *
 * @param text The text to draw; it may change on the player.
 * @param maxLength The most characters [text] may have.
 * @param glyphs The glyphs to draw with, from [rememberVariableFontGlyphs].
 * @param radius The radius of the baseline.
 * @param anchorAngleDegrees Where the anchor point of the text sits, in degrees clockwise from 3
 *   o'clock (`-90` is 12 o'clock). It may change on the player, to spin the text.
 * @param textAnchor Which point of the line sits at the anchor, as a fraction of its width.
 * @param clockwise Reads clockwise with the glyphs outside the circle (upright at the top), or
 *   counter-clockwise with them inside (upright at the bottom).
 * @param tabularFigures Each digit takes the widest digit's advance at the current axis values, so
 *   a clock's seconds tick without the line moving.
 * @param growLeadingGlyph When the text gains a character, its first glyph grows in from nothing
 *   and the line re-centers as it widens, as a clock's hours do at 10:00.
 * @param color The fill color.
 */
@SuppressLint("RestrictedApi")
@Composable
@RemoteComposable
public fun RemoteVariableFontCurvedText(
  text: RemoteString,
  maxLength: Int,
  glyphs: VariableFontGlyphs,
  fontSize: RemoteDp,
  radius: RemoteDp,
  modifier: RemoteModifier = RemoteModifier,
  anchorAngleDegrees: RemoteFloat = (-90f).rf,
  textAnchor: Float = 0.5f,
  clockwise: Boolean = true,
  tabularFigures: Boolean = false,
  growLeadingGlyph: Boolean = false,
  color: RemoteColor = Color.Black.rc,
) {
  val font = glyphs.font
  val em = 1f / font.unitsPerEm
  val extentEm = maxOf(font.ascender, -font.descender) * em
  val side = radius * 2f + fontSize * (2f * extentEm + 0.1f)
  val pixelSize = constantPixelSize(fontSize)
  val radiusPixels = constantPixelSize(radius)

  RemoteCanvas(modifier = modifier.width(side).height(side)) {
    val state = remoteComposeCreationState
    val writer = state.document
    val paint = writer.rcPaint.setAntiAlias(true)
    val constant = color.constantValueOrNull
    if (constant != null) paint.setColor(constant)
    else paint.setColorId(color.getIdForCreationState(state))
    paint.commit()

    val scale = fontScale(fontSize, pixelSize, font.unitsPerEm)
    // Written before the loops: the animation must not restart at every position.
    val grown =
      if (growLeadingGlyph) {
        animateRemoteFloatAsState(text.length.toRemoteFloat(), remoteTween(300)).also {
          it.getFloatIdForCreationState(state)
        }
      } else {
        null
      }
    val line =
      RemoteGlyphLine(
        state,
        glyphs,
        text,
        maxLength,
        scale,
        tabularFigures,
        glyphScale =
          grown?.let { animated ->
            { i, n ->
              // The first glyph only, from 0 to 1 as the animated length reaches the new one.
              val growth = clamp(animated - n + 1f, 0f, 1f)
              1f.rf - (1f.rf - growth) * max(1f.rf - i, 0f)
            }
          },
      )
    // About an eighth of a radian of arc per piece, in font units.
    val maxSegmentUnits =
      if (radiusPixels != null && pixelSize != null) {
        0.12f * radiusPixels / (pixelSize * em)
      } else {
        font.unitsPerEm * 0.15f
      }
    line.prepareWarp(maxSegmentUnits)
    val r = radius.toPx()
    val cx = width * 0.5f
    val cy = height * 0.5f
    val anchor = toRad(anchorAngleDegrees)
    for (v in listOf(r, cx, cy, anchor)) line.id(v)
    line.layout()
    val shift = line.total * textAnchor
    line.id(shift)
    line.forEachSlot { slot ->
      line.drawWarped(slot) { u, v ->
        val along = (u - shift) / r
        val angle = if (clockwise) anchor + along else anchor - along
        val distance = if (clockwise) r + v else r - v
        WarpedPoint(cx + distance * cos(angle), cy + distance * sin(angle))
      }
    }
  }
}
