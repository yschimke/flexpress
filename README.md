# Flexpress

Animate variable-font axes — weight, width, slant, roundness, grade — without ever loading or
re-instancing a font: the font's own variation model, evaluated as the text is drawn.

- `flexpress-core`: a small variable-font reader and the precomputed text outline.
- `flexpress-remote`: Remote Compose (`RemoteVariableFontText`).
- `flexpress-compose`: Jetpack Compose UI (`VariableFontText`).
- `flexpress-codegen`: work an outline out at build time so the app needs no font
  ([recipe](flexpress-codegen/README.md)).

```kotlin
// Remote Compose: the axes are RemoteFloats, evaluated by the player.
RemoteVariableFontText("Hello", font, mapOf("wght" to weight), fontSize = 32.rdp)

// Compose UI: the axes are read at draw time, so animating them never recomposes.
VariableFontText("Hello", font, mapOf("wght" to { weight.value }), fontSize = 32.sp)
```

Module READMEs: [`flexpress-remote`](flexpress-remote/README.md) (how the variation model becomes
Remote Compose expressions, the players it is checked against, document sizes, the sizzle reel) and
[`flexpress-compose`](flexpress-compose/README.md). The test fonts and their licences are described
in [`fonts/README.md`](fonts/README.md).

## Limitations

Flexpress draws one line of text as a path from one font's outlines, placing glyphs by their
advances and the font's pair kerning. That is what lets the axes animate without the player ever
loading a font, and it is also what it gives up against platform text. The animations below are the
`Limitation*Preview`s in `flexpress-remote`: Compose `Text` on top, re-instancing its font with new
variation settings on every frame, and `RemoteVariableFontText` below, at the same animated weight.
The preview workflow renders them on every pull request, so a change to any of these behaviours
shows up in its diff.

### No ligatures or contextual forms

Glyphs come from `cmap` one character at a time. `GSUB` is not applied, so ligatures (`ffi`, `fl`),
contextual alternates and stylistic sets are not used.

![Ligatures: Compose joins "ffi" and "fl"; flexpress draws separate letters](docs/limitations/ligatures.gif)

### No font fallback

Every character is drawn from the one font. A character the font lacks is drawn as its `.notdef`
glyph, usually an empty box, where Compose would fall back to a system font. Subset fonts make this
easy to hit: choose the font's character coverage for the text you will draw.

![Font fallback: Compose draws the arrow and the kanji from system fonts; flexpress draws boxes](docs/limitations/font-fallback.gif)

### No complex-script shaping

There is no shaping engine: no right-to-left layout, no joining forms, no reordering and no mark
positioning (`GPOS` is read for pair kerning only). Arabic, Indic and similar scripts do not render
correctly, even in a font that covers them.

![Complex scripts: Compose shapes Arabic right to left; flexpress cannot](docs/limitations/complex-script.gif)

### One line, no wrapping

The text is a single line, as wide as its widest advance over the animated range. Nothing wraps, and
a narrower container clips it. Break lines yourself and draw one `RemoteVariableFontText` per line.

![Single line: Compose wraps; flexpress clips](docs/limitations/single-line.gif)

### `RemoteString` text: Basic Multilingual Plane only

Text that changes on the player (the `RemoteString` overload with `rememberVariableFontGlyphs`) is
split by the player into UTF-16 units. A character outside the Basic Multilingual Plane, such as most
emoji, is a surrogate pair that no glyph can match, so `rememberVariableFontGlyphs` rejects one in
`characters` with an error naming it. The `String` overloads have no such limit beyond the font's own
coverage. The `RemoteString` path also needs every possible character listed up front, and a
`maxLength`.

### Font format

TrueType (`glyf`) outlines only, not CFF2. Composite glyphs must place their components by offset; a
composite that anchors a component by point matching is rejected when it is drawn.

### Accessibility and rasterization

The text is drawn as a filled path, so there is no accessible text unless you add a content
description, and edges are anti-aliased as a path fill, not by the platform's text rasterizer: light
weights at small sizes look lighter than platform text, which thickens thin stems.

### Document size grows with animated axes

This is a cost rather than a limit. When exactly one axis is animated and its variation regions do
not overlap, as is usual, the document holds a few whole key outlines and draws a single tween
between the default outline and one key: small, and cheap for the player. With two or more animated
axes, the regions multiply, so each outline coordinate becomes an expression over all the axes
instead, and the document grows with every axis added. Animate only the axes that move and hold the
rest with `location`. See [How](flexpress-remote/README.md#how).

## Using

Published to Maven Central as `ee.schimke.flexpress:flexpress-remote`, `flexpress-compose`,
`flexpress-core` and `flexpress-codegen`, all at one version.

## Building

```
./gradlew ktfmtFormat test lintDebug
./gradlew composePreviewRender   # the debug previews, as PNGs and GIFs
```

Remote Compose is `androidx.compose.remote` 1.0.0-alpha21. Contributor and agent rules are in
[`AGENTS.md`](AGENTS.md).
