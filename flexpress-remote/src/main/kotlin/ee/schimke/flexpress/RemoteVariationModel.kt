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
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.clamp
import androidx.compose.remote.creation.compose.state.mad
import androidx.compose.remote.creation.compose.state.rf

/**
 * Builds the Remote Compose float expressions that evaluate a font's variation model on the player.
 * Each tent is written once, as clamped ramps of its axis' user value, each distinct product of
 * tents once, and each distinct coordinate once, as a chain of multiply-adds; the [RemoteFloat]s
 * returned refer to them rather than repeat them.
 *
 * @param axes The animated axes, by index, with their user-space values.
 */
@SuppressLint("RestrictedApi")
internal class RemoteVariationModel(
  private val font: VariableFont,
  private val axes: Map<Int, RemoteFloat>,
) {
  private val tents = HashMap<Tent, RemoteFloat>()
  private val scalars = HashMap<List<Tent>, RemoteFloat>()
  private val forms = HashMap<AnimatedForm, RemoteFloat>()

  /**
   * The value of [form] on the player. Each multiply-add holds two more values on the stack until
   * the chain unwinds, so a long chain is written in parts that fit an expression's 32 tokens.
   */
  fun float(form: AnimatedForm): RemoteFloat =
    if (form.isConstant) form.constant.rf
    else
      forms.getOrPut(form) {
        form.terms.entries.chunked(TERMS_PER_EXPRESSION).fold(form.constant.rf) { sum, terms ->
          terms.fold(sum) { s, (region, k) -> mad(scalar(region), k.rf, s) }.createReference()
        }
      }

  private fun scalar(region: List<Tent>): RemoteFloat =
    scalars.getOrPut(region) {
      if (region.size == 1) tent(region.single())
      else region.map(::tent).reduce { p, t -> p * t }.createReference()
    }

  private fun tent(t: Tent): RemoteFloat =
    tents.getOrPut(t) { font.tentOf(t, axes.getValue(t.axis)).createReference() }
}

/** [tent] on the player, as clamped ramps of its axis' user [value]. */
@SuppressLint("RestrictedApi")
internal fun VariableFont.tentOf(tent: Tent, value: RemoteFloat): RemoteFloat {
  val (y0, ramps) = tentRamps(tent)
  return ramps.fold(y0.rf) { sum, (knot, width, slope) ->
    sum + clamp(value - knot, 0f, width) * slope
  }
}
