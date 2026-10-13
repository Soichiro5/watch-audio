# CLAUDE.md

Guidance for Claude Code in this repository. Keep it short; details live in `docs/`.

> **IMPORTANT: Every message to the user — reports, progress updates, proposals, questions and answers — must be written in Japanese.** This includes one-line status notes between tool calls. Only code, code comments, commit messages and tool inputs may be in English.

## Product in one line

A smartwatch records continuously while worn → chunks go to the paired phone over a direct link → the phone transcribes **on-device** → data accumulates **locally**. Current goal and priorities: `docs/product/03-roadmap.md` (use cases UC1–UC10) and the Milestone 「v0.1 実機で 1 日使える」.

## Non-negotiables

- **Local only.** Never send recordings, transcripts, summaries or any other private user content off the device (the owner's line, confirmed 2026-10-13). App network use is limited to downloading models; when the user turns agent access on, a LAN connection to the user's own paired PC (`docs/research/agent-access.md`); and, only if the owner approves a specific SDK, its diagnostics/usage logs that carry no user content (allow-listed with the reason in `tools/privacy-check` and declared in the privacy policy and store forms). Watch→phone transfer must use direct connections only (Wear OS: `MessageClient`/`ChannelClient` to `Node.isNearby`; never `DataClient`). Keep `allowBackup=false` / backup exclusions.
- **Wire contract.** `shared/protocol` is shared by Kotlin (phone, Wear OS) and Swift (watchOS: `apps/ios/SharedSwift/ChunkProtocol.swift`). Any change must update both sides **and** `shared/protocol/testdata/*.json` (tested on both sides). Times are epoch ms. The same holds for the agent wire format: `shared/agent` (Kotlin) ⇄ `tools/agent-bridge` (TypeScript) ⇄ `shared/agent/testdata/*.json`.
- **DB migrations are append-only** (`shared/db/.../Schema.kt`). Audio paths in the DB are relative to the audio root.
- AI features (UC9) start **on-device** (owner decision 2026-10-13, #39, `docs/research/on-device-llm.md`): a 「この日を要約」 button that makes hour summaries only on request; iOS uses Foundation Models' on-device `SystemLanguageModel` (never Private Cloud Compute). Cloud LLMs (BYOK, #26) and chat (#31) stay `P2`.
- **Public repo: no secrets.** The repository and issues are public. Never commit or write secrets, credentials, API keys, signing keys or personal data into the repo, issues, PRs or CI logs; use GitHub Actions secrets and gitignored local files (`local.properties`). App-embedded keys are extractable, so no provider API keys in the app (BYOK keys stay in Keystore/Keychain on the device).

## Repo map

| Path | What |
| --- | --- |
| `shared/*` | KMP logic: `protocol` (manifest/ACK), `db` (bundled SQLite + FTS5), `pipeline` (Ingestor, TranscriptionPipeline, VAD settings), `platform` (expect/actual) |
| `shared/agent` | Agent access (UC11): read-only MCP tool logic over LocalStore, scope and amount limits, access log, phone ⇄ bridge wire format (`testdata/` shared with `tools/agent-bridge`) |
| `shared/ort` | JVM/Android: ONNX Runtime's C API through JNA for the embedding model of semantic search (reuses sherpa-onnx's `libonnxruntime.so`) |
| `shared/testing` | Test-only helpers shared by the shared modules' tests (`TempDir`, `testManifest`, `testAudio`) |
| `shared/ui` | Shared phone UI (Compose MP) + `AppServices` contract; `iosMain` has the iOS graph/bridge (framework `ComposeApp`) |
| `apps/android` / `apps/wear` | Android phone (receiver, sherpa-onnx ASR, WorkManager) / Wear OS recorder |
| `apps/ios` | XcodeGen project (`project.yml`): `App` (iOS host), `Watch` + `WatchWidget` (watchOS recorder, complication), `SharedSwift`, `Tests/*` |
| `tools/harness` | Device-free E2E on Linux: watch simulator → ingest → sherpa-onnx → SQLite |
| `tools/agent-bridge` | `watch-audio-mcp`: the PC bridge CLI (TypeScript/Node, stdio MCP server; the paired phone connects to it over mutual TLS). `tools/agent-fake-phone`: JVM phone for its E2E |
| `tools/roadmap-view` | Builds the issue dashboard (`python3 tools/roadmap-view/build.py out.html`) |
| `tools/check-links` | Markdown link check (CI `links.yml`) |
| `research` | Python benchmarks from the spikes (ASR, embeddings) |
| `docs/` | `product` (research, roadmap), `architecture`, `platforms` (per platform), `dev` (environment, CI, test audio), `research` (spike reports) |

## Commands

```bash
./gradlew --max-workers=2 jvmTest testAndroidHostTest          # shared + UI logic tests
./gradlew --max-workers=2 :shared:ort:test                      # ONNX Runtime via JNA (embedding)
./gradlew :apps:android:assembleDebug :apps:wear:assembleDebug      # apps
./gradlew :apps:android:lintDebug :apps:wear:lintDebug
tools/harness/download-models.sh && ./gradlew :tools:harness:test   # real Japanese ASR E2E
(cd tools/agent-bridge && npm ci --ignore-scripts && npm run lint && npm test)   # agent bridge CLI
./gradlew --max-workers=2 :tools:agent-fake-phone:installDist && FAKE_PHONE=$PWD/tools/agent-fake-phone/build/install/agent-fake-phone/bin/agent-fake-phone npm --prefix tools/agent-bridge run test:e2e   # bridge E2E
# Type-check iOS Kotlin on Linux (no linking) before pushing iOS changes:
./gradlew compileKotlinIosSimulatorArm64 -Pkotlin.native.enableKlibsCrossCompilation=true
```

iOS/watchOS apps only build on macOS (CI `ios.yml`, Xcode 26.x): `cd apps/ios && xcodegen generate`.

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
- **CI roles while many PRs run in parallel:** PR CI is the merge gate; CI on `main` (batched: a newer merge cancels the older run) catches breakage from PRs merged side by side; `deliver.yml` (CD) only builds from `main`. A green PR may be merged while behind `main`; first compare what landed on `main` since its CI ran (`gh api repos/<o>/<r>/compare/<head>...main`) and merge `main` in and re-run CI only if that overlaps the PR (same modules, `shared/*`, Gradle/build-logic, workflows it relies on). Red CI on `main` is fixed before new merges. Requiring up-to-date PRs (or a merge queue) and CD-only `main` is the end state for #85, not for now.
- Sub-agent branches (worktrees) are pushed as such a feature branch and merged through a PR too.
- Issues labelled `dev-flow` (process, CI, docs/tooling that agents rely on) are handled before feature work.
- **Use cases and releases**: a use case is a parent issue (label `usecase`, user flow + user-visible completion criteria); work issues are its sub-issues; a release is a Milestone set on the use case and its work issues (`docs/product/03` 「ユースケースとリリース」). Every work issue starts with a 「目的」 sentence (why, from the user's view) and is attached to its use case; dev-flow issues get the `dev-flow` label instead. Keep `docs/product/03` in sync when use cases change.
- **Issue completion criteria**: every issue has a 「完了条件」 section with checkable conditions and how each is verified (`.github/ISSUE_TEMPLATE/task.md`). If the criteria change, edit the issue body and leave a comment explaining why.
- **Closing an issue**: verify every completion condition with evidence (test/CI run links, commit/PR, measurements, screenshots), post that evidence as a comment mapping each condition to its proof, then close. Prefer criteria verifiable in the dev environment (CI, unit/UI tests, the harness, emulator/simulator). Conditions that can only be verified on a real device go into the per-platform 「実機検証」 issues (label `needs-device`) instead of blocking the implementation issue; only fundamentally device-bound work (e.g. battery measurement) stays `needs-device` itself. Never close on "implemented" alone. A PR's `Closes #n` is only used when the PR itself satisfies all conditions; otherwise use `Refs #n`.
- With every change, **update the docs** it affects (`docs/platforms/*`, `docs/architecture/02`, `docs/product/03` progress table, README) and the **GitHub issues**: progress comment, labels (`P0/P1/P2`, `in-progress`, `needs-device`), close with a summary when done, file new issues for problems found. Regenerate the dashboard afterwards.
- **Verify before a PR**: open or update a PR only after the change has been shown to do what it is for, as far as this machine can show it. Before every push that changes code, build files, workflows or scripts, run the local checks for what it touches and fix failures first:
  - compile + unit tests of the touched modules (`jvmTest`, `testAndroidHostTest`, `:<app>:testDebugUnitTest`), lint for app changes, `assembleDebug` of affected apps, the iOS cross-compile when shared/iOS Kotlin changes, the harness for pipeline/ASR changes, `python3 tools/privacy-check/check.py` and `python3 tools/check-links/check_links.py`;
  - for a behaviour change, also the specific check that proves the behaviour (the new/changed test, an evaluation, a dry run).
  - Write the commands and their results in the PR's 「確認」 section, and list separately what only CI can verify. The lead rejects sub-agent PRs without it and puts the same requirement in every sub-agent prompt.
- **Parallelize:**
  - Run Gradle builds/tests, the harness, evaluations and model downloads in the background (Bash `run_in_background`) and keep working; read the result when the completion notice arrives. Don't block the conversation on them.
  - Hand multi-minute investigation, implementation or evidence gathering to sub-agents (worktrees); the lead keeps PRs, issues and user reports moving.
  - Wait for CI through PR activity subscriptions / scheduled check-ins, never by polling or `sleep`.
  - CI is for what cannot run here (Android emulator, Xcode/Swift tests, iOS simulator), not a substitute for local verification (see "Verify before a PR").
  - Never leave the user waiting on a long command; reply and continue in the background.
- **Lead vs. sub-agents** — delegate only self-contained, multi-minute work; the lead owns everything that needs judgement or the whole picture:
  - Lead (do it yourself): priorities and planning; reviewing every sub-agent diff before it is merged (correctness, scope, docs/tests updated, no secrets); merge decisions; CI triage and small CI/config fixes; merge-conflict resolution; posting evidence and closing issues; labels, the docs/product/03 progress table and the dashboard (after every merge or issue state change); user reports and questions.
  - Sub-agents: multi-file implementation, long investigations/measurements (evaluations, benchmarks), evidence gathering that needs many CI iterations, bulk mechanical edits. Give them a branch, scope, verification commands and "no merge / no issue edits" unless told otherwise.
- Talk to the user in **Japanese** (see the rule at the top).
