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
                problem = case.find("failure")
                if problem is None:
                    problem = case.find("error")
                message = ""
                if problem is not None:
                    result = "failed"
                    message = (problem.get("message") or (problem.text or "").strip().split("\n")[0])[:300]
                elif case.find("skipped") is not None:
                    result = "skipped"
                else:
                    result = "passed"
                cases.append((case.get("classname", ""), case.get("name", ""), result, case.get("time", ""), message))

    print(f"### {title}\n")
    if not cases:
        print("No test results found.\n")
        return
    counts = {r: sum(1 for c in cases if c[2] == r) for r in ("passed", "failed", "skipped")}
    print(f"{counts['passed']} passed, {counts['failed']} failed, {counts['skipped']} skipped "
          f"({len(files)} result files)\n")
    print("<details><summary>Test cases</summary>\n")
    print("| Class | Test | Result | Time (s) |")
    print("| --- | --- | --- | ---: |")
    for classname, name, result, time, _ in cases:
        print(f"| {classname} | {name} | {result} | {time} |")
    print("\n</details>\n")
    failures = [c for c in cases if c[2] == "failed"]
    if failures:
        print("Failures:\n")
        for classname, name, _, _, message in failures:
            text = message.replace("|", "\\|").replace("\n", " ") or "(no message)"
            print(f"- `{classname}.{name}`: {text}")
        print()


if __name__ == "__main__":
    main()
