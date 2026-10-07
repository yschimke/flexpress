# Compose UI: variable-font axis animation

`VariableFontText` draws a line of text in a variable font with any of its axes (`wght`, `wdth`,
`slnt`, `ROND`, …) animated, in Jetpack Compose UI, with no typeface instancing, no relayout and no
recomposition per frame.

```kotlin
val font = remember { VariableFont.parse(resources.openRawResource(R.raw.my_font).readBytes()) }
val weight by rememberInfiniteTransition()
  .animateFloat(100f, 900f, infiniteRepeatable(tween(1000), RepeatMode.Reverse))

VariableFontText(
  text = "Hello",
  font = font,
  axes = mapOf("wght" to { weight }), // read while drawing, not while composing
  fontSize = 32.sp,
  color = Color.White,
  location = mapOf("wdth" to 90f), // axes that don't move
)
```

It is the Compose UI counterpart of [`flexpress-remote`](../flexpress-remote)
(`RemoteVariableFontText`), and uses that module's variable-font reader.

## Why

Compose text with an animated `FontVariation.Settings` builds a new `FontFamily` for every distinct
value. Each one is a new typeface instance, loaded and cached by Compose's font resolver (the cache
grows with the number of distinct values), and the text is laid out again, which recomposes and
remeasures whatever reads the state. A continuous animation pays all of that every frame.

## API

```kotlin
@Composable
fun VariableFontText(
  text: String,
  font: VariableFont,
  axes: Map<String, () -> Float>,
  fontSize: TextUnit, // sp
  modifier: Modifier = Modifier,
  color: Color = Color.Unspecified, // black
  location: Map<String, Float> = emptyMap(),
  kerningLocation: Map<String, Float> = location,
)

@Composable
fun VariableFontText(
  outline: VariableTextOutline, // from VariableFont.outline, or VariableTextOutline.decode
  axes: Map<String, () -> Float>,
  fontSize: TextUnit,
  modifier: Modifier = Modifier,
  color: Color = Color.Unspecified,
  contentDescription: String? = null,
)
```

- `axes`: the animated axes by tag, each with a function giving its current value in user units
  (clamped to the axis' range). The functions are called in the draw phase, so a state they read
  invalidates only the draw. Read the animated state inside the lambda (`{ weight }` with a
  delegated `by` property), not before.
- `location`: the axes that do not move. Missing axes take their defaults.
- `kerningLocation`: where the font's `GPOS` pair kerning is read. Kerning is a constant per pair.
  It does not follow the animated axes.
- The box has a fixed size: the widest advance of the line anywhere the animated axes can move, by
  the font's ascender to descender. Animating never reflows. The baseline is at the ascender and is
  reported as `FirstBaseline`/`LastBaseline`, so `Modifier.alignByBaseline()` works.
- The text is exposed to accessibility as its semantics text.

`VariableFont.parse(bytes)` reads the font. It is the same class `RemoteVariableFontText` takes.

The second overload draws a `VariableTextOutline`, the same precomputed outline
`RemoteVariableFontText` uses. Since `VariableTextOutline.decode` reads one from a string generated at
build time, an app can animate a fixed text with no font file and no font parsing at run time:

```kotlin
// HELLO_WGHT: a string constant a build step wrote from font.outline("Hello", listOf("wght")).encode()
val outline = remember { VariableTextOutline.decode(HELLO_WGHT) }
VariableFontText(outline, axes = mapOf("wght" to { weight }), fontSize = 32.sp,
  contentDescription = "Hello")
```

## How

Every outline coordinate of a `glyf`/`gvar` variable font is the font's own linear model,
`default + Σ delta(region) × scalar(region, axes)`, where each region scalar is a product of per-axis
"tents" over the axis values.

1. Once per text, font, set of animated axes, `location` and pixel size (a `remember`),
   `VariableFont.outline` works the line out as a `VariableTextOutline`. The fixed axes are folded
   into the coefficients, each tent becomes clamped ramps of its axis' user value (`avar`
   included), and the outline is simplified wherever that cannot move an edge by more than a
   sixteenth of a pixel at the size drawn. It is held in one of two exact forms: key outlines when
   one axis is animated and its tents never overlap (the default outline plus each tent's scalar
   times its delta), otherwise a constant and (region, coefficient) terms per coordinate.
2. A `VariableTextOutlineEvaluator` (in `flexpress-core`, behind `InternalFlexpressApi`)
   flattens that into arrays once.
3. Per frame, in the draw phase, the axis lambdas are read into a reused `FloatArray`, the tents,
   region scalars and coordinates are computed into a second reused `FloatArray`, and a reused
   Compose `Path` is rewound, rebuilt from the verbs and filled. Nothing is allocated, and nothing is
   rebuilt when the values have not changed since the last draw.

The outline is the font's own at every value, not an approximation.

## Tests

- `OutlineExactnessTest`: the unsimplified outline evaluated at random axis values, one axis and
  three axes at a time, in six fonts, has every coordinate within 0.01 font units (plus float
  rounding on coordinates in the tens of thousands) of the font instanced at that location, in both
  key-outline and form shapes. An outline decoded from its string evaluates identically.
- `RenderFidelityTest`: against the platform's own text in the same font and variation settings
  (`BasicText`, `FontVariation.Settings`, `TextMotion.Animated`), at each tested axis' extremes and
  at combinations:
  - The platform's own glyph outlines (`Paint.getTextPath`) filled alike differ by at most 0.02 of
    the ink, and 0.04 at Google Sans Flex's hairline `wght` 1. An instance 3% off (`wght` 400 drawn
    at 430, `wdth` 100 at 104) fails this.
  - Each glyph's origin along the line is where the platform's layout puts it (kerning included),
    to within 0.04 px.
  - The rendered pixels agree to within the platform's text gamma, which gives platform glyphs
    3–15% more ink than a filled path at the same outline (22% at a hairline); ink centroids agree
    to under half a pixel (about a pixel at the hairline, where the gamma brightens half-covered
    stems more than pixel-aligned ones).
- `AnimationTest`: changing an axis' state redraws with no recomposition (neither the caller nor
  `VariableFontText` itself) and no relayout, and the ink changes. The box and baseline match the
  documented sizes.
- `FrameCostBenchmarkTest`: skipped unless `COMPOSE_VF_BENCH=1`.

## Cost

`COMPOSE_VF_BENCH=1 ./gradlew :flexpress-compose:testDebugUnitTest --tests '*FrameCostBenchmarkTest*'`

"Hamburgefonstiv", Roboto Flex, 28 sp at 2x, `wght` through 60 distinct values in Robolectric
(JVM, CPU raster). A frame is: set the value, let Compose do its work, draw the window into a
bitmap. Only the relative numbers mean anything.

| Composable | Mean ms/frame (new values) | Median ms/frame (new values) | Median ms/frame (repeated values) |
|---|---|---|---|
| `VariableFontText` | 3.94 | 3.48 | 2.56 |
| `BasicText`, new `FontFamily` per value | 10.59 | 7.25 | 6.13 |
| (no change: the harness alone) | 1.22 | 0.93 | 0.93 |

Less the harness, a frame of `VariableFontText` costs about a third of `BasicText`'s, and it
recomposes and relayouts nothing. Runs vary with machine load (one run under load measured 8.4 vs
21.4 ms median), but the ratio holds at about 2.5–3×.

| Outline | Keys | Coordinates | Terms | µs per evaluate | µs per evaluate + path rebuild |
|---|---|---|---|---|---|
| keys | yes | 1056 | 0 | 0.3 | 28.6 |
| forms | no | 1056 | 1311 | 5.4 | 27.8 |
| simplified at 56 px, as drawn | yes | 1056 | 0 | 0.2 | 23.2 |

Evaluating the model is a few microseconds at most. Most of the per-frame cost is rebuilding and
filling the path.

## Limits

- One line, left-aligned in its box. No ligatures or complex-script shaping (advances and `GPOS`
  pair kerning only), no font fallback for characters the font lacks, no `TextStyle`.
- TrueType (`glyf`/`gvar`) variable fonts only. CFF2 fonts are not supported. Composite glyphs that
  place a component by point matching throw.
- Glyph substitution through `GSUB` feature variations (Recursive's `CRSV`, Fraunces' `WONK`) is
  not applied.
- Kerning is fixed at `kerningLocation` and does not follow the animated axes.
- Glyphs that reach outside the box (a slanted overhang, a tall accent) are drawn, not clipped,
  and are not counted in the size.
- The outline grows with the regions in the text, and fastest with the number of axes animated
  together. Animate only the axes that move and fix the rest with `location`.
- Changing `text`, `font`, the set of animated tags, `location`, `kerningLocation` or the font
  size in pixels rebuilds the outline, which costs about as much as laying out the text once.
  Animating values does not. Animate size with a `graphicsLayer` scale instead of `fontSize`.
