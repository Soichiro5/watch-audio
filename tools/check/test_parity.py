"""CI and local checks stay in step (#202).

Every `run:` step of the workflows (.github/workflows/*.yml) and of the composite actions
(.github/actions/*/action.yml), and the emulator runner's `script:`, must be a single call of
`tools/check/check.sh step <name>`: then `tools/check/check.sh <stage>` on a developer's machine runs
the same checks, with the same commands, as CI. Only pure setup (runner cleanup, package installs,
cache waits) and artifact packaging stay in the workflows; they are listed in ALLOWED_SETUP with the
reason, and must not run anything that verifies the code.

Also: every step a workflow names exists in tools/check/steps-*.sh, is part of a local stage of
check.sh (or has a local counterpart in LOCAL_COUNTERPART), and is listed in docs/dev/04's
「手元での確認」 table.

Python standard library only (a minimal reader for the YAML these files use; no PyYAML).
Run: python3 -m unittest discover -s tools/check -p 'test_*.py' -v   (check.sh step parity)
"""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHECK_DIR = ROOT / "tools" / "check"
DOCS = ROOT / "docs" / "dev" / "04-dev-environment.md"

# (file relative to the repository, step name): why it is not a tools/check step.
ALLOWED_SETUP = {
    (".github/workflows/android.yml", "Install ffmpeg (end-to-end test audio)"):
        "package install on the runner (locally a prerequisite that the harness step checks for)",
    (".github/workflows/android.yml", "Remove the release key"):
        "cleanup of the CI secret written to the runner's temp dir (if: always(), also after a cancel)",
    (".github/workflows/deliver.yml", "Collect builds"):
        "artifact packaging (copies the APKs to dist/ with the commit in the name) and the job summary",
    (".github/workflows/emulator.yml", "Free disk space for the emulator image"):
        "runner cleanup (moves preinstalled toolchains aside); must never run on a developer's machine",
    (".github/workflows/emulator.yml", "Download the emulator and its system image (background)"):
        "runner setup: prefetches the emulator for the emulator runner action (locally: tools/check/avd.sh)",
    (".github/workflows/emulator.yml", "Enable KVM"):
        "runner setup: udev rule for /dev/kvm",
    (".github/workflows/emulator.yml", "Wait for the emulator download"):
        "runner setup: joins the background prefetch",
    (".github/workflows/ios.yml", "Show Xcode version"):
        "information only (the Xcode of the runner image)",
    (".github/workflows/ios.yml", "Audio output for the simulators"):
        "runner setup: a virtual audio device when the runner has none (a Mac has one)",
    (".github/workflows/ios.yml", "Install XcodeGen"):
        "package install on the runner (locally a prerequisite that the ios-xcodegen step checks for)",
}

# Steps that only the CI composite action runs, and the local step that does the same.
LOCAL_COUNTERPART = {
    "sherpa-onnx-jni-info": "sherpa-onnx-jni",
    "sherpa-onnx-jni-build": "sherpa-onnx-jni",
    "sherpa-onnx-jni-verify": "sherpa-onnx-jni",
}

# Programs that verify something; an allowed setup step must not run them (checked at the start of
# each command of a line, after `;`, `&&`, `||`, `|`, `(`, `sudo`, `exec`, `bash` / `sh`).
VERIFYING_PROGRAMS = {
    "./gradlew", "gradlew", "npm", "npx", "node", "python", "python3", "xcodegen", "xcrun", "adb",
    "keytool", "swift", "xctest",
}


def verifying_command(line):
    """The program of `line` that verifies something, or None."""
    for segment in re.split(r";|&&|\|\||\||\(|\)|\$\(", line):
        words = segment.split()
        while words and (words[0] in ("sudo", "exec", "bash", "sh", "env", "then", "do", "!") or "=" in words[0]):
            words = words[1:]
        if not words:
            continue
        program = words[0]
        if program in VERIFYING_PROGRAMS:
            return program
        if program == "xcodebuild" and words[1:] != ["-version"]:
            return program
        if program.startswith((".github/scripts/", "tools/", "./tools/")):
            return program
    return None
CHECK_STEP = re.compile(r"^tools/check/check\.sh step ([a-z0-9-]+)(?: .*)?$")


# --- a minimal reader for the workflow YAML -------------------------------------------------------

def _indent(line):
    return len(line) - len(line.lstrip(" "))


def _scalar(value):
    value = value.strip()
    if value[:1] in "\"'" and value[-1:] == value[:1]:
        return value[1:-1]
    # Trailing comment (`uses: x@sha # v1`).
    return re.sub(r"\s+#.*$", "", value)


def _block(lines, i, parent_indent):
    """Lines of a block scalar after `key: |`, dedented; returns (text, next index)."""
    body = []
    while i < len(lines) and (not lines[i].strip() or _indent(lines[i]) > parent_indent):
        body.append(lines[i])
        i += 1
    while body and not body[-1].strip():
        body.pop()
    cut = min((_indent(l) for l in body if l.strip()), default=0)
    return "\n".join(l[cut:] for l in body), i


def _mapping(lines, i, indent):
    """A mapping whose keys are at `indent`; returns (dict, next index)."""
    result = {}
    while i < len(lines):
        line = lines[i]
        if not line.strip() or line.lstrip().startswith("#"):
            i += 1
            continue
        if _indent(line) < indent:
            break
        if _indent(line) > indent:
            i += 1
            continue
        i = _key(lines, i, line.strip(), indent, result)
    return result, i


def _key(lines, i, text, indent, target):
    m = re.match(r"^([\w.-]+):\s*(.*)$", text)
    if not m:
        return i + 1
    key, value = m.group(1), m.group(2)
    if value in ("|", "|-", ">", ">-"):
        target[key], i = _block(lines, i + 1, indent)
        return i
    if value == "" or value.startswith("#"):
        nxt = i + 1
        while nxt < len(lines) and (not lines[nxt].strip() or lines[nxt].lstrip().startswith("#")):
            nxt += 1
        if nxt < len(lines) and _indent(lines[nxt]) > indent and not lines[nxt].lstrip().startswith("- "):
            target[key], i = _mapping(lines, nxt, _indent(lines[nxt]))
            return i
        target[key] = ""
        return i + 1
    target[key] = _scalar(value)
    return i + 1


def parse_steps(text):
    """Every step (dict of name, uses, run, with, if, id ...) of every `steps:` list in the file."""
    lines = text.splitlines()
    steps = []
    i = 0
    while i < len(lines):
        m = re.match(r"^(\s*)steps:\s*$", lines[i])
        i += 1
        if not m:
            continue
        base = len(m.group(1))
        item_indent = None
        current = None
        while i < len(lines):
            line = lines[i]
            if not line.strip() or line.lstrip().startswith("#"):
                i += 1
                continue
            ind = _indent(line)
            if ind <= base and not (ind == base and line.lstrip().startswith("- ")):
                break
            stripped = line.strip()
            if stripped.startswith("- ") and (item_indent is None or ind == item_indent):
                item_indent = ind
                current = {}
                steps.append(current)
                i = _key(lines, i, stripped[2:], ind + 2, current)
            elif current is not None and ind == item_indent + 2:
                i = _key(lines, i, stripped, ind, current)
            else:
                i += 1
    return steps


def step_label(step):
    return step.get("name") or step.get("uses") or (step.get("run") or "").splitlines()[0]


def commands(script):
    """The command lines of a shell script (no blank lines or comments)."""
    return [l.strip() for l in script.splitlines() if l.strip() and not l.strip().startswith("#")]


# --- checks -------------------------------------------------------------------------------------

def workflow_files(root=ROOT):
    files = sorted((root / ".github" / "workflows").glob("*.yml"))
    files += sorted((root / ".github" / "actions").glob("*/action.yml"))
    return files


def check_file(rel, text, allowed=ALLOWED_SETUP):
    """Returns (violations, steps named in check.sh calls, allow-list keys used)."""
    violations, named, used = [], [], set()
    for step in parse_steps(text):
        label = step_label(step)
        scripts = []
        if "run" in step:
            scripts.append(("run", step["run"]))
        with_ = step.get("with")
        if isinstance(with_, dict) and "script" in with_ and "android-emulator-runner" in step.get("uses", ""):
            scripts.append(("with.script", with_["script"]))
        for where, script in scripts:
            key = (rel, step.get("name", ""))
            if key in allowed:
                used.add(key)
                bad = [c for c in commands(script) if verifying_command(c)]
                if bad:
                    violations.append(f"{rel}: allowed setup step '{label}' runs a check: {bad[0]}")
                continue
            cmds = commands(script)
            m = CHECK_STEP.match(cmds[0]) if len(cmds) == 1 else None
            if not m:
                violations.append(
                    f"{rel}: step '{label}' ({where}) must be one `tools/check/check.sh step <name>` call "
                    f"(or pure setup listed in tools/check/test_parity.py ALLOWED_SETUP), found: {cmds[:3]}")
                continue
            named.append(m.group(1))
    return violations, named, used


def defined_steps(check_dir=CHECK_DIR):
    names = set()
    for f in check_dir.glob("steps-*.sh"):
        names |= {m.replace("_", "-") for m in re.findall(r"^step_([a-z0-9_]+)\(\)", f.read_text(), re.M)}
    return names


def staged_steps(check_dir=CHECK_DIR):
    """Step names that check.sh's stages run (run_step / run_step_soft <name>, run_after <dep> <name>)."""
    text = (check_dir / "check.sh").read_text()
    names = set(re.findall(r"\brun_step(?:_soft)?\s+([a-z0-9-]+)", text))
    names |= set(re.findall(r"\brun_after\s+(?:\"[^\"]+\"|[a-z0-9-]+)\s+([a-z0-9-]+)", text))
    return names


class ParityTest(unittest.TestCase):
    def setUp(self):
        self.violations, self.named, self.used = [], [], set()
        for f in workflow_files():
            rel = f.relative_to(ROOT).as_posix()
            v, n, u = check_file(rel, f.read_text())
            self.violations += v
            self.named += n
            self.used |= u

    def test_every_run_step_goes_through_tools_check(self):
        self.assertEqual([], self.violations)

    def test_workflows_name_steps_at_all(self):
        # Guards the reader itself: the workflows call a few dozen steps.
        self.assertGreater(len(set(self.named)), 30, sorted(set(self.named)))

    def test_named_steps_exist(self):
        missing = sorted(set(self.named) - defined_steps())
        self.assertEqual([], missing, "workflows call steps that tools/check/steps-*.sh do not define")

    def test_ci_steps_run_in_a_local_stage(self):
        staged = staged_steps()
        missing = sorted(
            n for n in set(self.named) if n not in staged and LOCAL_COUNTERPART.get(n) not in staged)
        self.assertEqual([], missing, "CI steps that no stage of check.sh runs locally")

    def test_allowed_setup_entries_are_used(self):
        stale = sorted(set(ALLOWED_SETUP) - self.used)
        self.assertEqual([], stale, "ALLOWED_SETUP entries that match no workflow step")

    def test_ci_steps_are_documented(self):
        docs = DOCS.read_text()
        undocumented = sorted(n for n in set(self.named) if f"`{n}`" not in docs)
        self.assertEqual([], undocumented, "steps missing from docs/dev/04 「手元での確認」")


class ReaderTest(unittest.TestCase):
    """The check fails on what it must catch (so a green run means something)."""

    WORKFLOW = """
name: x
jobs:
  a:
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@abc # v1
      - name: Good
        run: tools/check/check.sh step unit-tests
      - name: Direct gradle
        run: ./gradlew jvmTest
      - name: Two commands
        run: |
          tools/check/check.sh step lint
          ./gradlew assembleDebug
      - name: Setup
        run: |
          # comment
          sudo apt-get install -y ffmpeg
      - name: Setup that tests
        run: python3 tools/privacy-check/check.py
      - name: Emulator
        uses: reactivecircus/android-emulator-runner@abc # v2
        with:
          api-level: 35
          script: |
            bash .github/scripts/launch-smoke.sh x.apk out.txt
  b:
    steps:
      - run: tools/check/check.sh step links
"""

    def test_reader(self):
        steps = parse_steps(self.WORKFLOW)
        self.assertEqual(
            ["actions/checkout@abc", "Good", "Direct gradle", "Two commands", "Setup", "Setup that tests",
             "Emulator", "tools/check/check.sh step links"],
            [step_label(s) for s in steps])
        self.assertEqual("35", steps[6]["with"]["api-level"])
        self.assertIn("launch-smoke.sh", steps[6]["with"]["script"])

    def test_violations(self):
        allowed = {("w.yml", "Setup"): "install", ("w.yml", "Setup that tests"): "wrongly allowed"}
        violations, named, used = check_file("w.yml", self.WORKFLOW, allowed)
        self.assertEqual(["unit-tests", "links"], named)
        self.assertEqual(set(allowed), used)
        text = "\n".join(violations)
        self.assertIn("'Direct gradle'", text)
        self.assertIn("'Two commands'", text)
        self.assertIn("'Setup that tests' runs a check", text)
        self.assertIn("'Emulator' (with.script)", text)
        self.assertNotIn("'Good'", text)
        self.assertNotIn("'Setup' runs", text)
        self.assertEqual(4, len(violations), violations)

    def test_repository_steps_are_parsed(self):
        steps = defined_steps()
        for name in ("unit-tests", "emulator-run", "ios-build-app", "bridge-e2e", "parity"):
            self.assertIn(name, steps)
        self.assertIn("ios-agent-access", staged_steps())


if __name__ == "__main__":
    unittest.main()
