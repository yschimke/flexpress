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

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.layout.layout
import kotlin.math.PI

/**
 * Wear Compose Material 3's `Modifier.curveToEdge` (androidx.wear.compose:compose-material3
 * 1.8.0-alpha03, `CurveToEdge.kt`) for a clockwise, centered, 12 o'clock anchor: the content is
 * laid out flat and curved to the edge of the screen by its AGSL shader, which is copied here
 * unchanged. It is a debug-only copy for the comparison previews, so they need neither that alpha
 * nor the compileSdk it requires.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun Modifier.wearCurveToEdge(maxSweepAngle: Float, screenHeightPx: Int): Modifier {
  val shader = RuntimeShader(CURVED_TEXT)
  return layout { measurable, constraints ->
    val maxHeight = minOf(constraints.maxHeight, screenHeightPx)
    val halfMaxSweepAngleRad = maxSweepAngle * PI / 360
    val arc = maxHeight.toFloat() * halfMaxSweepAngleRad
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = arc.toInt()))
    val radius = maxHeight.toFloat() / 2f
    // AngularAnchorType.Center has ratio 1; the anchor is 270 degrees (the top).
    val circleStart = (placeable.width.toFloat() / radius) / 2f
    val anchorRadians = 270.0 * PI / 180.0
    shader.setFloatUniform("centre", radius, radius)
    shader.setFloatUniform("lowerSweepBound", circleStart - halfMaxSweepAngleRad.toFloat())
    shader.setFloatUniform("upperSweepBound", circleStart + halfMaxSweepAngleRad.toFloat())
    shader.setFloatUniform("offset", (circleStart + 2f * PI - anchorRadians).toFloat())
    shader.setFloatUniform("contentHeight", placeable.height.toFloat())
    shader.setIntUniform("angularDirection", 0)
    layout(minOf(constraints.maxWidth, maxHeight), placeable.height) {
      placeable.placeWithLayer(0, 0) {
        renderEffect =
          RenderEffect.createRuntimeShaderEffect(shader, "contents").asComposeRenderEffect()
      }
    }
  }
}

// Verbatim from Wear Compose Material 3's CurveToEdge.kt.
private val CURVED_TEXT =
  """
  uniform float2 centre;
  uniform float contentHeight;
  uniform float lowerSweepBound;
  uniform float upperSweepBound;
  uniform float offset;
  uniform int angularDirection;
  uniform shader contents;

  // Look up contents using a log-polar coordinate system to make it circular
  half4 main(float2 fragCoord) {
      const float PI = 3.14159265359;
      const float TWOPI = PI * 2;
      float radius = centre.x;
      float dist = distance(centre, fragCoord);
      if (dist > radius || dist < radius - contentHeight) return half4(0);
      float2 cv = fragCoord - centre;
      // ang is the angle between the x axis line and the point
      float ang = atan(cv.y, cv.x);
      if (angularDirection != 0) ang = -ang - PI;
      // We have to mod x by 2*PI to deal with clipping that happens otherwise
      float x = mod(ang + offset.x, TWOPI);
      // Only render text if bounds are between the maximum sweep angle
      if (x <= lowerSweepBound || x >= upperSweepBound) return half4(0);
      float y = abs(log(dist / radius));
      // Multiply the vector by the radius to get the final coordinate
      float2 final = float2(x, y) * float2(radius);
      if (angularDirection != 0) final.y = contentHeight - final.y;
      return contents.eval(final);
  }
  """
    .trimIndent()
