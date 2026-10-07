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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import ee.schimke.flexpress.VariableTextOutlineEvaluator
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compares [VariableFontText] with the platform's own text in the same font and variation settings
 * (`BasicText` with `FontVariation.Settings`, unhinted and with subpixel positioning, as
 * `TextMotion.Animated` draws it).
 * - Ink, glyph by glyph: each glyph alone, both aligned on the platform's baseline, must cover the
 *   same pixels to within antialiasing, with the same ink centroid.
 * - Position, glyph by glyph: each glyph's origin along the line must be where the platform's
 *   layout puts it (kerning included).
 * - Ink, line: the whole line must match too.
 *
 * Every case animates its axes through [VariableFontText]'s draw-time values rather than fixing
 * them, so this also covers the animated path.
 */
@Config(sdk = [35], qualifiers = "w800dp-h400dp-xhdpi")
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderFidelityTest {
  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun everyTestedAxisAtItsExtremes() {
    val cases = testFonts.flatMap { testFont ->
      testFont.axes.flatMap { axis ->
        val info = testFont.font.axes.first { it.tag == axis }
        listOf(info.minValue, info.maxValue).map { Case(testFont, mapOf(axis to it)) }
      }
    }
    check(cases)
  }

  @Test
  fun severalAxesAtOnce() {
    check(
      listOf(
        Case(googleSansFlex, mapOf("wght" to 800f, "ROND" to 100f)),
        Case(googleSansFlex, mapOf("wght" to 250f, "ROND" to 40f)),
        Case(robotoFlex, mapOf("wght" to 750f, "wdth" to 120f, "slnt" to -10f)),
        Case(testFonts[2], mapOf("wght" to 900f, "slnt" to -15f, "CASL" to 1f, "MONO" to 1f)),
        Case(testFonts[3], mapOf("wght" to 300f, "opsz" to 144f, "SOFT" to 100f)),
      )
    )
  }

  /** The outline tolerance is tight enough to catch an instance a few percent off. */
  @Test
  fun aSlightlyWrongInstanceIsCaught() {
    for ((testFont, values, wrong) in
      listOf(
        Triple(robotoFlex, mapOf("wght" to 400f), mapOf("wght" to 430f)),
        Triple(googleSansFlex, mapOf("wght" to 400f), mapOf("wght" to 420f)),
        Triple(robotoFlex, mapOf("wdth" to 100f), mapOf("wdth" to 104f)),
      )) {
      val case = Case(testFont, values)
      val typeface = platformTypeface(case)
      val worst = TEXT.maxOf { outlineDifference(case, typeface, it.toString(), wrong) }
      assertWithMessage("$case drawn at $wrong").that(worst).isGreaterThan(OUTLINE_TOLERANCE)
      val right = TEXT.maxOf { outlineDifference(case, typeface, it.toString()) }
      assertWithMessage("$case").that(right).isAtMost(OUTLINE_TOLERANCE)
    }
  }

  private fun check(cases: List<Case>) {
    val report = StringBuilder()
    val failures = mutableListOf<String>()
    for (case in cases) {
      show(case)
      val whole = drawRoot()
      // Kerned, like the composable and the platform, at the case's own location.
      val origins = VariableTextOutlineEvaluator.glyphOrigins(case.testFont.font, TEXT, case.values)
      val scale = SIZE_PX / case.testFont.font.unitsPerEm
      val layout = lineLayout!!

      var worstInk = 0f
      var worstInkGlyph = ' '
      var worstCentroid = 0f
      var worstCentroidGlyph = ' '
      var worstPosition = 0f
      var worstInkRatio = 0f
      var worstOutline = 0f
      var worstOutlineGlyph = ' '
      val typeface = platformTypeface(case)
      TEXT.indices.forEach { i ->
        val outline = outlineDifference(case, typeface, TEXT[i].toString())
        if (outline > worstOutline) {
          worstOutline = outline
          worstOutlineGlyph = TEXT[i]
        }
        val mine = crop(whole, "mine$i")
        val platform = crop(whole, "platform$i")
        val ink = difference(mine, platform)
        val centroid = centroidDistance(mine, platform)
        val position =
          abs(origins[i] * scale - layout.getHorizontalPosition(i, usePrimaryDirection = true))
        if (ink > worstInk) {
          worstInk = ink
          worstInkGlyph = TEXT[i]
        }
        if (centroid > worstCentroid) {
          worstCentroid = centroid
          worstCentroidGlyph = TEXT[i]
        }
        worstPosition = maxOf(worstPosition, position)
        worstInkRatio = maxOf(worstInkRatio, abs(ink(mine) / ink(platform) - 1f))
      }
      val line = difference(crop(whole, "mineLine"), crop(whole, "platformLine"))
      val result =
        ("$case: outline=%.4f ('$worstOutlineGlyph') glyph ink=%.4f ('$worstInkGlyph') ink mass=%.3f centroid=%.3fpx " +
            "('$worstCentroidGlyph') origin=%.3fpx; line ink=%.4f")
          .format(worstOutline, worstInk, worstInkRatio, worstCentroid, worstPosition, line)
      report.appendLine(result)
      if (
        worstOutline > OUTLINE_TOLERANCE ||
          worstInk > GLYPH_INK_TOLERANCE ||
          worstCentroid > CENTROID_TOLERANCE_PX ||
          worstPosition > POSITION_TOLERANCE_PX ||
          line > LINE_INK_TOLERANCE
      ) {
        failures += result
      }
    }
    println(report)
    assertWithMessage(report.toString()).that(failures).isEmpty()
  }

  private class Case(val testFont: TestFont, val values: Map<String, Float>) {
    override fun toString(): String = "$testFont $values"
  }

  private var current by mutableStateOf<Case?>(null)
  private var lineLayout: TextLayoutResult? = null
  private val baselines = HashMap<String, Float>()

  private fun show(case: Case) {
    if (current == null) composeRule.setContent { current?.let { key(it) { Content(it) } } }
    current = case
    composeRule.waitForIdle()
  }

  @Composable
  private fun Content(case: Case) {
    val family =
      FontFamily(
        Font(
          case.testFont.file,
          variationSettings =
            FontVariation.Settings(
              *case.values.map { (tag, v) -> FontVariation.Setting(tag, v) }.toTypedArray()
            ),
        )
      )
    val style =
      TextStyle(
        color = Color.White,
        fontSize = SIZE.sp,
        fontFamily = family,
        textMotion = TextMotion.Animated,
      )
    Column(Modifier.background(Color.Black)) {
      Row {
        TEXT.forEachIndexed { i, c ->
          Cell("platform$i") {
            Platform(c.toString(), style, "platform$i") {
              baselines["platform$i"] = it.firstBaseline
            }
          }
        }
      }
      Row {
        TEXT.forEachIndexed { i, c -> Cell("mine$i") { Mine(case, c.toString(), "platform$i") } }
      }
      Cell("platformLine", LINE_WIDTH) {
        Platform(TEXT, style, "platformLine") {
          baselines["platformLine"] = it.firstBaseline
          lineLayout = it
        }
      }
      Cell("mineLine", LINE_WIDTH) { Mine(case, TEXT, "platformLine") }
    }
  }

  @Composable
  private fun Cell(tag: String, width: Int = CELL, content: @Composable () -> Unit) {
    Box(Modifier.size(width.dp, CELL_HEIGHT.dp).testTag(tag)) {
      Box(Modifier.padding(start = MARGIN.dp)) { content() }
    }
  }

  @Composable
  private fun Platform(
    text: String,
    style: TextStyle,
    tag: String,
    onLayout: (TextLayoutResult) -> Unit,
  ) {
    BasicText(
      text,
      style = style,
      softWrap = false,
      maxLines = 1,
      overflow = TextOverflow.Visible,
      onTextLayout = onLayout,
    )
  }

  /** [VariableFontText] moved, at draw time only, onto the platform text's baseline. */
  @Composable
  private fun Mine(case: Case, text: String, alignWith: String) {
    val font = case.testFont.font
    val ownBaseline = font.ascender * SIZE_PX / font.unitsPerEm
    check(LocalDensity.current.density * SIZE == SIZE_PX)
    VariableFontText(
      text,
      font,
      axes = case.values.mapValues { (_, v) -> { v } },
      fontSize = SIZE.sp,
      modifier =
        Modifier.drawWithContent {
          translate(top = (baselines[alignWith] ?: ownBaseline) - ownBaseline) {
            this@drawWithContent.drawContent()
          }
        },
      color = Color.White,
      kerningLocation = case.values,
    )
  }

  /** The platform's typeface for [case]: the font file instanced with its variation settings. */
  private fun platformTypeface(case: Case): Typeface =
    Typeface.Builder(case.testFont.file)
      .setFontVariationSettings(case.values.entries.joinToString(",") { (tag, v) -> "'$tag' $v" })
      .build()

  /**
   * The platform's own outline of [glyph] (`Paint.getTextPath`, unhinted) against this library's,
   * both filled with the same antialiasing: the outlines alone, without the platform's text gamma.
   */
  private fun outlineDifference(
    case: Case,
    typeface: Typeface,
    glyph: String,
    drawn: Map<String, Float> = case.values,
  ): Float {
    val x0 = MARGIN * 2f
    val baseline = CELL_HEIGHT * 1.5f
    val platformPath = android.graphics.Path()
    Paint()
      .apply {
        this.typeface = typeface
        textSize = SIZE_PX
        isSubpixelText = true
        isLinearText = true
        hinting = Paint.HINTING_OFF
      }
      .getTextPath(glyph, 0, glyph.length, x0, baseline, platformPath)
    val evaluator =
      VariableTextOutlineEvaluator(
        VariableTextOutlineEvaluator.exactOutline(
          case.testFont.font,
          glyph,
          case.values.keys.toList(),
        )
      )
    val renderer = VariableFontTextRenderer(evaluator)
    val scale = SIZE_PX / evaluator.unitsPerEm
    renderer.update(case.values.keys.map { drawn.getValue(it) }.toFloatArray(), scale)
    val mine = android.graphics.Path(renderer.path.asAndroidPath())
    mine.offset(x0, baseline - evaluator.ascender * scale)
    return difference(fill(platformPath), fill(mine))
  }

  private fun fill(path: android.graphics.Path): Bitmap {
    val bitmap = Bitmap.createBitmap(CELL * 2, CELL_HEIGHT * 2, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).apply {
      drawColor(android.graphics.Color.BLACK)
      drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
    }
    return bitmap
  }

  private fun drawRoot(): Bitmap {
    val root = composeRule.activity.window.decorView
    val whole = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
    composeRule.runOnUiThread { root.draw(Canvas(whole)) }
    return whole
  }

  private fun crop(whole: Bitmap, tag: String): Bitmap {
    val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
    return Bitmap.createBitmap(
      whole,
      bounds.left.roundToInt(),
      bounds.top.roundToInt(),
      bounds.width.roundToInt(),
      bounds.height.roundToInt(),
    )
  }

  private companion object {
    const val TEXT = "Hamburgefonstiv"
    const val SIZE = 28
    const val SIZE_PX = 56f
    const val CELL = 52
    const val CELL_HEIGHT = 48
    const val MARGIN = 8
    const val LINE_WIDTH = 400
    /**
     * Outlines filled alike agree to about 0.02, and to 0.04 for Google Sans Flex's hairline
     * (`wght` 1), where a stroke is a fraction of a pixel and any edge difference is a large part
     * of its ink.
     */
    const val OUTLINE_TOLERANCE = 0.05f

    /**
     * Platform text is drawn with a text gamma and contrast boost that a filled path is not: the
     * platform's glyphs carry 3–15% more ink at the same outline, and up to 22% at a hairline. The
     * same boost brightens half-covered stems more than pixel-aligned ones, which moves a hairline
     * glyph's ink centroid by about a pixel; elsewhere centroids agree to under half a pixel.
     */
    const val GLYPH_INK_TOLERANCE = 0.25f
    const val CENTROID_TOLERANCE_PX = 1.25f
    const val POSITION_TOLERANCE_PX = 0.1f
    const val LINE_INK_TOLERANCE = 0.25f
  }
}

/**
 * The summed absolute difference in luminance over the summed ink of the two: 0 when identical, 1
 * when they share no ink. Normalizing by ink rather than area keeps a thin glyph in a large box
 * from looking better than it is.
 */
internal fun difference(a: Bitmap, b: Bitmap): Float {
  var diff = 0.0
  var ink = 0.0
  for (y in 0 until minOf(a.height, b.height)) {
    for (x in 0 until minOf(a.width, b.width)) {
      val la = (a.getPixel(x, y) and 0xff) / 255.0
      val lb = (b.getPixel(x, y) and 0xff) / 255.0
      diff += abs(la - lb)
      ink += maxOf(la, lb)
    }
  }
  return if (ink == 0.0) 0f else (diff / ink).toFloat()
}

/** The distance in pixels between the ink centroids of [a] and [b]. */
internal fun centroidDistance(a: Bitmap, b: Bitmap): Float {
  fun centroid(bitmap: Bitmap): Pair<Double, Double> {
    var sum = 0.0
    var sx = 0.0
    var sy = 0.0
    for (y in 0 until bitmap.height) {
      for (x in 0 until bitmap.width) {
        val l = (bitmap.getPixel(x, y) and 0xff) / 255.0
        sum += l
        sx += l * x
        sy += l * y
      }
    }
    return if (sum == 0.0) 0.0 to 0.0 else sx / sum to sy / sum
  }
  val (ax, ay) = centroid(a)
  val (bx, by) = centroid(b)
  return hypot(ax - bx, ay - by).toFloat()
}

/** The total ink of [bitmap], in fully covered pixels. */
internal fun ink(bitmap: Bitmap): Float {
  var sum = 0.0
  for (y in 0 until bitmap.height) {
    for (x in 0 until bitmap.width) sum += (bitmap.getPixel(x, y) and 0xff) / 255.0
  }
  return sum.toFloat()
}
