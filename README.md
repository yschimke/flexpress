# Flexpress

Animate variable-font axes — weight, width, slant, roundness, grade — without ever loading or
re-instancing a font: the font's own variation model, evaluated as the text is drawn.

- `flexpress-core`: a small variable-font reader and the precomputed text outline.
- `flexpress-remote`: Remote Compose (`RemoteVariableFontText`).
- `flexpress-compose`: Jetpack Compose UI (`VariableFontText`).
- `flexpress-codegen`: work an outline out at build time so the app needs no font.
