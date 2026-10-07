# flexpress-codegen

Works a fixed text's outline out at build time and writes it as Kotlin source: an encoded
`VariableTextOutline` constant and a `@RemoteComposable` that draws it. At run time the app decodes
a string. It never parses the font, lays the text out or ships the font file. The document it makes
is byte for byte the one `RemoteVariableFontText` makes from the font.

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

`generate` returns the same source as a string, for build steps of your own.
