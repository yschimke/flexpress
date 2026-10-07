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
import androidx.compose.remote.creation.compose.capture.LocalRemoteComposeCreationState
import androidx.compose.remote.creation.compose.capture.RemoteDensity
import androidx.compose.remote.creation.compose.state.RemoteDp
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.runtime.Composable

/**
 * [fontSize] in pixels, when that is known as the document is made: when the size is a constant and
 * so is the density the document is made for, as it usually is. It is not known when the document
 * takes the player's density ([RemoteDensity.Host]) or the size is an expression.
 *
 * Known, the scale from font units to pixels is written as a literal rather than an expression, and
 * the outline can be simplified for the pixels it covers. Not known, both wait for the player.
 */
@SuppressLint("RestrictedApi")
@Composable
internal fun constantPixelSize(fontSize: RemoteDp): Float? =
  constantPixelSize(fontSize, LocalRemoteComposeCreationState.current.remoteDensity)

/** [fontSize] in pixels at [density], when both are constants. */
internal fun constantPixelSize(fontSize: RemoteDp, density: RemoteDensity): Float? {
  val dp = fontSize.value.constantValueOrNull ?: return null
  val perDp = density.density.constantValueOrNull ?: return null
  return dp * perDp
}

/** The scale from font units to pixels: a literal when [pixelSize] is known. */
@SuppressLint("RestrictedApi")
internal fun fontScale(fontSize: RemoteDp, pixelSize: Float?, unitsPerEm: Int): RemoteFloat =
  if (pixelSize != null) (pixelSize / unitsPerEm).rf else fontSize.toPx() * (1f / unitsPerEm)
