# CLAUDE.md

Guidance for Claude Code in this repository. Keep it short; details live in `docs/`.

## Product in one line

A smartwatch records continuously while worn → chunks go to the paired phone over a direct link → the phone transcribes **on-device** → data accumulates **locally**. Current goal and priorities: `docs/03-roadmap.md` and the v0.1 milestone (P0 issues).

## Non-negotiables

- **Local only.** Never upload recordings, transcripts or any user data. The only network use is downloading models. Watch→phone transfer must use direct connections only (Wear OS: `MessageClient`/`ChannelClient` to `Node.isNearby`; never `DataClient`). Keep `allowBackup=false` / backup exclusions.
- **Wire contract.** `shared/core-protocol` is shared by Kotlin (phone, Wear OS) and Swift (watchOS: `iosApp/Shared/ChunkProtocol.swift`). Any change must update both sides **and** `shared/core-protocol/testdata/*.json` (tested on both sides). Times are epoch ms.
- **DB migrations are append-only** (`shared/core-db/.../Schema.kt`). Audio paths in the DB are relative to the audio root.
- AI summaries/chat are out of scope until the core goal is met (`P2` label).

## Repo map

| Path | What |
| --- | --- |
| `shared/core-*` | KMP logic: `protocol` (manifest/ACK), `db` (bundled SQLite + FTS5), `pipeline` (Ingestor, TranscriptionPipeline, VAD settings), `platform` (expect/actual) |
| `composeApp` | Shared phone UI (Compose MP) + `AppServices` contract; `iosMain` has the iOS graph/bridge |
| `androidApp` / `wearApp` | Android phone (receiver, sherpa-onnx ASR, WorkManager) / Wear OS recorder |
| `iosApp` | XcodeGen project (`project.yml`): iOS host + watchOS recorder + Swift protocol tests |
| `tools/harness` | Device-free E2E on Linux: watch simulator → ingest → sherpa-onnx → SQLite |
| `tools/roadmap-view` | Builds the issue dashboard (`python3 tools/roadmap-view/build.py out.html`) |
| `docs/` | Research, architecture, roadmap, dev environment, `impl/` per platform, `spikes/` |

## Commands

```bash
./gradlew --max-workers=2 jvmTest testAndroidHostTest          # shared + UI logic tests
./gradlew :androidApp:assembleDebug :wearApp:assembleDebug      # apps
./gradlew :androidApp:lintDebug :wearApp:lintDebug
tools/harness/download-models.sh && ./gradlew :tools:harness:test   # real Japanese ASR E2E
# Type-check iOS Kotlin on Linux (no linking) before spending macOS CI minutes:
./gradlew compileKotlinIosSimulatorArm64 -Pkotlin.native.enableKlibsCrossCompilation=true
```

iOS/watchOS apps only build on macOS (CI `ios.yml`, Xcode 26.x): `cd iosApp && xcodegen generate`.

## Environment gotchas (cloud Linux container)

- No Xcode, no Android emulator (no KVM): rely on unit tests, the harness and CI (`emulator.yml`, `ios.yml`).
- Maven Central may return HTTP 429; a local init script routes to a mirror. sherpa-onnx resolves from JitPack only (`exclusiveContent` in `settings.gradle.kts`).
- Several Gradle builds may run in parallel (agents): use `--max-workers=2`, and **never run `./gradlew --stop`** (it kills other builds).
- `gh` works with REST only (`gh api repos/...`); GraphQL is blocked.
- macOS CI minutes are billed 10x: push iOS changes after the Linux cross-compile check passes.

## Conventions

- Code comments and identifiers in English; KDoc on public types, no noise comments. Docs and user-facing strings in **Japanese**.
- Match surrounding style; keep pure logic in plain functions with unit tests (`commonTest`/`jvmTest`).
- Platform specifics go behind interfaces (`Transcriber`, `AppServices`, expect/actual), not `if (platform)` branches.

## Workflow

- Branch: `main`. Commit small, logical units and **push regularly**; keep CI green (fix red CI before new work).
- With every change, **update the docs** it affects (`docs/impl/*`, `docs/02`, `docs/03` progress table, README) and the **GitHub issues**: progress comment, labels (`P0/P1/P2`, `in-progress`, `needs-device`), close with a summary when done, file new issues for problems found. Regenerate the dashboard afterwards.
- Parallelize: hand independent work to sub-agents (git worktrees) and keep working while they and long commands run in the background — don't sit idle waiting.
- Talk to the user in **Japanese**.
