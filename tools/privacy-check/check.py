#!/usr/bin/env python3
"""Local-only check (#76): fails when a change could let data leave the device.

Static checks (repository files only, no build needed):
  * Android manifests: permissions against the allow-list, allowBackup="false", backup and
    data extraction rules excluding every domain, no cleartext / custom network config.
  * App sources (.kt/.java/.swift): network APIs, Wear DataClient and weak file protection only
    in allow-listed files; URL literals only with allow-listed prefixes or in display-only files
    (license notices shown as text).
  * Gradle files: no analytics, crash reporting or network client dependencies.
  * iOS project.yml / committed plists: Info.plist keys, background modes, entitlements and the
    privacy manifests (one per app / extension target, no tracking, no collected data, reasons
    from the allow-list).
  * Agent bridge CLI on the PC (tools/agent-bridge): network modules only where allowed (listening
    TLS, mDNS), no URLs, runtime npm packages (transitive ones included) from the allow-list.

Build outputs (optional, after `assembleDebug` / `dependencies`):
  --merged-manifest APP=PATH   merged AndroidManifest.xml (library permissions included)
  --resolved-deps APP=PATH     output of `./gradlew :APP:dependencies --configuration ...`

Allow-lists live in policy.py. Python standard library only.
"""

from __future__ import annotations

import argparse
import json
import os
import plistlib
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import policy  # noqa: E402

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
TOOLS_NS = "{http://schemas.android.com/tools}"


@dataclass(frozen=True)
class Violation:
    check: str
    path: str
    line: int
    message: str

    def __str__(self) -> str:
        location = f"{self.path}:{self.line}" if self.line else self.path
        return f"[{self.check}] {location}: {self.message}"


@dataclass
class Report:
    violations: list[Violation] = field(default_factory=list)
    # Allowed network call sites found in code: (path, line, api id, reason).
    network_sites: list[tuple[str, int, str, str]] = field(default_factory=list)
    # The same for the agent bridge CLI on the PC (tools/agent-bridge).
    bridge_sites: list[tuple[str, int, str, str]] = field(default_factory=list)

    def add(self, check: str, path: str, line: int, message: str) -> None:
        self.violations.append(Violation(check, path, line, message))


# ---------------------------------------------------------------------------------------------
# Android manifests


def _rel(root: Path, path: Path) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return path.as_posix()


def _parse_xml(root: Path, path: Path, report: Report, check: str) -> ET.Element | None:
    try:
        return ET.parse(path).getroot()
    except (ET.ParseError, OSError) as e:
        report.add(check, _rel(root, path), 0, f"cannot parse: {e}")
        return None


def _permissions(manifest: ET.Element) -> list[str]:
    names = []
    for tag in ("uses-permission", "uses-permission-sdk-23"):
        for element in manifest.iter(tag):
            if element.get(TOOLS_NS + "node") == "remove":
                continue
            names.append(element.get(ANDROID_NS + "name", ""))
    return names


def _check_backup_rules(root: Path, module: str, reference: str | None, sections: tuple[str, ...],
                        attribute: str, report: Report) -> None:
    path = f"{module}/src/main/AndroidManifest.xml"
    if not reference:
        report.add("backup", path, 0, f"<application> must set android:{attribute}")
        return
    if not reference.startswith("@xml/"):
        report.add("backup", path, 0, f"android:{attribute} must reference @xml/...")
        return
    rules_path = root / module / "src/main/res/xml" / (reference[len("@xml/"):] + ".xml")
    if not rules_path.is_file():
        report.add("backup", path, 0, f"{reference} not found ({_rel(root, rules_path)})")
        return
    rules = _parse_xml(root, rules_path, report, "backup")
    if rules is None:
        return
    rel = _rel(root, rules_path)
    if any(True for _ in rules.iter("include")):
        report.add("backup", rel, 0, "<include> is not allowed: app data never goes into a backup")
    for section_name in sections:
        section = rules if rules.tag == section_name else rules.find(section_name)
        if section is None:
            report.add("backup", rel, 0, f"<{section_name}> section missing")
            continue
        excluded = {e.get("domain") for e in section.iter("exclude") if e.get("path") in (None, "", ".")}
        missing = [d for d in policy.BACKUP_DOMAINS if d not in excluded]
        if missing:
            report.add("backup", rel, 0, f"<{section_name}> must exclude whole domains: {', '.join(missing)}")


def _check_application(root: Path, module: str, manifest: ET.Element, sections: tuple[str, ...],
                       report: Report, rel: str, follow_rules: bool) -> None:
    application = manifest.find("application")
    if application is None:
        report.add("backup", rel, 0, "<application> missing")
        return
    if application.get(ANDROID_NS + "allowBackup") != "false":
        report.add("backup", rel, 0, 'android:allowBackup must be "false"')
    if application.get(ANDROID_NS + "usesCleartextTraffic") == "true":
        report.add("network", rel, 0, "android:usesCleartextTraffic=\"true\" is not allowed")
    if application.get(ANDROID_NS + "networkSecurityConfig"):
        report.add("network", rel, 0, "a custom networkSecurityConfig needs a policy review (#76)")
    extraction = application.get(ANDROID_NS + "dataExtractionRules")
    if follow_rules:
        _check_backup_rules(root, module, extraction, sections, "dataExtractionRules", report)
        full_backup = application.get(ANDROID_NS + "fullBackupContent")
        if full_backup and full_backup != "false":
            _check_backup_rules(root, module, full_backup, ("full-backup-content",), "fullBackupContent", report)
    elif not extraction:
        report.add("backup", rel, 0, "android:dataExtractionRules missing")


def check_android_manifests(root: Path, report: Report) -> None:
    for module, app in policy.ANDROID_APPS.items():
        manifests = sorted((root / module / "src").glob("*/AndroidManifest.xml"))
        main = root / module / "src/main/AndroidManifest.xml"
        if main not in manifests:
            report.add("permissions", f"{module}/src/main/AndroidManifest.xml", 0, "main manifest missing")
            continue
        for path in manifests:
            manifest = _parse_xml(root, path, report, "permissions")
            if manifest is None:
                continue
            rel = _rel(root, path)
            for name in _permissions(manifest):
                if name not in app["permissions"]:
                    report.add("permissions", rel, 0,
                               f"permission {name} is not in the {module} allow-list (policy.ANDROID_APPS)")
            if path == main:
                _check_application(root, module, manifest, app["excluded_backup_sections"], report, rel,
                                   follow_rules=True)


def check_merged_manifest(root: Path, module: str, path: Path, report: Report) -> None:
    app = policy.ANDROID_APPS.get(module)
    rel = _rel(root, path)
    if app is None:
        report.add("permissions", rel, 0, f"unknown app {module}")
        return
    manifest = _parse_xml(root, path, report, "permissions")
    if manifest is None:
        return
    allowed = {**app["permissions"], **app["library_permissions"]}
    for name in _permissions(manifest):
        if name not in allowed:
            report.add("permissions", rel, 0,
                       f"merged manifest of {module} requests {name}, which is not in the allow-list "
                       "(a dependency added it? policy.ANDROID_APPS library_permissions)")
    _check_application(root, module, manifest, app["excluded_backup_sections"], report, rel, follow_rules=False)


# ---------------------------------------------------------------------------------------------
# Sources


def strip_comments(source: str) -> str:
    """Replaces // and (nested) /* */ comments with spaces, keeping strings and line numbers.

    Good enough for Kotlin, Java and Swift: string literals are kept (URLs live there) and an
    unterminated single-line string ends at the line break.
    """
    out: list[str] = []
    i, n = 0, len(source)
    while i < n:
        c = source[i]
        two = source[i:i + 2]
        if two == "//":
            while i < n and source[i] != "\n":
                out.append(" ")
                i += 1
        elif two == "/*":
            depth = 0
            while i < n:
                if source.startswith("/*", i):
                    depth += 1
                    out.append("  ")
                    i += 2
                elif source.startswith("*/", i):
                    depth -= 1
                    out.append("  ")
                    i += 2
                    if depth == 0:
                        break
                else:
                    out.append("\n" if source[i] == "\n" else " ")
                    i += 1
        elif source.startswith('"""', i):
            end = source.find('"""', i + 3)
            end = n if end < 0 else end + 3
            out.append(source[i:end])
            i = end
        elif c in "\"'":
            j = i + 1
            while j < n and source[j] not in (c, "\n"):
                j += 2 if source[j] == "\\" else 1
            j = min(j + 1, n) if j < n and source[j] == c else j
            out.append(source[i:j])
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


URL_PATTERN = re.compile(r"\b(?:https?|wss?|ftp)://[^\s\"'`)<>\\]+")


def _is_test_directory(name: str) -> bool:
    """Test source sets and Swift test targets: not shipped, so not scanned."""
    return name == "test" or "Test" in name


def _source_files(root: Path):
    for base in policy.SOURCE_ROOTS:
        for dirpath, dirnames, filenames in os.walk(root / base):
            dirnames[:] = sorted(d for d in dirnames if d not in ("build", ".gradle", "DerivedData")
                                 and not d.endswith(".xcodeproj") and not _is_test_directory(d))
            for filename in sorted(filenames):
                if filename.endswith(policy.SOURCE_EXTENSIONS):
                    yield Path(dirpath) / filename


def check_sources(root: Path, report: Report) -> None:
    apis = [(api_id, re.compile(pattern, re.MULTILINE), what) for api_id, pattern, what in policy.GUARDED_APIS]
    used_apis: set[tuple[str, str]] = set()
    used_prefixes: set[str] = set()
    display_only_files: set[str] = set()
    for path in _source_files(root):
        rel = _rel(root, path)
        code = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
        allowed = policy.ALLOWED_API_USES.get(rel, {})
        for api_id, regex, what in apis:
            for match in regex.finditer(code):
                line = code.count("\n", 0, match.start()) + 1
                if api_id in allowed:
                    used_apis.add((rel, api_id))
                    report.network_sites.append((rel, line, api_id, allowed[api_id]))
                else:
                    report.add("network", rel, line,
                               f"{match.group(0).strip()} ({what}) is only allowed in files listed in "
                               "policy.ALLOWED_API_USES")
        if rel in policy.DISPLAY_ONLY_URL_FILES:
            if URL_PATTERN.search(code):
                display_only_files.add(rel)
            continue
        for match in URL_PATTERN.finditer(code):
            url = match.group(0)
            prefixes = [prefix for prefix in policy.ALLOWED_URL_PREFIXES if url.startswith(prefix)]
            used_prefixes.update(prefixes)
            if not prefixes:
                line = code.count("\n", 0, match.start()) + 1
                report.add("network", rel, line, f"URL {url} is not in policy.ALLOWED_URL_PREFIXES")
    for rel, requirements in policy.ALLOWED_API_REQUIREMENTS.items():
        path = root / rel
        code = strip_comments(path.read_text(encoding="utf-8", errors="replace")) if path.is_file() else ""
        for what, pattern in requirements:
            if not re.search(pattern, code, re.MULTILINE):
                report.add("network", rel, 0, f"allowed network site no longer {what} (policy.ALLOWED_API_REQUIREMENTS)")
    # Stale entries would make the list of network call sites (docs/architecture/02 §7.2) wrong.
    for rel, allowed in policy.ALLOWED_API_USES.items():
        for api_id in allowed:
            if (rel, api_id) not in used_apis:
                report.add("network", rel, 0, f"stale allow-list entry: {api_id} is no longer used here "
                           "(remove it from policy.ALLOWED_API_USES)")
    for prefix in policy.ALLOWED_URL_PREFIXES:
        if prefix not in used_prefixes:
            report.add("network", "tools/privacy-check/policy.py", 0,
                       f"stale allow-list entry: no URL starts with {prefix} (remove it from ALLOWED_URL_PREFIXES)")
    for rel in policy.DISPLAY_ONLY_URL_FILES:
        if rel not in display_only_files:
            report.add("network", "tools/privacy-check/policy.py", 0,
                       f"stale allow-list entry: {rel} has no URL (remove it from DISPLAY_ONLY_URL_FILES)")


# ---------------------------------------------------------------------------------------------
# Dependencies


def _strip_hash_comments(text: str) -> str:
    return "\n".join(re.sub(r"\s#.*$|^#.*$", "", line) for line in text.split("\n"))


def check_declared_dependencies(root: Path, report: Report) -> None:
    files = [root / "gradle/libs.versions.toml"] if (root / "gradle/libs.versions.toml").is_file() else []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in ("build", ".gradle", ".git", "node_modules"))
        files += [Path(dirpath) / f for f in sorted(filenames) if f.endswith(".gradle.kts")]
    denied = [(name, re.compile(pattern, re.IGNORECASE)) for name, pattern in policy.DENIED_DEPENDENCIES]
    for path in files:
        rel = _rel(root, path)
        if rel.startswith(policy.DEPENDENCY_SCAN_EXCLUDES):
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        text = _strip_hash_comments(text) if path.suffix == ".toml" else strip_comments(text)
        for number, line in enumerate(text.split("\n"), start=1):
            for name, regex in denied:
                match = regex.search(line)
                if match:
                    report.add("dependencies", rel, number,
                               f"'{match.group(0)}' looks like a {name} dependency (policy.DENIED_DEPENDENCIES)")


DEPENDENCY_LINE = re.compile(r"[+\\]--- ([\w.-]+):([\w.-]+)")


def check_resolved_dependencies(root: Path, module: str, path: Path, report: Report) -> None:
    rel = _rel(root, path)
    allowed = [re.compile(pattern) for pattern, _ in policy.ALLOWED_RESOLVED_DEPENDENCIES]
    seen: set[str] = set()
    for line in path.read_text(encoding="utf-8", errors="replace").split("\n"):
        match = DEPENDENCY_LINE.search(line)
        if not match:
            continue
        coordinate = f"{match.group(1)}:{match.group(2)}"
        if coordinate in seen:
            continue
        seen.add(coordinate)
        if not any(regex.fullmatch(coordinate) for regex in allowed):
            report.add("dependencies", rel, 0,
                       f"{module} runtime classpath contains {coordinate}, which is not in "
                       "policy.ALLOWED_RESOLVED_DEPENDENCIES")
    if not seen:
        report.add("dependencies", rel, 0, f"no dependencies found in the report for {module}")


# ---------------------------------------------------------------------------------------------
# iOS


def _yaml_scalar(text: str):
    text = text.strip()
    if text.startswith("[") and text.endswith("]"):
        inner = text[1:-1].strip()
        return [_yaml_scalar(item) for item in inner.split(",")] if inner else []
    if text.startswith("{") and text.endswith("}"):
        inner = text[1:-1].strip()
        result = {}
        for item in inner.split(",") if inner else []:
            key, _, value = item.partition(":")
            result[_yaml_scalar(key)] = _yaml_scalar(value)
        return result
    if len(text) >= 2 and text[0] == text[-1] and text[0] in "\"'":
        return text[1:-1]
    return text


def parse_simple_yaml(text: str):
    """Parses the YAML subset XcodeGen projects use: block mappings and lists, flow lists and
    maps on one line, quoted scalars and block scalars (| / >). Enough for project.yml."""
    lines: list[tuple[int, str]] = []
    raw = text.split("\n")
    i = 0
    while i < len(raw):
        line = raw[i]
        stripped = re.sub(r"(^|\s)#.*$", "", line).rstrip()
        i += 1
        if not stripped.strip():
            continue
        indent = len(stripped) - len(stripped.lstrip(" "))
        content = stripped.strip()
        if re.search(r":\s*[|>][-+]?\d*$", content):
            # Block scalar: swallow the more-indented lines that follow.
            while i < len(raw) and (not raw[i].strip() or len(raw[i]) - len(raw[i].lstrip(" ")) > indent):
                i += 1
            content = content[:content.rfind(":") + 1] + " ''"
        lines.append((indent, content))

    def parse_block(index: int, indent: int):
        if lines[index][1].startswith("- ") or lines[index][1] == "-":
            return parse_list(index, indent)
        return parse_mapping(index, indent)

    def parse_list(index: int, indent: int):
        items = []
        while index < len(lines) and lines[index][0] == indent and (
                lines[index][1].startswith("- ") or lines[index][1] == "-"):
            rest = lines[index][1][1:].strip()
            if not rest:
                if index + 1 < len(lines) and lines[index + 1][0] > indent:
                    value, index = parse_block(index + 1, lines[index + 1][0])
                else:
                    value, index = None, index + 1
                items.append(value)
            elif re.match(r"^[^\[{\"'][^:]*:(\s|$)", rest):
                # "- key: value" starts a mapping indented at the item's content.
                lines[index] = (indent + 2, rest)
                value, index = parse_mapping(index, indent + 2)
                items.append(value)
            else:
                items.append(_yaml_scalar(rest))
                index += 1
        return items, index

    def parse_mapping(index: int, indent: int):
        result = {}
        while index < len(lines) and lines[index][0] == indent and not lines[index][1].startswith("- "):
            content = lines[index][1]
            if content.endswith(":"):
                key, value = content[:-1], ""
            else:
                key, sep, value = content.partition(": ")
                if not sep:
                    raise ValueError(f"cannot parse YAML line: {content!r}")
            key = _yaml_scalar(key)
            index += 1
            if value.strip():
                result[key] = _yaml_scalar(value)
            elif index < len(lines) and (lines[index][0] > indent or (
                    lines[index][0] == indent and lines[index][1].startswith("- "))):
                result[key], index = parse_block(index, lines[index][0])
            else:
                result[key] = None
        return result, index

    if not lines:
        return {}
    value, _ = parse_block(0, lines[0][0])
    return value


def _check_info_keys(target: str, info: dict, rel: str, report: Report) -> None:
    rules = policy.IOS_TARGETS.get(target)
    if rules is None:
        if info:
            report.add("ios", rel, 0, f"target {target} has Info.plist keys but no policy.IOS_TARGETS entry")
        return
    for key in info:
        if key not in rules["info"]:
            report.add("ios", rel, 0, f"Info.plist key {key} of {target} is not in policy.IOS_TARGETS")
    modes = info.get("UIBackgroundModes") or []
    for mode in modes if isinstance(modes, list) else [modes]:
        if mode not in rules["background_modes"]:
            report.add("ios", rel, 0, f"UIBackgroundModes '{mode}' of {target} is not in policy.IOS_TARGETS")
    services = info.get("NSBonjourServices") or []
    for service in services if isinstance(services, list) else [services]:
        if service not in policy.IOS_BONJOUR_SERVICES:
            report.add("ios", rel, 0, f"NSBonjourServices '{service}' of {target} is not in policy.IOS_BONJOUR_SERVICES")


def _check_entitlements(keys, where: str, rel: str, report: Report) -> None:
    for key in keys:
        if key not in policy.IOS_ENTITLEMENTS:
            report.add("ios", rel, 0, f"entitlement {key} ({where}) is not in policy.IOS_ENTITLEMENTS")


def _check_privacy_manifest_source(target: str, spec: dict, ios_dir: Path, rel: str, report: Report) -> None:
    """An app / extension target must copy exactly one existing privacy manifest as a resource."""
    if spec.get("type") not in policy.IOS_PRIVACY_TARGET_TYPES:
        return
    entries = [s for s in spec.get("sources") or [] if isinstance(s, dict)
               and str(s.get("path", "")).endswith(policy.IOS_PRIVACY_MANIFEST_NAME)]
    if len(entries) != 1:
        report.add("ios", rel, 0, f"target {target} must list one {policy.IOS_PRIVACY_MANIFEST_NAME} in sources")
        return
    entry = entries[0]
    if entry.get("buildPhase") != "resources":
        report.add("ios", rel, 0, f"{entry['path']} of {target} must use buildPhase: resources")
    if not (ios_dir / entry["path"]).is_file():
        report.add("ios", rel, 0, f"{entry['path']} of {target} does not exist")


def _check_privacy_manifest(path: Path, rel: str, report: Report) -> None:
    try:
        data = plistlib.loads(path.read_bytes())
    except Exception as e:  # noqa: BLE001 - any parse error is a violation
        report.add("ios", rel, 0, f"cannot parse: {e}")
        return
    if data.get("NSPrivacyTracking") is not False:
        report.add("ios", rel, 0, "NSPrivacyTracking must be false")
    if data.get("NSPrivacyTrackingDomains"):
        report.add("ios", rel, 0, "NSPrivacyTrackingDomains must be empty")
    if data.get("NSPrivacyCollectedDataTypes"):
        report.add("ios", rel, 0, "NSPrivacyCollectedDataTypes must be empty: no data leaves the device")
    for entry in data.get("NSPrivacyAccessedAPITypes") or []:
        category = entry.get("NSPrivacyAccessedAPIType")
        allowed = policy.IOS_REQUIRED_REASONS.get(category)
        if allowed is None:
            report.add("ios", rel, 0, f"API category {category} is not in policy.IOS_REQUIRED_REASONS")
            continue
        reasons = entry.get("NSPrivacyAccessedAPITypeReasons") or []
        if not reasons:
            report.add("ios", rel, 0, f"{category} declares no reason")
        for reason in reasons:
            if reason not in allowed:
                report.add("ios", rel, 0, f"reason {reason} for {category} is not in policy.IOS_REQUIRED_REASONS")


def _check_swift_packages(packages: dict, rel: str, report: Report) -> None:
    """Every Swift package is allow-listed and pinned to an exact version; no allow-list entry is stale."""
    used = set()
    for name, spec in packages.items():
        spec = spec or {}
        url = str(spec.get("url") or spec.get("path") or "")
        used.add(url)
        if url not in policy.IOS_SWIFT_PACKAGES:
            report.add("ios", rel, 0, f"Swift package {name} ({url}) is not in policy.IOS_SWIFT_PACKAGES")
        if not spec.get("exactVersion"):
            report.add("ios", rel, 0, f"Swift package {name} must be pinned with exactVersion")
    for url in policy.IOS_SWIFT_PACKAGES:
        if url not in used:
            report.add("ios", "tools/privacy-check/policy.py", 0,
                       f"stale allow-list entry: no Swift package {url} (remove it from IOS_SWIFT_PACKAGES)")


def check_ios(root: Path, report: Report) -> None:
    project_path = root / policy.IOS_PROJECT
    if project_path.is_file():
        rel = _rel(root, project_path)
        text = project_path.read_text(encoding="utf-8")
        try:
            project = parse_simple_yaml(text) or {}
        except ValueError as e:
            report.add("ios", rel, 0, str(e))
            project = {}
        if "CODE_SIGN_ENTITLEMENTS" in text:
            report.add("ios", rel, 0, "CODE_SIGN_ENTITLEMENTS needs a policy review (policy.IOS_ENTITLEMENTS)")
        _check_swift_packages(project.get("packages") or {}, rel, report)
        for target, spec in (project.get("targets") or {}).items():
            spec = spec or {}
            info = (spec.get("info") or {}).get("properties") or {}
            _check_info_keys(target, info, rel, report)
            _check_privacy_manifest_source(target, spec, project_path.parent, rel, report)
            entitlements = spec.get("entitlements") or {}
            if entitlements:
                properties = entitlements.get("properties") or {}
                _check_entitlements(properties or ["<entitlements file>"], f"target {target}", rel, report)
    for dirpath, dirnames, filenames in os.walk(root / "apps" / "ios"):
        dirnames[:] = sorted(d for d in dirnames if d not in ("build", "DerivedData")
                             and not d.endswith((".xcodeproj", ".xcassets")))
        for filename in sorted(filenames):
            path = Path(dirpath) / filename
            rel = _rel(root, path)
            if filename.endswith(".entitlements"):
                try:
                    keys = plistlib.loads(path.read_bytes()).keys()
                except Exception as e:  # noqa: BLE001 - any parse error is a violation
                    report.add("ios", rel, 0, f"cannot parse: {e}")
                    continue
                _check_entitlements(keys, filename, rel, report)
            elif filename.endswith(".xcprivacy"):
                _check_privacy_manifest(path, rel, report)


# ---------------------------------------------------------------------------------------------
# Agent bridge CLI (tools/agent-bridge, #183)


def check_agent_bridge(root: Path, report: Report) -> None:
    """The bridge on the PC talks only to the paired phone: network modules only where the policy
    allows them, no URLs, and runtime dependencies (transitive ones included) from the allow-list."""
    base = root / policy.AGENT_BRIDGE_DIR
    if not base.is_dir():
        return
    apis = [(api_id, re.compile(pattern, re.MULTILINE), what) for api_id, pattern, what in policy.AGENT_BRIDGE_GUARDED_APIS]
    used: set[tuple[str, str]] = set()
    for path in sorted((base / "src").rglob("*.ts")):
        rel = _rel(root, path)
        code = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
        allowed = policy.AGENT_BRIDGE_ALLOWED_API_USES.get(rel, {})
        for api_id, regex, what in apis:
            for match in regex.finditer(code):
                line = code.count("\n", 0, match.start()) + 1
                if api_id in allowed:
                    used.add((rel, api_id))
                    report.bridge_sites.append((rel, line, api_id, allowed[api_id]))
                else:
                    report.add("agent-bridge", rel, line, f"{match.group(0).strip()} ({what}) is only allowed in files "
                               "listed in policy.AGENT_BRIDGE_ALLOWED_API_USES")
        for match in URL_PATTERN.finditer(code):
            line = code.count("\n", 0, match.start()) + 1
            report.add("agent-bridge", rel, line, f"URL {match.group(0)}: the bridge talks to the paired phone only")
    for rel, allowed in policy.AGENT_BRIDGE_ALLOWED_API_USES.items():
        for api_id in allowed:
            if (rel, api_id) not in used:
                report.add("agent-bridge", rel, 0, f"stale allow-list entry: {api_id} is no longer used here "
                           "(remove it from policy.AGENT_BRIDGE_ALLOWED_API_USES)")

    package_rel = f"{policy.AGENT_BRIDGE_DIR}/package.json"
    try:
        package = json.loads((base / "package.json").read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        report.add("agent-bridge", package_rel, 0, f"cannot read: {e}")
        return
    dependencies = package.get("dependencies") or {}
    for name in dependencies:
        if name not in policy.AGENT_BRIDGE_DEPENDENCIES:
            report.add("agent-bridge", package_rel, 0, f"dependency {name} is not in policy.AGENT_BRIDGE_DEPENDENCIES")
    for name in policy.AGENT_BRIDGE_DEPENDENCIES:
        if name not in dependencies:
            report.add("agent-bridge", "tools/privacy-check/policy.py", 0,
                       f"stale allow-list entry: {name} is not a dependency (remove it from AGENT_BRIDGE_DEPENDENCIES)")
    all_names = list(dependencies) + list(package.get("devDependencies") or {})
    for name, pattern in policy.DENIED_DEPENDENCIES:
        for dep in all_names:
            if re.search(pattern, dep, re.IGNORECASE):
                report.add("agent-bridge", package_rel, 0, f"'{dep}' looks like a {name} dependency (policy.DENIED_DEPENDENCIES)")

    lock_rel = f"{policy.AGENT_BRIDGE_DIR}/package-lock.json"
    try:
        lock = json.loads((base / "package-lock.json").read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        report.add("agent-bridge", lock_rel, 0, f"cannot read (dependencies must be pinned by a lockfile): {e}")
        return
    for key, entry in (lock.get("packages") or {}).items():
        if not key or entry.get("dev"):
            continue
        name = key.rsplit("node_modules/", 1)[-1]
        if name not in policy.AGENT_BRIDGE_RUNTIME_PACKAGES:
            report.add("agent-bridge", lock_rel, 0, f"runtime package {name} is not in policy.AGENT_BRIDGE_RUNTIME_PACKAGES")
        if entry.get("hasInstallScript"):
            report.add("agent-bridge", lock_rel, 0, f"{name} has an install script")


# ---------------------------------------------------------------------------------------------


def run(root: Path, merged: dict[str, Path] | None = None, resolved: dict[str, Path] | None = None) -> Report:
    report = Report()
    check_android_manifests(root, report)
    check_sources(root, report)
    check_declared_dependencies(root, report)
    check_ios(root, report)
    check_agent_bridge(root, report)
    for module, path in (merged or {}).items():
        check_merged_manifest(root, module, path, report)
    for module, path in (resolved or {}).items():
        check_resolved_dependencies(root, module, path, report)
    return report


def summary_markdown(report: Report) -> str:
    lines = ["### Local-only check (#76)", ""]
    if report.violations:
        lines.append(f"**{len(report.violations)} violation(s)**")
        lines += [f"- `{v}`" for v in report.violations]
    else:
        lines.append("No violations.")
    lines += ["", "Network call sites (policy.ALLOWED_API_USES):", ""]
    lines += [f"- `{path}:{line}` {api}: {reason}" for path, line, api, reason in report.network_sites] or ["- none"]
    lines += ["", "Agent bridge on the PC (policy.AGENT_BRIDGE_ALLOWED_API_USES):", ""]
    lines += [f"- `{path}:{line}` {api}: {reason}" for path, line, api, reason in report.bridge_sites] or ["- none"]
    return "\n".join(lines) + "\n"


def _pairs(values: list[str], option: str) -> dict[str, Path]:
    result = {}
    for value in values:
        module, sep, path = value.partition("=")
        if not sep:
            raise SystemExit(f"{option} expects APP=PATH, got {value!r}")
        result[module] = Path(path)
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--merged-manifest", action="append", default=[], metavar="APP=PATH")
    parser.add_argument("--resolved-deps", action="append", default=[], metavar="APP=PATH")
    parser.add_argument("--summary", type=Path, help="append a Markdown summary (e.g. $GITHUB_STEP_SUMMARY)")
    args = parser.parse_args(argv)

    report = run(args.root, _pairs(args.merged_manifest, "--merged-manifest"),
                 _pairs(args.resolved_deps, "--resolved-deps"))
    for path, line, api, reason in report.network_sites:
        print(f"allowed network call site: {path}:{line} {api} ({reason})")
    for path, line, api, reason in report.bridge_sites:
        print(f"allowed agent bridge network site: {path}:{line} {api} ({reason})")
    for violation in report.violations:
        print(violation, file=sys.stderr)
    if args.summary:
        with args.summary.open("a", encoding="utf-8") as f:
            f.write(summary_markdown(report))
    if report.violations:
        print(f"\n{len(report.violations)} violation(s). Data must stay on the device (CLAUDE.md "
              "'Local only'). If the change is intended, extend tools/privacy-check/policy.py with a "
              "reason and update docs/architecture/02 §7 (see docs/dev/04).", file=sys.stderr)
        return 1
    print("Local-only check passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
