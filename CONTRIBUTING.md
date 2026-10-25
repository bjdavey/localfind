# Contributing to LocalFind

Thanks for helping. LocalFind is a small, focused project, so a few simple rules keep it that way.

## Before you start

- Read [docs/PRD.md](docs/PRD.md) (what the app does) and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (how it is built).
- Open an issue first for anything bigger than a small fix, so we can agree on the approach.
- Check [TODO.md](TODO.md): planned work and known bugs are listed there.

## Rules

- **Keep it simple.** Choose the simplest solution that works. No extra layers, abstractions or "future-proofing".
- **Stay offline.** No network calls, no `INTERNET` permission, no analytics or crash-reporting SDKs. This is the core promise of the app.
- **Stay in scope.** Features outside the PRD are not accepted. Put ideas in an issue.
- **No new dependencies** without discussing them in an issue first.
- **One change per pull request.** Keep files focused (ideally under ~400-500 lines).
- **Comment the why.** Explain intent and non-obvious details in code and tests.
- **Keep `core/` pure.** `android/app/src/main/java/.../core` may not import `android.*`, `androidx.*`, MediaPipe or PDFBox (`CoreIsPureTest` enforces this).
- **Update the docs** when behaviour changes.

## Build and test

```powershell
cd android
.\gradlew test          # unit tests (add one for any pure logic you change)
.\gradlew assembleDebug # build
```

Test your change on an emulator or device. On phones with an NPU, install only with `android\deploy-bundle.ps1 -DeviceId <serial>`, never plain `adb install`.

## Pull requests

- Describe what changed and why, and how you tested it.
- Make sure unit tests and the debug build pass.
- By contributing you agree your work is licensed under the [Apache License 2.0](LICENSE).
