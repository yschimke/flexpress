# AGENTS.md

The instructions every coding agent working on this repository reads. Project detail is in
[`README.md`](README.md) and each module's README.

## What this project is

Flexpress animates variable-font axes without loading or re-instancing a font: it reads the font's
own variation model and evaluates it as the text is drawn.

| Module | What |
| --- | --- |
| `flexpress-core` | JVM: the variable-font reader, the variation model and `VariableTextOutline` |
| `flexpress-remote` | Remote Compose: `RemoteVariableFontText` |
| `flexpress-compose` | Compose UI: `VariableFontText` |
| `flexpress-codegen` | JVM: generates an outline as Kotlin source at build time |
| `fonts/` | The OFL test fonts shared by every module's previews and tests |

Declarations shared between modules but not meant for apps are public behind
`@InternalFlexpressApi`, which every module here opts into from the root build script.

## Rules

- **No agent attribution in git history or PR text.** Commits are authored and committed by the
  human; no `Co-authored-by:` trailer naming an agent, no agent identity as author or committer, in
  commits or in the PR title and body.
- **Branch names are `agent/…`.** Never `claude/…`, `codex/…` or another agent prefix.
- **Conventional commits** for PR titles and commit subjects (`feat:`, `fix:`, `docs:`, …). PRs are
  squash-merged, so the title is the commit. The `PR Title` workflow checks it.
- **Run the formatter before committing.** CI runs `./gradlew ktfmtCheck`; run
  `./gradlew ktfmtFormat` first.
- **The public API is tracked.** A change to it updates the module's `api/current.api`:
  `./gradlew metalavaGenerateSignature` (core) or `metalavaGenerateSignatureRelease` (Android
  modules). CI runs `metalavaCheckCompatibility`.
- **Regenerate the generated previews** after changing the outline encoding or the code generator:
  `CODEGEN_WRITE=1 ./gradlew :flexpress-remote:testDebugUnitTest --tests '*VariableFontCodegenTest*'`.
- **A change to what is drawn carries before/after renders** embedded in the PR from a GitHub-hosted
  origin (a committed PNG at a commit-pinned `raw.githubusercontent.com` URL).
- **Re-check PR state before every push**; if the PR has merged, branch fresh from `origin/main`.
- **Don't merge your own PR.**

## Running Gradle

Wrap Gradle in [`build-brief`](https://bb.staticvar.dev) when it is installed
(`build-brief ./gradlew test`): it keeps the full log on disk and prints the failed tasks and tests.
