#!/usr/bin/env python3
"""Summarize JUnit XML test results as Markdown, for $GITHUB_STEP_SUMMARY.

Usage: junit-summary.py <title> <glob> [<glob> ...]   (globs may use **)

Lists every test case with its result, so a CI run shows which tests ran on which platform
(e.g. the shared SQLite tests on the iOS simulator and the Android emulator, #14).
Always exits 0; the test tasks themselves fail the build.
"""
import glob
import sys
import xml.etree.ElementTree as ET


def main() -> None:
    title, patterns = sys.argv[1], sys.argv[2:]
    files = sorted({path for pattern in patterns for path in glob.glob(pattern, recursive=True)})
    cases = []
    for path in files:
        root = ET.parse(path).getroot()
        suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
        for suite in suites:
            for case in suite.iter("testcase"):
                failure = case.find("failure")
                if failure is None:
                    failure = case.find("error")
                if failure is not None:
                    # The first line of the message (e.g. "Expected <a>, actual <b>.") is not in the
                    # Gradle console output, so show it here.
                    lines = (failure.get("message") or failure.text or "").strip().splitlines()
                    detail = lines[0][:300].replace("|", "\\|") if lines else ""
                    result = f"failed: {detail}" if detail else "failed"
                elif case.find("skipped") is not None:
                    result = "skipped"
                else:
                    result = "passed"
                cases.append((case.get("classname", ""), case.get("name", ""), result, case.get("time", "")))

    print(f"### {title}\n")
    if not cases:
        print("No test results found.\n")
        return
    counts = {r: sum(1 for c in cases if c[2].split(":")[0] == r) for r in ("passed", "failed", "skipped")}
    print(f"{counts['passed']} passed, {counts['failed']} failed, {counts['skipped']} skipped "
          f"({len(files)} result files)\n")
    print("<details><summary>Test cases</summary>\n")
    print("| Class | Test | Result | Time (s) |")
    print("| --- | --- | --- | ---: |")
    for classname, name, result, time in cases:
        print(f"| {classname} | {name} | {result} | {time} |")
    print("\n</details>\n")


if __name__ == "__main__":
    main()
