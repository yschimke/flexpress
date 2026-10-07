# Flexpress

Animate variable-font axes — weight, width, slant, roundness, grade — without ever loading or
re-instancing a font: the font's own variation model, evaluated as the text is drawn.

- `flexpress-core`: a small variable-font reader and the precomputed text outline.
- `flexpress-remote`: Remote Compose (`RemoteVariableFontText`).
- `flexpress-compose`: Jetpack Compose UI (`VariableFontText`).
- `flexpress-codegen`: work an outline out at build time so the app needs no font.

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

## Building

```
./gradlew ktfmtFormat test lintDebug
```

Remote Compose is `androidx.compose.remote` 1.0.0-alpha21. Contributor and agent rules are in
[`AGENTS.md`](AGENTS.md).
