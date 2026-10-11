#!/usr/bin/env python3
"""App version check (#138): the version is defined once, in gradle.properties.

  watchaudio.versionName  user-visible version ("0.1.0"); Android versionName, iOS/watchOS
                          MARKETING_VERSION (CFBundleShortVersionString)
  watchaudio.versionCode  build number (positive integer); Android versionCode, iOS/watchOS
                          CURRENT_PROJECT_VERSION (CFBundleVersion)

Both Android apps read these properties through build-logic (AndroidAppConventionPlugin).
XcodeGen cannot read gradle.properties, so apps/ios/project.yml repeats the values; this check
fails when they differ, when the values are malformed, or when an app sets its own version
(versionName/versionCode in an app's build.gradle.kts, a literal CFBundleShortVersionString/
CFBundleVersion in project.yml).

Run: python3 tools/check-version/check_version.py [--root DIR]
Python standard library only.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

GRADLE_PROPERTIES = "gradle.properties"
IOS_PROJECT = "apps/ios/project.yml"
ANDROID_BUILD_FILES = ("apps/android/build.gradle.kts", "apps/wear/build.gradle.kts")

NAME_PROPERTY = "watchaudio.versionName"
CODE_PROPERTY = "watchaudio.versionCode"

# Apple: up to three period-separated non-negative integers (also fine as an Android versionName).
VERSION_NAME = re.compile(r"\d+(\.\d+){0,2}")
# Android: versionCode must be a positive integer up to 2100000000.
MAX_VERSION_CODE = 2_100_000_000


def read_properties(path: Path) -> dict[str, str]:
    """Parses `key=value` lines of a .properties file (no continuation lines, as in this repo)."""
    props = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        key, sep, value = line.partition("=")
        if sep:
            props[key.strip()] = value.strip()
    return props


def yaml_values(text: str, key: str) -> list[tuple[int, str]]:
    """Returns (line number, scalar value) for every `key: value` line in a YAML file."""
    pattern = re.compile(rf"^\s*{re.escape(key)}\s*:\s*(.*?)\s*$")
    found = []
    for number, line in enumerate(text.splitlines(), start=1):
        match = pattern.match(line)
        if not match:
            continue
        value = re.sub(r"\s+#.*$", "", match.group(1))
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        found.append((number, value))
    return found


def check(root: Path) -> list[str]:
    """Returns the problems found under [root] (empty when the version is consistent)."""
    errors: list[str] = []

    props = read_properties(root / GRADLE_PROPERTIES)
    name = props.get(NAME_PROPERTY)
    code = props.get(CODE_PROPERTY)
    if name is None:
        errors.append(f"{GRADLE_PROPERTIES}: {NAME_PROPERTY} is missing")
    elif not VERSION_NAME.fullmatch(name):
        errors.append(f"{GRADLE_PROPERTIES}: {NAME_PROPERTY}={name} is not 1 to 3 dot-separated integers (e.g. 0.2.0)")
    if code is None:
        errors.append(f"{GRADLE_PROPERTIES}: {CODE_PROPERTY} is missing")
    elif not code.isdigit() or not 0 < int(code) <= MAX_VERSION_CODE:
        errors.append(f"{GRADLE_PROPERTIES}: {CODE_PROPERTY}={code} is not an integer from 1 to {MAX_VERSION_CODE}")

    ios_text = (root / IOS_PROJECT).read_text(encoding="utf-8")
    for key, expected, prop in (("MARKETING_VERSION", name, NAME_PROPERTY),
                                ("CURRENT_PROJECT_VERSION", code, CODE_PROPERTY)):
        values = yaml_values(ios_text, key)
        if not values:
            errors.append(f"{IOS_PROJECT}: {key} is not set")
        for number, value in values:
            if expected is not None and value != expected:
                errors.append(f"{IOS_PROJECT}:{number}: {key} is {value!r} but {GRADLE_PROPERTIES} has "
                              f"{prop}={expected}")
    for key, setting in (("CFBundleShortVersionString", "MARKETING_VERSION"),
                         ("CFBundleVersion", "CURRENT_PROJECT_VERSION")):
        for number, value in yaml_values(ios_text, key):
            if value != f"$({setting})":
                errors.append(f"{IOS_PROJECT}:{number}: {key} must be $({setting}), not {value!r}")

    for build_file in ANDROID_BUILD_FILES:
        text = (root / build_file).read_text(encoding="utf-8")
        for number, line in enumerate(text.splitlines(), start=1):
            if re.match(r"\s*(versionName|versionCode)\s*=", line):
                errors.append(f"{build_file}:{number}: sets its own version ({line.strip()}); "
                              f"the version comes from {GRADLE_PROPERTIES} through build-logic")

    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2],
                        help="repository root (default: this checkout)")
    args = parser.parse_args(argv)
    errors = check(args.root)
    if errors:
        print("Version check failed (#138, docs/dev/04-dev-environment.md §7.2):")
        for error in errors:
            print(f"  {error}")
        return 1
    props = read_properties(args.root / GRADLE_PROPERTIES)
    print(f"Version check passed: {props[NAME_PROPERTY]} ({props[CODE_PROPERTY]}) in {GRADLE_PROPERTIES}, "
          f"{IOS_PROJECT} and the Android apps")
    return 0


if __name__ == "__main__":
    sys.exit(main())
