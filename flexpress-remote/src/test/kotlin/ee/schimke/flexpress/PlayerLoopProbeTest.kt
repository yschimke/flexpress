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
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.remote.core.operations.ConditionalOperations
import androidx.compose.remote.core.operations.Utils
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression as E
import androidx.compose.remote.creation.compose.capture.rememberRemoteDocument
import androidx.compose.remote.creation.compose.layout.RemoteCanvas
import androidx.compose.remote.creation.compose.layout.RemoteOffset
import androidx.compose.remote.creation.compose.layout.RemoteSize
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.RemoteBitmapFont
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemotePaint
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.min
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlin.math.roundToInt
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pins down the player behaviour the RemoteString effects are built on: inside a `loop`, a
 * substring at the loop index, a bitmap-font measure of it and expressions of the index are
 * re-evaluated on every iteration. A substring past the end of the text throws on the player (it
 * draws its error screen), so both ends are clamped to the length.
 */
@Config(sdk = [35], qualifiers = "w400dp-h400dp-mdpi")
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerLoopProbeTest {
  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  private var text by mutableStateOf("")

  @SuppressLint("RestrictedApi")
  @Test
  fun loopBodiesReadTheirIndexTextAndMeasures() {
    val pixel = ImageBitmap(1, 1)
    // 'a' is 1 wide, 'b' 2, 'c' 3: a bar's height says which character a slot read.
    val font =
      RemoteBitmapFont(
        "abc"
          .mapIndexed { i, c ->
            RemoteBitmapFont.Glyph(c.toString(), pixel, 0, 0, i.toShort(), 0, 1, 1)
          },
        emptyMap(),
      )
    composeRule.setContent {
      val doc = rememberRemoteDocument {
        RemoteCanvas(RemoteModifier.size(400.rdp)) {
          val text = RemoteString.createNamedRemoteString("text", "")
          val paint = RemotePaint { color = Color.White.rc }
          drawRect(RemotePaint { color = Color.Black.rc })
          // Control: a constant bar outside any loop, at the bottom.
          drawRect(paint, RemoteOffset(0f.rf, 380f.rf), RemoteSize(400f.rf, 20f.rf))
          loop(0f.rf, 8f.rf, 1f.rf) { i ->
            // A substring past the end throws on the player: clamp both ends to the length.
            val n = text.length
            val ch = text.substring(min(n, i.toRemoteInt()), min(n, (i + 1f).toRemoteInt()))
            val w = font.measureWidth(ch, 0f.rf)
            val left = i * 40f + 10f
            // A constant bar per slot, and one as tall as the character's width.
            drawRect(paint, RemoteOffset(left, 300f.rf), RemoteSize(20f.rf, 20f.rf))
            drawRect(paint, RemoteOffset(left, 200f.rf - w * 40f), RemoteSize(20f.rf, w * 40f))
          }
        }
      }
      Box(Modifier.size(400.dp, 400.dp).testTag("p")) {
        doc.value?.let {
          RemoteDocumentPlayer(
            it,
            400,
            400,
            Modifier.size(400.dp, 400.dp),
            update = { p -> p.setUserLocalString("text", text) },
          )
        }
      }
    }
    for ((t, expected) in
      listOf("abc" to listOf(1, 2, 3), "cab" to listOf(3, 1, 2), "b" to listOf(2))) {
      text = t
      val b = settled()
      val heights = (0 until 8).map { slot -> barHeight(b, 10 + slot * 40 + 10) }
      println("'$t' -> $heights")
      // The constant bar drawn in every iteration, whatever the text.
      for (slot in 0 until 8) assertThat(b.getPixel(20 + slot * 40, 310) and 0xff)
        .isGreaterThan(128)
      assertThat(heights.take(expected.size)).isEqualTo(expected.map { it * 40 })
      assertThat(heights.drop(expected.size).all { it == 0 }).isTrue()
    }
  }

  /**
   * The pen positions every RemoteString effect needs, without a running sum (a loop cannot carry
   * one): a first loop writes each slot's advance into a dynamic float array, and a second reads
   * the advances before it with `A_SUM_TILL`.
   */
  @SuppressLint("RestrictedApi")
  @Test
  fun aDynamicArrayGivesEachSlotItsPen() {
    val pixel = ImageBitmap(1, 1)
    val font =
      RemoteBitmapFont(
        "abc"
          .mapIndexed { i, c ->
            RemoteBitmapFont.Glyph(c.toString(), pixel, 0, 0, i.toShort(), 0, 1, 1)
          },
        emptyMap(),
      )
    composeRule.setContent {
      val doc = rememberRemoteDocument {
        RemoteCanvas(RemoteModifier.size(400.rdp)) {
          // Everything is written straight to the document, in order: RemoteCanvas records its own
          // operations to write later, which would put these array writes outside their loop.
          val state = remoteComposeCreationState
          val writer = state.document
          val text = RemoteString.createNamedRemoteString("text", "")
          val id = { v: RemoteFloat -> v.getFloatIdForCreationState(state) }
          writer.rcPaint.setColor(android.graphics.Color.BLACK).commit()
          writer.drawRect(0f, 0f, 400f, 400f)
          val advances = writer.addDynamicFloatArray(8f)
          val arrayId = Utils.idFromNan(advances)
          val first = RemoteFloat(writer.startLoop(8f))
          run {
            val n = text.length
            val ch = text.substring(min(n, first.toRemoteInt()), min(n, (first + 1f).toRemoteInt()))
            // Each character is as many 20 px cells wide as its glyph in the font.
            val w = font.measureWidth(ch, 0f.rf) * 20f
            writer.buffer.setArrayValue(arrayId, id(first), id(w))
          }
          writer.endLoop()
          writer.rcPaint.setColor(android.graphics.Color.WHITE).commit()
          val i = writer.startLoop(8f)
          val pen = writer.floatExpression(advances, i, 1f, E.SUB, E.A_SUM_TILL)
          val w = writer.floatExpression(advances, i, E.A_DEREF)
          // Each slot's box, inset a pixel so neighbours stay apart.
          writer.drawRect(
            writer.floatExpression(pen, 1f, E.ADD),
            100f,
            writer.floatExpression(pen, w, E.ADD, 1f, E.SUB),
            140f,
          )
          writer.endLoop()
        }
      }
      Box(Modifier.size(400.dp, 400.dp).testTag("p")) {
        doc.value?.let {
          RemoteDocumentPlayer(
            it,
            400,
            400,
            Modifier.size(400.dp, 400.dp),
            update = { p -> p.setUserLocalString("text", text) },
          )
        }
      }
    }
    // a=20, b=40, c=60 px: "cab" starts its boxes at 0, 60 and 80, and ends at 120.
    for ((t, edges) in listOf("cab" to listOf(0, 60, 80, 120), "abc" to listOf(0, 20, 60, 120))) {
      text = t
      val b = settled()
      val row = (0 until 400).map { x -> (b.getPixel(x, 120) and 0xff) > 128 }
      val starts = (0 until 400).filter { x -> row[x] && (x == 0 || !row[x - 1]) }
      assertThat(starts).isEqualTo(edges.dropLast(1).map { it + 1 })
      // Nothing past the line (an empty slot's zero-width box sits at its end).
      assertThat(row.subList(edges.last() + 2, 400).none { it }).isTrue()
    }
  }

  /** A path built in a loop body from chained expressions of the index is rebuilt every time. */
  @SuppressLint("RestrictedApi")
  @Test
  fun aPathBuiltInALoopFollowsTheIndex() {
    composeRule.setContent {
      val doc = rememberRemoteDocument {
        RemoteCanvas(RemoteModifier.size(400.rdp)) {
          val state = remoteComposeCreationState
          val writer = state.document
          writer.rcPaint.setColor(android.graphics.Color.BLACK).commit()
          writer.drawRect(0f, 0f, 400f, 400f)
          writer.rcPaint.setColor(android.graphics.Color.WHITE).commit()
          val i = writer.startLoop(3f)
          // x = (i * 50) * 2 + 20, through two expressions.
          val a = writer.floatExpression(i, 50f, E.MUL)
          val x = writer.floatExpression(a, 2f, E.MUL, 20f, E.ADD)
          val right = writer.floatExpression(x, 40f, E.ADD)
          val path = writer.pathCreate(x, 100f)
          writer.pathAppendLineTo(path, right, 100f)
          writer.pathAppendLineTo(path, right, 140f)
          writer.pathAppendLineTo(path, x, 140f)
          writer.pathAppendClose(path)
          writer.drawPath(path)
          writer.endLoop()
        }
      }
      Box(Modifier.size(400.dp, 400.dp).testTag("p")) {
        doc.value?.let { RemoteDocumentPlayer(it, 400, 400, Modifier.size(400.dp, 400.dp)) }
      }
    }
    val b = settled()
    val row = (0 until 400).map { x -> (b.getPixel(x, 120) and 0xff) > 128 }
    val starts = (0 until 400).filter { x -> row[x] && (x == 0 || !row[x - 1]) }
    val lit = row.count { it }
    assertThat(starts).isEqualTo(listOf(20, 120, 220))
    assertThat(lit).isEqualTo(120)
  }

  /**
   * The same path built inside a conditional block in the loop body is not rebuilt: the player
   * refreshes the variables of a loop's own operations, not of those nested in a conditional. So
   * the RemoteString effects build everything at the loop's top level and use conditionals only to
   * choose what to draw.
   */
  @SuppressLint("RestrictedApi")
  @Test
  fun aPathBuiltInAConditionalInALoopIsNotRebuilt() {
    composeRule.setContent {
      val doc = rememberRemoteDocument {
        RemoteCanvas(RemoteModifier.size(400.rdp)) {
          val state = remoteComposeCreationState
          val writer = state.document
          writer.rcPaint.setColor(android.graphics.Color.BLACK).commit()
          writer.drawRect(0f, 0f, 400f, 400f)
          writer.rcPaint.setColor(android.graphics.Color.WHITE).commit()
          val i = writer.startLoop(3f)
          writer.conditionalOperations(ConditionalOperations.TYPE_GTE, i, 0f)
          val a = writer.floatExpression(i, 50f, E.MUL)
          val x = writer.floatExpression(a, 2f, E.MUL, 20f, E.ADD)
          val right = writer.floatExpression(x, 40f, E.ADD)
          val path = writer.pathCreate(x, 100f)
          writer.pathAppendLineTo(path, right, 100f)
          writer.pathAppendLineTo(path, right, 140f)
          writer.pathAppendLineTo(path, x, 140f)
          writer.pathAppendClose(path)
          writer.drawPath(path)
          writer.endConditionalOperations()
          writer.endLoop()
        }
      }
      Box(Modifier.size(400.dp, 400.dp).testTag("p")) {
        doc.value?.let { RemoteDocumentPlayer(it, 400, 400, Modifier.size(400.dp, 400.dp)) }
      }
    }
    val b = settled()
    val row = (0 until 400).map { x -> (b.getPixel(x, 120) and 0xff) > 128 }
    val starts = (0 until 400).filter { x -> row[x] && (x == 0 || !row[x - 1]) }
    // Only the first iteration's box: had this changed, the effects could build paths in branches.
    assertThat(starts).isEqualTo(listOf(20))
  }

  private fun barHeight(b: Bitmap, x: Int): Int =
    (0 until 200).count { y -> (b.getPixel(x, y) shr 16 and 0xff) > 128 }

  private fun settled(): Bitmap {
    var last: Bitmap? = null
    repeat(20) {
      composeRule.mainClock.advanceTimeByFrame()
      composeRule.waitForIdle()
      val now = capture()
      if (last?.sameAs(now) == true) return now
      last = now
    }
    return last!!
  }

  private fun capture(): Bitmap {
    val root = composeRule.activity.window.decorView
    val whole = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
    composeRule.runOnUiThread { root.draw(Canvas(whole)) }
    val r = composeRule.onNodeWithTag("p").fetchSemanticsNode().boundsInWindow
    return Bitmap.createBitmap(
      whole,
      r.left.roundToInt(),
      r.top.roundToInt(),
      r.width.roundToInt(),
      r.height.roundToInt(),
    )
  }
}
