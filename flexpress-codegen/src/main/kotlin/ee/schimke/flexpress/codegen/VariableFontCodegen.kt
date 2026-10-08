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

package ee.schimke.flexpress.codegen

import ee.schimke.flexpress.DEFAULT_TOLERANCE_PIXELS
import ee.schimke.flexpress.VariableFont
import ee.schimke.flexpress.outline
import java.io.File

/** What generated source draws with. */
public enum class CodegenTarget {
  /** A `@RemoteComposable` for a Remote Compose document, with `RemoteFloat` axes. */
  RemoteCompose,

  /** A Compose UI `@Composable`, with each axis a `() -> Float` read while drawing. */
  ComposeUi,
}

/**
 * Writes Kotlin source for one fixed text: its [VariableTextOutline][outline], worked out now and
 * kept as an encoded string constant, and a composable that draws it. The app then needs neither
 * the font nor any layout at run time: it decodes a string.
 *
 * By default the composable draws through the library, `RemoteVariableFontText` from
 * `flexpress-remote` or `VariableFontText` from `flexpress-compose`, so the app depends on it.
 * Standalone, the file carries its own decoder and drawing instead and depends only on Compose UI
 * or Remote Compose: one file to copy into any project.
 *
 * Run it from a unit test that [write]s each text into the app's sources and fails when one is
 * stale; see this module's README.
 */
public object VariableFontCodegen {
  /**
   * Writes [generate]'s source to `<sourceRoot>/<package path>/<functionName>.kt`, if [update], and
   * returns whether the file already held exactly that source. A test calls it for every text with
   * [update] off to fail on stale sources, and with it on to regenerate them.
   */
  public fun write(
    sourceRoot: File,
    packageName: String,
    functionName: String,
    font: VariableFont,
    fontName: String,
    text: String,
    axes: List<String>,
    location: Map<String, Float> = emptyMap(),
    pixelSize: Float? = null,
    tolerancePixels: Float = DEFAULT_TOLERANCE_PIXELS,
    fileHeader: String? = null,
    update: Boolean = true,
    target: CodegenTarget = CodegenTarget.RemoteCompose,
    standalone: Boolean = false,
  ): Boolean {
    val source =
      generate(
        packageName,
        functionName,
        font,
        fontName,
        text,
        axes,
        location,
        pixelSize,
        tolerancePixels,
        fileHeader,
        target,
        standalone,
      )
    val file = File(sourceRoot, packageName.replace('.', '/') + "/$functionName.kt")
    val upToDate = file.isFile && file.readText() == source
    if (update && !upToDate) {
      file.parentFile.mkdirs()
      file.writeText(source)
    }
    return upToDate
  }

  /**
   * The source for [text] in [font] with [axes] animated: a composable named [functionName] in
   * [packageName] for [target], and the outline it draws. Each animated axis is a parameter named
   * by its tag in lower case.
   *
   * @param fontName The font's name, for the comments.
   * @param location Values for the axes not in [axes], held fixed.
   * @param pixelSize The size in pixels the text will be drawn at, to simplify the outline for;
   *   null keeps it exact, right at any size.
   * @param tolerancePixels How far simplifying may move an edge, when [pixelSize] is given.
   * @param fileHeader A comment to start the file with, such as a license header.
   * @param target Whether the composable is for Remote Compose or Compose UI.
   * @param standalone Whether the file draws the outline itself, depending only on [target]'s
   *   Compose libraries, rather than through flexpress. Everything but the composable is private to
   *   the file, so several can share a package.
   */
  public fun generate(
    packageName: String,
    functionName: String,
    font: VariableFont,
    fontName: String,
    text: String,
    axes: List<String>,
    location: Map<String, Float> = emptyMap(),
    pixelSize: Float? = null,
    tolerancePixels: Float = DEFAULT_TOLERANCE_PIXELS,
    fileHeader: String? = null,
    target: CodegenTarget = CodegenTarget.RemoteCompose,
    standalone: Boolean = false,
  ): String {
    val encoded = font.outline(text, axes, location, location, pixelSize, tolerancePixels).encode()
    val simplified = pixelSize?.let { "// Simplified for $it px, to within $tolerancePixels px." }
    val outlineName = functionName.replaceFirstChar { it.lowercase() } + "Outline"
    val remote = target == CodegenTarget.RemoteCompose
    return buildString {
      fileHeader?.let {
        appendLine(it.trimEnd())
        appendLine()
      }
      appendLine(
        "// Generated by VariableFontCodegen: \"$text\" in $fontName, animated ${axes.joinToString()}."
      )
      simplified?.let { appendLine(it) }
      if (standalone) {
        appendLine(
          "// Standalone: it needs only ${if (remote) "Remote Compose" else "Compose UI"}, not flexpress."
        )
      }
      appendLine("// Do not edit; regenerate it with VariableFontCodegen.")
      appendLine()
      appendLine("package $packageName")
      appendLine()
      imports(target, standalone).forEach { appendLine("import $it") }
      appendLine()
      val chunks = encoded.chunked(CHUNK)
      if (!standalone) {
        appendLine(
          "/** \"$text\" in $fontName, worked out ahead of time: ${encoded.length} characters, no font. */"
        )
        appendLine("internal val $outlineName: VariableTextOutline by lazy {")
        appendLine("  VariableTextOutline.decode(")
        strings(chunks, "    ")
        appendLine("  )")
        appendLine("}")
        appendLine()
      }
      appendLine("/** Draws \"$text\" in $fontName with ${axes.joinToString()} animated. */")
      appendLine("@Composable")
      if (remote) appendLine("@RemoteComposable")
      appendLine("internal fun $functionName(")
      val axisType = if (remote) "RemoteFloat" else "() -> Float"
      axes.forEach { appendLine("  ${it.lowercase()}: $axisType,") }
      if (remote) {
        appendLine("  fontSize: RemoteDp,")
        appendLine("  modifier: RemoteModifier = RemoteModifier,")
        appendLine("  color: RemoteColor = Color.Black.rc,")
      } else {
        appendLine("  fontSize: TextUnit,")
        appendLine("  modifier: Modifier = Modifier,")
        appendLine("  color: Color = Color.Unspecified,")
      }
      appendLine(") {")
      val values = axes.joinToString { it.lowercase() }
      if (standalone) {
        call(
          "  ${functionName}Outline.Draw",
          listOf("arrayOf($values)", "fontSize", "modifier", "color"),
        )
      } else {
        val axisMap = "mapOf(${axes.joinToString { "\"$it\" to ${it.lowercase()}" }})"
        val arguments =
          if (remote) listOf(outlineName, axisMap, "fontSize", "modifier", "color")
          else listOf(outlineName, axisMap, "fontSize", "modifier", "color", "\"${escape(text)}\"")
        call(if (remote) "  RemoteVariableFontText" else "  VariableFontText", arguments)
      }
      appendLine("}")
      if (standalone) {
        appendLine()
        appendLine(
          "/** \"$text\" in $fontName, worked out ahead of time: ${encoded.length} characters, no font. */"
        )
        appendLine("private object ${functionName}Outline {")
        if (!remote) {
          appendLine("  private const val TEXT = \"${escape(text)}\"")
          appendLine("  private const val AXIS_COUNT = ${axes.size}")
          appendLine()
        }
        appendLine("  private val packed =")
        appendLine("    arrayOf(")
        strings(chunks, "      ")
        appendLine("    )")
        appendLine()
        append(resource("decode.kt.txt"))
        append(resource(if (remote) "remote-compose.kt.txt" else "compose-ui.kt.txt"))
        appendLine("}")
      }
    }
  }

  /**
   * [write] as it was before [CodegenTarget] and standalone files, for code compiled against it.
   */
  @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
  public fun write(
    sourceRoot: File,
    packageName: String,
    functionName: String,
    font: VariableFont,
    fontName: String,
    text: String,
    axes: List<String>,
    location: Map<String, Float> = emptyMap(),
    pixelSize: Float? = null,
    tolerancePixels: Float = DEFAULT_TOLERANCE_PIXELS,
    fileHeader: String? = null,
    update: Boolean = true,
  ): Boolean =
    write(
      sourceRoot,
      packageName,
      functionName,
      font,
      fontName,
      text,
      axes,
      location,
      pixelSize,
      tolerancePixels,
      fileHeader,
      update,
      CodegenTarget.RemoteCompose,
      false,
    )

  /**
   * [generate] as it was before [CodegenTarget] and standalone files, for code compiled against it.
   */
  @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
  public fun generate(
    packageName: String,
    functionName: String,
    font: VariableFont,
    fontName: String,
    text: String,
    axes: List<String>,
    location: Map<String, Float> = emptyMap(),
    pixelSize: Float? = null,
    tolerancePixels: Float = DEFAULT_TOLERANCE_PIXELS,
    fileHeader: String? = null,
  ): String =
    generate(
      packageName,
      functionName,
      font,
      fontName,
      text,
      axes,
      location,
      pixelSize,
      tolerancePixels,
      fileHeader,
      CodegenTarget.RemoteCompose,
      false,
    )

  /** [chunks] as string literals, one per line; as ktfmt writes them, a comma only between. */
  private fun StringBuilder.strings(chunks: List<String>, indent: String) {
    chunks.forEach { appendLine("$indent\"${escape(it)}\"" + if (chunks.size > 1) "," else "") }
  }

  /** A call of [function] with [arguments]: on one line when it fits, else one per line. */
  private fun StringBuilder.call(function: String, arguments: List<String>) {
    val oneLine = "$function(${arguments.joinToString()})"
    if (oneLine.length <= 100) appendLine(oneLine)
    else {
      appendLine("$function(")
      arguments.forEach { appendLine("    $it,") }
      appendLine("  )")
    }
  }

  private fun imports(target: CodegenTarget, standalone: Boolean): List<String> =
    when {
      target == CodegenTarget.RemoteCompose && !standalone ->
        listOf(
          "androidx.compose.remote.creation.compose.layout.RemoteComposable",
          "androidx.compose.remote.creation.compose.modifier.RemoteModifier",
          "androidx.compose.remote.creation.compose.state.RemoteColor",
          "androidx.compose.remote.creation.compose.state.RemoteDp",
          "androidx.compose.remote.creation.compose.state.RemoteFloat",
          "androidx.compose.remote.creation.compose.state.rc",
          "androidx.compose.runtime.Composable",
          "androidx.compose.ui.graphics.Color",
          "ee.schimke.flexpress.RemoteVariableFontText",
          "ee.schimke.flexpress.VariableTextOutline",
        )
      target == CodegenTarget.RemoteCompose ->
        listOf(
          "android.annotation.SuppressLint",
          "androidx.compose.remote.creation.RemotePath",
          "androidx.compose.remote.creation.compose.capture.LocalRemoteComposeCreationState",
          "androidx.compose.remote.creation.compose.layout.RemoteCanvas",
          "androidx.compose.remote.creation.compose.layout.RemoteComposable",
          "androidx.compose.remote.creation.compose.modifier.RemoteModifier",
          "androidx.compose.remote.creation.compose.modifier.height",
          "androidx.compose.remote.creation.compose.modifier.width",
          "androidx.compose.remote.creation.compose.state.RemoteColor",
          "androidx.compose.remote.creation.compose.state.RemoteDp",
          "androidx.compose.remote.creation.compose.state.RemoteFloat",
          "androidx.compose.remote.creation.compose.state.RemotePaint",
          "androidx.compose.remote.creation.compose.state.clamp",
          "androidx.compose.remote.creation.compose.state.mad",
          "androidx.compose.remote.creation.compose.state.rc",
          "androidx.compose.remote.creation.compose.state.rf",
          "androidx.compose.runtime.Composable",
          "androidx.compose.ui.graphics.Color",
        )
      !standalone ->
        listOf(
          "androidx.compose.runtime.Composable",
          "androidx.compose.ui.Modifier",
          "androidx.compose.ui.graphics.Color",
          "androidx.compose.ui.unit.TextUnit",
          "ee.schimke.flexpress.VariableTextOutline",
          "ee.schimke.flexpress.compose.VariableFontText",
        )
      else ->
        listOf(
          "androidx.compose.runtime.Composable",
          "androidx.compose.runtime.remember",
          "androidx.compose.ui.Modifier",
          "androidx.compose.ui.draw.drawBehind",
          "androidx.compose.ui.graphics.Color",
          "androidx.compose.ui.graphics.Path",
          "androidx.compose.ui.graphics.drawscope.DrawScope",
          "androidx.compose.ui.graphics.isSpecified",
          "androidx.compose.ui.layout.FirstBaseline",
          "androidx.compose.ui.layout.LastBaseline",
          "androidx.compose.ui.layout.Layout",
          "androidx.compose.ui.semantics.contentDescription",
          "androidx.compose.ui.semantics.semantics",
          "androidx.compose.ui.unit.TextUnit",
          "androidx.compose.ui.unit.constrainHeight",
          "androidx.compose.ui.unit.constrainWidth",
          "kotlin.math.ceil",
          "kotlin.math.roundToInt",
        )
    }

  /** A standalone file's shared code, from this module's resources. */
  private fun resource(name: String): String =
    requireNotNull(VariableFontCodegen::class.java.getResource(name)) { "no resource $name" }
      .readText()

  /** Characters per string constant: a class file holds at most 65,535 bytes of UTF-8 in one. */
  private const val CHUNK = 16_000

  private fun escape(s: String): String = buildString {
    for (ch in s) {
      if (ch.code in 0x20..0x7e && ch != '"' && ch != '\\' && ch != '$') append(ch)
      else append("\\u%04x".format(ch.code))
    }
  }
}
