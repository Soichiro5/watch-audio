#!/usr/bin/env python3
"""Summarize an Xcode result bundle (.xcresult) as Markdown, for $GITHUB_STEP_SUMMARY.

Usage: xcresult-summary.py <title> <path.xcresult>

Lists every test case with its result and the messages attached to it (skip reasons, failure
messages), like junit-summary.py does for the Kotlin tests. Uses
`xcrun xcresulttool get test-results tests` (Xcode 16+). Always exits 0.
"""
import json
import subprocess
import sys


def walk(node, suite, cases):
    kind = node.get("nodeType", "")
    if kind == "Test Case":
        messages = []
        for child in node.get("children", []):
            if "Message" in child.get("nodeType", "") and child.get("name"):
                messages.append(child["name"])
        cases.append((suite, node.get("name", ""), node.get("result", "unknown"),
                      node.get("durationInSeconds", node.get("duration", "")), messages))
        return
    if kind == "Test Suite":
        suite = node.get("name", suite)
    elif kind in ("Unit test bundle", "UI test bundle"):
        suite = node.get("name", suite)
    for child in node.get("children", []):
        walk(child, suite, cases)


def cell(text):
    return str(text).replace("|", "\\|").replace("\n", " ")


def main() -> None:
    title, path = sys.argv[1], sys.argv[2]
    print(f"### {title}\n")
    try:
        output = subprocess.run(
            ["xcrun", "xcresulttool", "get", "test-results", "tests", "--path", path],
            check=True, capture_output=True, text=True,
        ).stdout
        data = json.loads(output)
    except Exception as error:  # noqa: BLE001 - the summary must never fail the job
        print(f"Could not read {path}: {error}\n")
        return

    cases = []
    for node in data.get("testNodes", []):
        walk(node, "", cases)
    if not cases:
        print("No test results found.\n")
        return
    devices = ", ".join(f"{d.get('deviceName', '?')} ({d.get('platform', '?')} {d.get('osVersion', '?')})"
                        for d in data.get("devices", []))
    counts = {}
    for case in cases:
        counts[case[2]] = counts.get(case[2], 0) + 1
    print(", ".join(f"{n} {r.lower()}" for r, n in sorted(counts.items())) + (f" on {devices}" if devices else "") + "\n")
    print("<details><summary>Test cases</summary>\n")
    print("| Suite | Test | Result | Time (s) | Message |")
    print("| --- | --- | --- | ---: | --- |")
    for suite, name, result, duration, messages in cases:
        if isinstance(duration, float):
            duration = f"{duration:.3f}"
        print(f"| {cell(suite)} | {cell(name)} | {cell(result)} | {cell(duration)} | {cell('; '.join(messages))} |")
    print("\n</details>\n")


if __name__ == "__main__":
    main()
