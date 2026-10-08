# flexpress-codegen

Works a fixed text's outline out at build time and writes it as Kotlin source: an encoded
`VariableTextOutline` constant and a composable that draws it, a `@RemoteComposable` for Remote
Compose or a Compose UI `@Composable`. At run time the app decodes a string. It never parses the
font, lays the text out or ships the font file. What it draws is exactly what the library draws from
the font: byte for byte the same Remote Compose document, pixel for pixel the same Compose UI text.

Use it for text known at build time: a label, a brand word, a title. Text that changes at run time
(a clock, a name) needs the font when the document is made, through `rememberVariableFontGlyphs`
and the `RemoteString` overload.

## The recipe: a golden test

There is no Gradle plugin. The app keeps the generated files in its sources, and a unit test
regenerates them on request and fails when one is stale, so they are reviewed like any other code
and never drift from the library.

```kotlin
// build.gradle.kts
dependencies {
  implementation("ee.schimke.flexpress:flexpress-remote:<version>")
  testImplementation("ee.schimke.flexpress:flexpress-codegen:<version>")
}
```

```kotlin
class GeneratedTextTest {
  @Test
  fun generatedTextIsUpToDate() {
    val update = System.getenv("CODEGEN_WRITE") == "1"
    val font = VariableFont.parse(File("src/main/res/font/roboto_flex.ttf").readBytes())
    val stale =
      listOf(
          VariableFontCodegen.write(
            sourceRoot = File("src/main/kotlin"),
            packageName = "com.example.generated",
            functionName = "BrandTitle",
            font = font,
            fontName = "Roboto Flex",
            text = "Hamburg",
            axes = listOf("wght"),
            update = update,
          )
        )
        .count { upToDate -> !upToDate }
    if (!update) assertEquals("stale; run with CODEGEN_WRITE=1", 0, stale)
  }
}
```

`CODEGEN_WRITE=1 ./gradlew testDebugUnitTest --tests '*GeneratedTextTest*'` writes
`src/main/kotlin/com/example/generated/BrandTitle.kt`. Afterwards the same test, run without the
variable, fails as soon as the text, the font or the library's encoding changes. The app draws it
with:

```kotlin
BrandTitle(wght = weight, fontSize = 32.rdp, color = Color.White.rc)
```

`write` returns whether the file already held that source. Other parameters:

- `location`: values for the axes that don't animate.
- `pixelSize` / `tolerancePixels`: simplify the outline for the size it will be drawn at, moving
  no edge by more than the tolerance (a sixteenth of a pixel by default). Leave `pixelSize` out for
  an outline that is exact at any size.
- `fileHeader`: a comment to start the file with, such as your license header.

- `target`: `CodegenTarget.RemoteCompose` (the default) or `CodegenTarget.ComposeUi`. For Compose
  UI each axis is a `() -> Float`, read while drawing, and the composable draws through
  `flexpress-compose`'s `VariableFontText`.
- `standalone`: see below.

`generate` returns the same source as a string, for build steps of your own.

## Standalone: one file, no flexpress

By default the generated file draws through the library, so the app depends on `flexpress-remote`
or `flexpress-compose`. With `standalone = true` it carries its own decoder and drawing instead and
depends only on Remote Compose or Compose UI: one file to drop into any project, such as code a
design tool exports.

```kotlin
VariableFontCodegen.write(
  sourceRoot = File("src/main/kotlin"),
  packageName = "com.example.generated",
  functionName = "BrandTitle",
  font = font,
  fontName = "Roboto Flex",
  text = "Hamburg",
  axes = listOf("wght"),
  target = CodegenTarget.ComposeUi,
  standalone = true,
)
```

The composable has the same signature either way. Everything else in the file is private to it,
so several generated files can share a package. A standalone file is about 9 KB of code larger than
one that uses the library. Its drawing is the library's, copied: the tests check it writes the same
Remote Compose document byte for byte and draws the same Compose UI pixels.
