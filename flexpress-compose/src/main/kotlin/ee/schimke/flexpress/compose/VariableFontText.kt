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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.isSpecified
import ee.schimke.flexpress.VariableFont
import ee.schimke.flexpress.VariableTextOutline
import ee.schimke.flexpress.VariableTextOutlineEvaluator
import ee.schimke.flexpress.outline
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Draws one line of [text] in a variable [font] with any of its axes animated, without ever
 * instancing a typeface, laying the text out again or recomposing per frame.
 *
 * Compose text with an animated `FontVariation.Settings` loads a new typeface for every distinct
 * value, caches it, and lays the text out again. Instead, this evaluates the font's own variation
 * model: every outline coordinate is the font's default plus each variation region's delta weighted
 * by that region's scalar. The model is worked out once per text, font, animated axes, [location]
 * and size (as a [VariableTextOutline], simplified only where that cannot move an edge by more than
 * a sixteenth of a pixel); each frame only evaluates it at the current axis values into a reused
 * array and rebuilds one reused [Path] from it, with no allocation. The outline is the font's own
 * at every value.
 *
 * The [axes] are read only while drawing, so a change to the state they read invalidates the draw
 * and nothing else: no recomposition and no relayout. Pass a lambda that reads the state:
 * ```
 * val weight by rememberInfiniteTransition().animateFloat(100f, 900f, infiniteRepeatable(tween(2000)))
 * VariableFontText("Hello", robotoFlex, axes = mapOf("wght" to { weight }), fontSize = 32.sp)
 * ```
 *
 * The size is fixed: as wide as the line's widest advance anywhere the animated axes can move, and
 * as tall as the font's ascender to descender, so animating never reflows the layout. The text is
 * drawn from the left edge with its baseline at the ascender, which is reported as the
 * [FirstBaseline] and [LastBaseline] alignment lines. Glyphs that reach outside that box, such as
 * slanted overhangs, are not clipped.
 *
 * Glyphs are placed by their advances and the font's `GPOS` pair kerning at [kerningLocation];
 * there are no ligatures or complex-script shaping and no font fallback for characters [font]
 * lacks. The text is exposed to accessibility services as [text].
 *
 * @param text The single line of text to draw.
 * @param font The variable font to take outlines from. CFF2 fonts are not supported.
 * @param axes The animated axes, by tag, each with a function giving its current value in the axis'
 *   user units (clamped to its range). The functions are called while drawing; read animated state
 *   in them, not before. Changing the set of tags rebuilds the outline.
 * @param fontSize The font size, in sp.
 * @param modifier The modifier for the text's box.
 * @param color The fill color; [Color.Unspecified] draws black.
 * @param location Values for the axes not in [axes], held fixed; missing axes take their defaults.
 * @param kerningLocation Where in the design space the font's pair kerning is taken. Kerning is a
 *   constant per pair: it does not follow the animated axes.
 */
@Composable
public fun VariableFontText(
  text: String,
  font: VariableFont,
  axes: Map<String, () -> Float>,
  fontSize: TextUnit,
  modifier: Modifier = Modifier,
  color: Color = Color.Unspecified,
  location: Map<String, Float> = emptyMap(),
  kerningLocation: Map<String, Float> = location,
) {
  requireSp(fontSize)
  val tags = axes.keys.toList()
  val pixelSize = with(LocalDensity.current) { fontSize.toPx() }
  val outline =
    remember(text, font, tags, location, kerningLocation, pixelSize) {
      font.outline(text, tags, location, kerningLocation, pixelSize = pixelSize)
    }
  OutlineText(outline, axes, fontSize, modifier, color, semantics = text, isText = true)
}

/**
 * Draws a [VariableTextOutline] (from [VariableFont.outline], or decoded from a string generated
 * ahead of time, with no font at run time) with its axes animated, as [VariableFontText] does.
 *
 * @param outline The outline to draw.
 * @param axes Each of [outline]'s axes, by tag, with a function giving its current value in user
 *   units, called while drawing.
 * @param fontSize The font size, in sp.
 * @param modifier The modifier for the text's box.
 * @param color The fill color; [Color.Unspecified] draws black.
 * @param contentDescription What accessibility services read for the drawing, usually its text.
 */
@Composable
public fun VariableFontText(
  outline: VariableTextOutline,
  axes: Map<String, () -> Float>,
  fontSize: TextUnit,
  modifier: Modifier = Modifier,
  color: Color = Color.Unspecified,
  contentDescription: String? = null,
) {
  requireSp(fontSize)
  OutlineText(outline, axes, fontSize, modifier, color, contentDescription, isText = false)
}

private fun requireSp(fontSize: TextUnit) {
  require(fontSize.isSpecified && fontSize.isSp) { "fontSize must be in sp, was $fontSize" }
}

@Composable
private fun OutlineText(
  outline: VariableTextOutline,
  axes: Map<String, () -> Float>,
  fontSize: TextUnit,
  modifier: Modifier,
  color: Color,
  semantics: String?,
  isText: Boolean,
) {
  val renderer =
    remember(outline) { VariableFontTextRenderer(VariableTextOutlineEvaluator(outline)) }
  val evaluator = renderer.evaluator
  val sources =
    evaluator.axes.map { tag ->
      requireNotNull(axes[tag]) {
        "no value for axis '$tag'; the outline animates ${evaluator.axes}"
      }
    }
  val fill = if (color.isSpecified) color else Color.Black
  val measurePolicy =
    remember(evaluator, fontSize) {
      MeasurePolicy { _, constraints ->
        val scale = fontSize.toPx() / evaluator.unitsPerEm
        val width = ceil(evaluator.width * scale).toInt()
        val height = ceil((evaluator.ascender - evaluator.descender) * scale).toInt()
        val baseline = (evaluator.ascender * scale).roundToInt()
        layout(
          constraints.constrainWidth(width),
          constraints.constrainHeight(height),
          mapOf(FirstBaseline to baseline, LastBaseline to baseline),
        ) {}
      }
    }
  val semanticsModifier =
    when {
      semantics == null -> Modifier
      isText -> Modifier.semantics { text = AnnotatedString(semantics) }
      else -> Modifier.semantics { contentDescription = semantics }
    }
  Layout(
    modifier =
      modifier.then(semanticsModifier).drawBehind {
        renderer.draw(this, fontSize.toPx() / evaluator.unitsPerEm, sources, fill)
      },
    measurePolicy = measurePolicy,
  )
}

/**
 * Evaluates a [VariableTextOutlineEvaluator] into one reused [Path], only when the axis values or
 * the scale change. Drawing allocates nothing.
 */
internal class VariableFontTextRenderer(val evaluator: VariableTextOutlineEvaluator) {
  private val values = FloatArray(evaluator.axes.size)
  private val lastValues = FloatArray(evaluator.axes.size)
  private val coordinates = FloatArray(evaluator.coordinateCount)
  private var lastScale = Float.NaN

  /** The outline at the last values drawn, in pixels, with the baseline at the ascender. */
  val path: Path = Path()

  /** How many times [path] has been rebuilt. */
  var rebuilds: Int = 0
    private set

  fun draw(scope: DrawScope, scale: Float, sources: List<() -> Float>, color: Color) {
    for (i in values.indices) values[i] = sources[i]()
    update(values, scale)
    scope.drawPath(path, color)
  }

  /** Brings [path] up to date with [axisValues] (one per axis) at [scale] pixels per font unit. */
  fun update(axisValues: FloatArray, scale: Float) {
    if (scale == lastScale && rebuilds > 0 && axisValues.contentEquals(lastValues)) return
    axisValues.copyInto(lastValues)
    lastScale = scale
    evaluator.evaluate(axisValues, coordinates)
    val ascender = evaluator.ascender.toFloat()
    val c = coordinates
    var p = 0
    path.rewind()
    for (verb in evaluator.verbs) {
      when (verb) {
        VariableTextOutlineEvaluator.VERB_MOVE -> {
          path.moveTo(c[p] * scale, (ascender - c[p + 1]) * scale)
          p += 2
        }
        VariableTextOutlineEvaluator.VERB_LINE -> {
          path.lineTo(c[p] * scale, (ascender - c[p + 1]) * scale)
          p += 2
        }
        VariableTextOutlineEvaluator.VERB_QUAD -> {
          path.quadraticTo(
            c[p] * scale,
            (ascender - c[p + 1]) * scale,
            c[p + 2] * scale,
            (ascender - c[p + 3]) * scale,
          )
          p += 4
        }
        else -> path.close()
      }
    }
    rebuilds++
  }
}
