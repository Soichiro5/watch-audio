# CLAUDE.md

Guidance for Claude Code in this repository. Keep it short; details live in `docs/`.

> **IMPORTANT: Every message to the user — reports, progress updates, proposals, questions and answers — must be written in Japanese.** This includes one-line status notes between tool calls. Only code, code comments, commit messages and tool inputs may be in English.

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

- **`main` is the official branch**: CI and CD (`deliver.yml`) run on it. **Never commit or push to `main` directly.**
- **Always develop on a separate branch and open a PR** linked to its issue: branch per issue from `main` (`feat/<issue>-<slug>`, `fix/…`, `docs/…`), commit small units and push the branch regularly, PR body says `Closes #<n>` (or `Refs #<n>` for partial work). CI runs on the PR; merge only when it is green. Claude may merge its own PRs once CI is green (the user delegated merging on 2026-10-10 until they say otherwise). Keep CI green (fix red CI before new work).
- Sub-agent branches (worktrees) are pushed as such a feature branch and merged through a PR too.
- **Issue completion criteria**: every issue has a 「完了条件」 section with checkable conditions and how each is verified (`.github/ISSUE_TEMPLATE/task.md`). If the criteria change, edit the issue body and leave a comment explaining why.
- **Closing an issue**: verify every completion condition with evidence (test/CI run links, commit/PR, measurements, screenshots), post that evidence as a comment mapping each condition to its proof, then close. Conditions that need a real device stay open with `needs-device`; never close on "implemented" alone. A PR's `Closes #n` is only used when the PR itself satisfies all conditions; otherwise use `Refs #n`.
- With every change, **update the docs** it affects (`docs/impl/*`, `docs/02`, `docs/03` progress table, README) and the **GitHub issues**: progress comment, labels (`P0/P1/P2`, `in-progress`, `needs-device`), close with a summary when done, file new issues for problems found. Regenerate the dashboard afterwards.
- Parallelize: hand independent work to sub-agents (git worktrees) and keep working while they and long commands run in the background — don't sit idle waiting.
- Talk to the user in **Japanese** (see the rule at the top).
