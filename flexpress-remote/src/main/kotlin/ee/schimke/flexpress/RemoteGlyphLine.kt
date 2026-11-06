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
import androidx.compose.remote.core.PaintOperation
import androidx.compose.remote.core.operations.ConditionalOperations
import androidx.compose.remote.core.operations.Utils
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression as E
import androidx.compose.remote.creation.compose.capture.RemoteComposeCreationState
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.max
import androidx.compose.remote.creation.compose.state.min
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.ri
import kotlin.math.abs
import kotlin.math.ceil

/**
 * One position of a [RemoteString] line, as the player lays it out inside the line's slot loop.
 * Every value is in pixels from the start of the line, and is only valid inside the loop body.
 *
 * @property index The position, `0 until maxLength`.
 * @property character The character's index in the glyph set, or `-1` for none: past the end of the
 *   text, or a character the set lacks.
 * @property pen Where the glyph's origin is along the line.
 * @property advance How far the glyph moves the pen, scaled by [scale].
 * @property scale The glyph's size, `1` as shaped.
 * @property unitScale Pixels per font unit for this glyph: the font's, times [scale].
 *
 * Each is written to the document when the slot is made, before any branch on the character: a
 * value first written inside one character's branch would not be computed in another's.
 */
internal class GlyphSlot(
  val index: RemoteFloat,
  val character: RemoteFloat,
  val pen: RemoteFloat,
  val advance: RemoteFloat,
  val scale: RemoteFloat,
  val unitScale: RemoteFloat,
)

/**
 * One line of a [RemoteString] drawn from [glyphs] with every position handled by one player
 * `loop`, so the document grows with the glyph set and not with [maxLength]: each character's
 * outline is written once and every position that shows it refers to it.
 *
 * A loop cannot carry a running sum from one position to the next, so the line is laid out in two
 * passes. The first loop writes each position's advance (its own, its kerning, any [glyphScale])
 * into a dynamic array; the second gives each position the sum of the advances before it
 * (`A_SUM_TILL`), and [total] is the sum of them all. Both loops clamp the substrings they read to
 * the text's length: a substring past the end throws on the player.
 *
 * Everything is written straight to the document, in order, as [RemoteVariableFontText] does:
 * RemoteCanvas records its own operations to write later, which would put these outside their
 * loops. Values that do not depend on the position (the axes, every outline coordinate) are written
 * before the loops, so nothing in a loop or a branch defines a value used elsewhere.
 *
 * Inside a loop the player refreshes the variables of the loop's own operations but not of those
 * nested in a conditional block, so every expression, path and matrix operation is written at the
 * loop's top level, and a conditional only ever wraps a draw.
 *
 * @param fontScale Pixels per font unit.
 * @param tabularFigures Each digit takes the widest digit's advance at the current axis values.
 * @param glyphScale Each position's scale, from its index and the text's length.
 */
@SuppressLint("RestrictedApi")
internal class RemoteGlyphLine(
  private val state: RemoteComposeCreationState,
  private val glyphs: VariableFontGlyphs,
  private val text: RemoteString,
  private val maxLength: Int,
  private val fontScale: RemoteFloat,
  tabularFigures: Boolean = false,
  private val glyphScale: ((index: RemoteFloat, length: RemoteFloat) -> RemoteFloat)? = null,
) {
  val writer = state.document
  val written = glyphs.write(state)
  private val set = glyphs.set

  /** The text's length, as a float. */
  val length: RemoteFloat = text.length.toRemoteFloat()

  private val characterAdvances = writer.addDynamicFloatArray(set.size.toFloat())
  private val advances = writer.addDynamicFloatArray(maxLength.toFloat())
  private val kerns = writer.addDynamicFloatArray(maxLength.toFloat())
  private val characters = writer.addDynamicFloatArray(maxLength.toFloat())
  private val scales = writer.addDynamicFloatArray(maxLength.toFloat())

  /** The width of the line, in pixels: valid after [layout]. */
  lateinit var total: RemoteFloat
    private set

  fun id(value: RemoteFloat): Float = value.getFloatIdForCreationState(state)

  init {
    id(length)
    id(fontScale)
    val digits = set.indices.filter { set[it].single().isDigit() }
    val cell =
      if (tabularFigures && digits.size > 1) {
        digits.map { written.advances[it] }.reduce { a, b -> max(a, b) }
      } else {
        null
      }
    for (c in set.indices) {
      val units = if (cell != null && c in digits) cell else written.advances[c]
      put(characterAdvances, c.toFloat().rf, units * fontScale)
    }
  }

  private fun put(array: Float, index: RemoteFloat, value: RemoteFloat) =
    writer.buffer.setArrayValue(Utils.idFromNan(array), id(index), id(value))

  private fun read(array: Float, index: RemoteFloat): RemoteFloat =
    RemoteFloat(writer.floatExpression(array, id(index), E.A_DEREF))

  /** The first pass: each position's character, scale, kerning and advance. */
  fun layout() {
    val fonts = glyphs.fonts
    val n = text.length
    val i = RemoteFloat(writer.startLoop(maxLength.toFloat()))
    val start = min(n, i.toRemoteInt())
    val end = min(n, (i + 1f).toRemoteInt())
    val character = fonts.index.measureWidth(text.substring(start, end), 0f.rf) - 1f
    // 1 for a character the set has, 0 for none.
    val known = min(character + 1f, 1f)
    val own = read(characterAdvances, max(character, 0f)) * known
    // The kerning font gives every glyph one unit plus the pair kerning, so the text through this
    // position less the text before it is this glyph's unit plus the kerning of the pair it ends.
    val kern =
      (fonts.kerning.measureWidth(text.substring(0.ri, end), 0f.rf) -
        fonts.kerning.measureWidth(text.substring(0.ri, start), 0f.rf) -
        known) * fontScale
    val scale = glyphScale?.invoke(i, length) ?: 1f.rf
    put(characters, i, character)
    put(scales, i, scale)
    put(kerns, i, kern * scale)
    put(advances, i, (own + kern) * scale)
    writer.endLoop()
    total = RemoteFloat(writer.floatExpression(advances, E.A_SUM))
    id(total)
  }

  /** The second pass: [body] for each position, inside the loop. Call after [layout]. */
  fun forEachSlot(body: (GlyphSlot) -> Unit) {
    val i = RemoteFloat(writer.startLoop(maxLength.toFloat()))
    val kern = read(kerns, i)
    val before = RemoteFloat(writer.floatExpression(advances, id(i), 1f, E.SUB, E.A_SUM_TILL))
    val scale = read(scales, i)
    val slot =
      GlyphSlot(
        index = i,
        character = read(characters, i),
        pen = before + kern,
        advance = read(advances, i) - kern,
        scale = scale,
        unitScale = fontScale * scale,
      )
    for (v in listOf(slot.character, slot.pen, slot.advance, slot.scale, slot.unitScale)) id(v)
    body(slot)
    writer.endLoop()
  }

  /**
   * Draws [slot]'s glyph rigidly: [transform] moves the canvas so the glyph's origin on the
   * baseline is at (0, 0), and the glyph's own path, shared by every position that shows it, is
   * drawn there at its scale. Nothing is drawn for a position with no character.
   */
  fun drawRigid(slot: GlyphSlot, transform: () -> Unit) {
    val k = id(slot.unitScale)
    val flipped = writer.floatExpression(k, -1f, E.MUL)
    val firstPath = written.firstPath
    val pathId = firstPath?.let { (slot.character.toRemoteInt() + it).getIdForCreationState(state) }
    writer.save()
    transform()
    writer.scale(k, flipped)
    if (pathId != null) {
      writer.conditionalOperations(ConditionalOperations.TYPE_GTE, id(slot.character), 0f)
      writer.drawPath(pathId or PaintOperation.PTR_DEREFERENCE)
      writer.endConditionalOperations()
    } else {
      for (c in set.indices) {
        writer.conditionalOperations(ConditionalOperations.TYPE_EQ, id(slot.character), c.toFloat())
        writer.drawPath(written.paths[c])
        writer.endConditionalOperations()
      }
    }
    writer.restore()
  }

  private var warpGeometry: List<List<WarpCommand>>? = null

  /**
   * Prepares every glyph's outline for [drawWarped], split so no piece spans more than
   * [maxSegmentUnits] font units across the line. Call before [layout]: its coordinates are written
   * here, outside the loops.
   */
  fun prepareWarp(maxSegmentUnits: Float) {
    warpGeometry =
      glyphs.outlines.map { outline ->
        val builder = WarpGeometryBuilder(maxSegmentUnits)
        outline.emit(builder)
        builder.commands.onEach { command -> command.points.forEach { id(written.form(it)) } }
      }
  }

  /**
   * Draws [slot]'s glyph through a warp, built on the player from its outline's points: [map] takes
   * a point `u` pixels along the line from its start and `v` pixels above the baseline to the
   * canvas. Each piece is the quadratic through its warped start, midpoint and end, so straight
   * stems bend with the warp. Nothing is drawn for a position with no character.
   *
   * Every character's path is built for every position (the player does not rebuild a path inside a
   * conditional), and only the one the position shows is drawn. [map] is called once per point of
   * every character, so any value it uses that depends on neither `u` nor `v` should be written
   * (with [id]) once, before this is called.
   */
  fun drawWarped(slot: GlyphSlot, map: (u: RemoteFloat, v: RemoteFloat) -> WarpedPoint) {
    val geometry = checkNotNull(warpGeometry) { "prepareWarp before drawing warped glyphs" }
    val k = slot.unitScale
    val point = { x: LinearForm, y: LinearForm ->
      map(slot.pen + written.form(x) * k, written.form(y) * k)
    }
    for (c in set.indices) {
      if (geometry[c].isEmpty()) continue
      var path = 0
      var lastX = 0f
      var lastY = 0f
      for (command in geometry[c]) {
        when (command) {
          is WarpCommand.Move -> {
            val p = point(command.x, command.y)
            lastX = id(p.x)
            lastY = id(p.y)
            if (path == 0) path = writer.pathCreate(lastX, lastY)
            else writer.pathAppendMoveTo(path, lastX, lastY)
          }
          is WarpCommand.Piece -> {
            val mid = point(command.midX, command.midY)
            val end = point(command.x, command.y)
            val endX = id(end.x)
            val endY = id(end.y)
            // The control point of the quadratic through start, mid and end.
            val cx =
              writer.floatExpression(id(mid.x), 2f, E.MUL, lastX, endX, E.ADD, 0.5f, E.MUL, E.SUB)
            val cy =
              writer.floatExpression(id(mid.y), 2f, E.MUL, lastY, endY, E.ADD, 0.5f, E.MUL, E.SUB)
            writer.pathAppendQuadTo(path, cx, cy, endX, endY)
            lastX = endX
            lastY = endY
          }
          WarpCommand.Close -> writer.pathAppendClose(path)
        }
      }
      writer.conditionalOperations(ConditionalOperations.TYPE_EQ, id(slot.character), c.toFloat())
      writer.drawPath(path)
      writer.endConditionalOperations()
    }
  }
}

/** A point on the canvas, from a [RemoteGlyphLine.drawWarped] map. */
internal class WarpedPoint(val x: RemoteFloat, val y: RemoteFloat)

internal sealed class WarpCommand {
  abstract val points: List<LinearForm>

  class Move(val x: LinearForm, val y: LinearForm) : WarpCommand() {
    override val points = listOf(x, y)
  }

  /** A piece of a contour, from the last point through ([midX], [midY]) to ([x], [y]). */
  class Piece(val midX: LinearForm, val midY: LinearForm, val x: LinearForm, val y: LinearForm) :
    WarpCommand() {
    override val points = listOf(midX, midY, x, y)
  }

  object Close : WarpCommand() {
    override val points = emptyList<LinearForm>()
  }
}

/**
 * Splits an outline into [WarpCommand] pieces. Only extent across the line is split: under the
 * warps used here a line across the text curves, while one up the text stays straight.
 */
internal class WarpGeometryBuilder(private val maxSegmentUnits: Float) : PathSink<LinearForm> {
  val commands = mutableListOf<WarpCommand>()
  private var curX = LinearForm.ZERO
  private var curY = LinearForm.ZERO
  private var startX = LinearForm.ZERO
  private var startY = LinearForm.ZERO

  private fun pieces(extent: Float): Int =
    ceil(abs(extent) / maxSegmentUnits).toInt().coerceIn(1, MAX_PIECES)

  override fun moveTo(x: LinearForm, y: LinearForm) {
    commands += WarpCommand.Move(x, y)
    curX = x
    curY = y
    startX = x
    startY = y
  }

  override fun lineTo(x: LinearForm, y: LinearForm) {
    val n = pieces(x.constant - curX.constant)
    for (k in 1..n) {
      val tm = (k - 0.5f) / n
      val te = k.toFloat() / n
      commands +=
        WarpCommand.Piece(
          curX + (x - curX) * tm,
          curY + (y - curY) * tm,
          curX + (x - curX) * te,
          curY + (y - curY) * te,
        )
    }
    curX = x
    curY = y
  }

  override fun quadTo(x1: LinearForm, y1: LinearForm, x2: LinearForm, y2: LinearForm) {
    val extent = abs(x1.constant - curX.constant) + abs(x2.constant - x1.constant)
    val n = pieces(extent)
    fun at(t: Float, a: LinearForm, c: LinearForm, b: LinearForm): LinearForm {
      val mt = 1f - t
      return a * (mt * mt) + c * (2f * mt * t) + b * (t * t)
    }
    for (k in 1..n) {
      val tm = (k - 0.5f) / n
      val te = k.toFloat() / n
      commands +=
        WarpCommand.Piece(
          at(tm, curX, x1, x2),
          at(tm, curY, y1, y2),
          at(te, curX, x1, x2),
          at(te, curY, y1, y2),
        )
    }
    curX = x2
    curY = y2
  }

  override fun close() {
    if (curX.constant != startX.constant || curY.constant != startY.constant) {
      lineTo(startX, startY)
    }
    commands += WarpCommand.Close
    curX = startX
    curY = startY
  }

  private companion object {
    const val MAX_PIECES = 12
  }
}
