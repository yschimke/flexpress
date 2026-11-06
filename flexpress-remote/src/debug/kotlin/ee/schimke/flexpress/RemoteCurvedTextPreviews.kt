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
import android.icu.text.DecimalFormat
import androidx.compose.foundation.layout.size
import androidx.compose.remote.creation.compose.layout.RemoteTime
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.asRdp
import androidx.compose.remote.creation.compose.state.floor
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.preview.AnimatedPreview
import ee.schimke.flexpress.remote.R

/** [value], a whole number from 0 to 99, as two digits. */
@SuppressLint("RestrictedApi")
private fun twoDigits(value: RemoteFloat): RemoteString {
  val digit = DecimalFormat("0")
  val ones = value % 10f
  return ((value - ones) / 10f).toRemoteString(digit) + ones.toRemoteString(digit)
}

/**
 * `h:mm:ss` for [seconds] since midnight, the hour unpadded, as a [RemoteString] the player builds:
 * the text a curved clock draws.
 */
@SuppressLint("RestrictedApi")
private fun clockText(seconds: RemoteFloat): RemoteString {
  val whole = floor(seconds)
  val hours = floor(whole / 3600f)
  val minutes = floor(whole / 60f) % 60f
  return hours.toRemoteString(DecimalFormat("0")) +
    ":" +
    twoDigits(minutes) +
    ":" +
    twoDigits(whole % 60f)
}

/**
 * A curved clock on a round watch, kept by the player: a second every 400 ms from 9:59:55, so each
 * four-second loop passes 10:00:00. The text is a `RemoteString` the document builds from its own
 * time; the figures are tabular, and the new leading 1 grows in as the hour gains a digit.
 */
@Preview(backgroundColor = 0xff000000, showBackground = true, widthDp = 192, heightDp = 192)
@AnimatedPreview(durationMs = 4000, frameIntervalMs = 100, showCurves = false)
@SuppressLint("RestrictedApi")
@Composable
fun RemoteCurvedClockAnimatedPreview() {
  val context = LocalContext.current
  val font = remember { context.variableFont(R.raw.roboto_flex) }
  SelfAnimatedDocumentPreview(
    Modifier.size(192.dp, 192.dp),
    documentWidth = 192,
    documentHeight = 192,
  ) {
    val seconds = RemoteTime().ContinuousSec() % 4f * 2.5f + (9 * 3600 + 59 * 60 + 55).toFloat()
    val axes = mapOf("wght" to sweep(400f, 800f, 4f))
    val glyphs = rememberVariableFontGlyphs(font, "0123456789:", axes)
    RemoteVariableFontCurvedText(
      text = clockText(seconds),
      maxLength = 8,
      glyphs = glyphs,
      fontSize = 20.dp.asRdp(),
      radius = 78.dp.asRdp(),
      tabularFigures = true,
      growLeadingGlyph = true,
      color = Color.White.rc,
    )
  }
}
