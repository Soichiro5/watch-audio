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
- **Public repo: no secrets.** The repository and issues are public. Never commit or write secrets, credentials, API keys, signing keys or personal data into the repo, issues, PRs or CI logs; use GitHub Actions secrets and gitignored local files (`local.properties`). App-embedded keys are extractable, so no provider API keys in the app (BYOK keys stay in Keystore/Keychain on the device).

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
# Type-check iOS Kotlin on Linux (no linking) before pushing iOS changes:
./gradlew compileKotlinIosSimulatorArm64 -Pkotlin.native.enableKlibsCrossCompilation=true
```

iOS/watchOS apps only build on macOS (CI `ios.yml`, Xcode 26.x): `cd iosApp && xcodegen generate`.

## Environment gotchas (cloud Linux container)

- No Xcode, no Android emulator (no KVM): rely on unit tests, the harness and CI (`emulator.yml`, `ios.yml`).
- Maven Central may return HTTP 429; a local init script routes to a mirror. sherpa-onnx resolves from JitPack only (`exclusiveContent` in `settings.gradle.kts`).
- Several Gradle builds may run in parallel (agents): use `--max-workers=2`, and **never run `./gradlew --stop`** (it kills other builds).
- `gh` works with REST only (`gh api repos/...`); GraphQL is blocked.
- The repo is public, so standard GitHub-hosted runners (macOS included) are free; still keep macOS runs purposeful (slow queue): push iOS changes after the Linux cross-compile check passes.

## Conventions

- Code comments and identifiers in English; KDoc on public types, no noise comments. Docs and user-facing strings in **Japanese**.
- Match surrounding style; keep pure logic in plain functions with unit tests (`commonTest`/`jvmTest`).
- Platform specifics go behind interfaces (`Transcriber`, `AppServices`, expect/actual), not `if (platform)` branches.

## Workflow

- **`main` is the official branch**: CI and CD (`deliver.yml`) run on it. **Never commit or push to `main` directly.**
- **Always develop on a separate branch and open a PR** linked to its issue: branch per issue from `main` (`feat/<issue>-<slug>`, `fix/…`, `docs/…`), commit small units and push the branch regularly, PR body says `Closes #<n>` (or `Refs #<n>` for partial work). CI runs on the PR; merge only when it is green. Claude may merge its own PRs once CI is green (the user delegated merging on 2026-10-10 until they say otherwise). Keep CI green (fix red CI before new work).
- **CI roles while many PRs run in parallel:** PR CI is the merge gate; CI on `main` (batched: a newer merge cancels the older run) catches breakage from PRs merged side by side; `deliver.yml` (CD) only builds from `main`. A green PR may be merged while behind `main`; first compare what landed on `main` since its CI ran (`gh api repos/<o>/<r>/compare/<head>...main`) and merge `main` in and re-run CI only if that overlaps the PR (same modules, `shared/core-*`, Gradle/build-logic, workflows it relies on). Red CI on `main` is fixed before new merges. Requiring up-to-date PRs (or a merge queue) and CD-only `main` is the end state for #85, not for now.
- Sub-agent branches (worktrees) are pushed as such a feature branch and merged through a PR too.
- Issues labelled `dev-flow` (process, CI, docs/tooling that agents rely on) are handled before feature work.
- **Issue completion criteria**: every issue has a 「完了条件」 section with checkable conditions and how each is verified (`.github/ISSUE_TEMPLATE/task.md`). If the criteria change, edit the issue body and leave a comment explaining why.
- **Closing an issue**: verify every completion condition with evidence (test/CI run links, commit/PR, measurements, screenshots), post that evidence as a comment mapping each condition to its proof, then close. Prefer criteria verifiable in the dev environment (CI, unit/UI tests, the harness, emulator/simulator). Conditions that can only be verified on a real device go into the per-platform 「実機検証」 issues (label `needs-device`) instead of blocking the implementation issue; only fundamentally device-bound work (e.g. battery measurement) stays `needs-device` itself. Never close on "implemented" alone. A PR's `Closes #n` is only used when the PR itself satisfies all conditions; otherwise use `Refs #n`.
- With every change, **update the docs** it affects (`docs/impl/*`, `docs/02`, `docs/03` progress table, README) and the **GitHub issues**: progress comment, labels (`P0/P1/P2`, `in-progress`, `needs-device`), close with a summary when done, file new issues for problems found. Regenerate the dashboard afterwards.
- **Parallelize:**
  - Run Gradle builds/tests, the harness, evaluations and model downloads in the background (Bash `run_in_background`) and keep working; read the result when the completion notice arrives. Don't block the conversation on them.
  - Hand multi-minute investigation, implementation or evidence gathering to sub-agents (worktrees); the lead keeps PRs, issues and user reports moving.
  - Wait for CI through PR activity subscriptions / scheduled check-ins, never by polling or `sleep`.
  - The repo is public, so CI minutes are free: push and let CI run the heavy checks (emulator, iOS simulator); before pushing, run only the fast local checks for the touched modules (unit tests, iOS cross-compile).
  - Never leave the user waiting on a long command; reply and continue in the background.
- **Lead vs. sub-agents** — delegate only self-contained, multi-minute work; the lead owns everything that needs judgement or the whole picture:
  - Lead (do it yourself): priorities and planning; reviewing every sub-agent diff before it is merged (correctness, scope, docs/tests updated, no secrets); merge decisions; CI triage and small CI/config fixes; merge-conflict resolution; posting evidence and closing issues; labels, the docs/03 progress table and the dashboard (after every merge or issue state change); user reports and questions.
  - Sub-agents: multi-file implementation, long investigations/measurements (evaluations, benchmarks), evidence gathering that needs many CI iterations, bulk mechanical edits. Give them a branch, scope, verification commands and "no merge / no issue edits" unless told otherwise.
- Talk to the user in **Japanese** (see the rule at the top).
