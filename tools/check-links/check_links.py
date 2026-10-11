#!/usr/bin/env python3
"""Checks the repository's Markdown for broken links (standard library only).

- Every relative link and image in every Markdown file (inline `[text](target)`, reference
  definitions `[id]: target`, HTML `href` / `src`): the target file or directory must exist,
  and an `#anchor` must match a heading (GitHub's anchor rules) or an `id` / `name` in the target.
- CLAUDE.md also names repository paths without links (code spans, the Repo map table, the
  commands block): those paths, `cd <dir>` targets and Gradle project paths (`:module:task`)
  must exist too.

External links (http, mailto, ...) are not fetched. Code blocks and code spans are not links.

Usage: python3 tools/check-links/check_links.py [--root DIR] [files ...]
Exits 1 when something is broken (each problem is printed as path:line: message).
"""

from __future__ import annotations

import argparse
import glob
import os
import re
import subprocess
import sys
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import unquote

# Files whose code spans and commands are checked as repository paths as well.
PATH_CHECKED_FILES = ("CLAUDE.md",)

SKIPPED_DIRS = {".git", ".gradle", ".kotlin", ".idea", ".claude", "build", "node_modules", "DerivedData"}

FENCE = re.compile(r"^\s{0,3}(```+|~~~+)")
ATX_HEADING = re.compile(r"^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$")
SETEXT_UNDERLINE = re.compile(r"^\s{0,3}(=+|-+)\s*$")
CODE_SPAN = re.compile(r"(`+)(.+?)\1")
# [text](target "title") and ![alt](target); the text may hold one level of brackets (badges).
INLINE_LINK = re.compile(r"!?\[(?:[^\[\]]|\[[^\[\]]*\])*\]\(\s*(<[^>]*>|[^)\s]+)(?:\s+(?:\"[^\"]*\"|'[^']*'))?\s*\)")
REFERENCE_DEF = re.compile(r"^\s{0,3}\[[^\]]+\]:\s*(<[^>]*>|\S+)")
HTML_LINK = re.compile(r"""<(?:a|img)\b[^>]*?\b(?:href|src)\s*=\s*["']([^"']+)["']""", re.IGNORECASE)
HTML_ANCHOR = re.compile(r"""<[a-z]+\b[^>]*?\b(?:id|name)\s*=\s*["']([^"']+)["']""", re.IGNORECASE)
SCHEME = re.compile(r"^[a-zA-Z][a-zA-Z0-9+.-]*:")
GRADLE_PATH = re.compile(r"^:[A-Za-z][\w-]*(?::[A-Za-z][\w-]*)+$")
GRADLE_INCLUDE = re.compile(r"""include\(\s*["'](:[^"']+)["']\s*\)""")
FILE_EXTENSION = re.compile(r"\.(?:md|kts?|swift|py|sh|ya?ml|json|toml|properties|pro|xml|html|txt|xcconfig)$")


@dataclass(frozen=True)
class Problem:
    path: str
    line: int
    message: str

    def __str__(self) -> str:
        return f"{self.path}:{self.line}: {self.message}"


def github_slug(heading: str) -> str:
    """GitHub's heading anchor (github-slugger): lower case, punctuation and symbols dropped,
    spaces to hyphens. Letters of any script, digits, marks, `-` and `_` stay."""
    text = re.sub(r"!?\[([^\]]*)\]\([^)]*\)", r"\1", heading)  # links and images -> their text
    text = re.sub(r"<[^>]+>", "", text)  # inline HTML
    text = text.replace("`", "").replace("*", "")
    out = []
    for ch in text.strip().lower():
        if ch in " -_":
            out.append("-" if ch == " " else ch)
        elif unicodedata.category(ch)[0] in "LNM":
            out.append(ch)
    return "".join(out)


def _lines_outside_code(text: str):
    """Yields (line number, line, in_fence) with fenced code blocks marked."""
    fence = None
    for number, line in enumerate(text.splitlines(), start=1):
        match = FENCE.match(line)
        if fence is None and match:
            fence = match.group(1)[0] * len(match.group(1))
            yield number, line, True
            continue
        if fence is not None:
            if line.strip().startswith(fence) and set(line.strip()) <= {fence[0]}:
                fence = None
            yield number, line, True
            continue
        yield number, line, False


def anchors_of(text: str) -> set[str]:
    """Anchors a Markdown file provides: heading slugs (with -1, -2 for repeats) and HTML ids."""
    anchors: set[str] = set()
    seen: dict[str, int] = {}
    previous = None

    def add(heading: str) -> None:
        slug = github_slug(heading)
        count = seen.get(slug, 0)
        seen[slug] = count + 1
        anchors.add(slug if count == 0 else f"{slug}-{count}")

    for _, line, in_fence in _lines_outside_code(text):
        if in_fence:
            previous = None
            continue
        anchors.update(HTML_ANCHOR.findall(line))
        heading = ATX_HEADING.match(line)
        if heading:
            add(heading.group(2))
            previous = None
            continue
        if previous and previous.strip() and SETEXT_UNDERLINE.match(line) and not previous.lstrip().startswith(("|", "-", "*")):
            add(previous.strip())
            previous = None
            continue
        previous = line
    return anchors


def links_of(text: str):
    """Yields (line number, target) for every link outside code."""
    for number, line, in_fence in _lines_outside_code(text):
        if in_fence:
            continue
        line = CODE_SPAN.sub(lambda m: " " * len(m.group(0)), line)
        for match in INLINE_LINK.finditer(line):
            yield number, match.group(1)
        reference = REFERENCE_DEF.match(line)
        if reference:
            yield number, reference.group(1)
        for target in HTML_LINK.findall(line):
            yield number, target


class Checker:
    def __init__(self, root: Path):
        self.root = root.resolve()
        self._anchors: dict[Path, set[str]] = {}
        self._gradle_projects: set[str] | None = None

    def rel(self, path: Path) -> str:
        return path.resolve().relative_to(self.root).as_posix()

    def anchors(self, path: Path) -> set[str]:
        if path not in self._anchors:
            self._anchors[path] = anchors_of(path.read_text(encoding="utf-8"))
        return self._anchors[path]

    def gradle_projects(self) -> set[str]:
        if self._gradle_projects is None:
            settings = self.root / "settings.gradle.kts"
            projects: set[str] = set()
            if settings.is_file():
                for include in GRADLE_INCLUDE.findall(settings.read_text(encoding="utf-8")):
                    parts = include.strip(":").split(":")
                    projects.update(":" + ":".join(parts[:i]) for i in range(1, len(parts) + 1))
            self._gradle_projects = projects
        return self._gradle_projects

    def check_link(self, source: Path, line: int, target: str) -> Problem | None:
        target = target.strip("<>").strip()
        if not target or SCHEME.match(target) or target.startswith("//"):
            return None
        path_part, _, anchor = target.partition("#")
        path_part = unquote(path_part.split("?", 1)[0])
        if path_part:
            base = self.root if path_part.startswith("/") else source.parent
            resolved = (base / path_part.lstrip("/")).resolve()
            try:
                resolved.relative_to(self.root)
            except ValueError:
                return Problem(self.rel(source), line, f"link leaves the repository: {target}")
            if not resolved.exists():
                return Problem(self.rel(source), line, f"missing link target: {target}")
        else:
            resolved = source.resolve()
        if anchor and resolved.is_file() and resolved.suffix.lower() == ".md":
            if unquote(anchor) not in self.anchors(resolved):
                return Problem(self.rel(source), line, f"missing anchor: {target}")
        return None

    def path_exists(self, token: str) -> bool:
        token = token.rstrip("/.,;:)")
        if not token:
            return True
        pattern = token.replace("...", "**").replace("…", "**")
        if (self.root / pattern).exists():
            return True
        # Globs (shared/core-*, testdata/*.json, a/.../b) and shorthand prefixes (docs/03 -> docs/03-roadmap.md).
        return bool(glob.glob(str(self.root / pattern), recursive=True)
                    or glob.glob(str(self.root / pattern) + "*"))

    def looks_like_repo_path(self, token: str, first_cell: bool) -> bool:
        if not token or any(c in token for c in "<>{}$=@#\"'") or "://" in token or token.startswith(("-", "~")):
            return False
        if first_cell:
            return True
        if "/" not in token:
            return False
        first = token.lstrip("./").split("/", 1)[0]
        return (self.root / first).exists() or bool(FILE_EXTENSION.search(token.rstrip("/")))

    def check_paths(self, source: Path, text: str) -> list[Problem]:
        """Repository paths, `cd` targets and Gradle project paths named in code spans and blocks."""
        problems: list[Problem] = []
        rel = self.rel(source)

        def check_tokens(number: int, chunk: str, first_cell: bool = False) -> None:
            tokens = chunk.split()
            for index, token in enumerate(tokens):
                if token in ("&&", "||", "|", ";"):
                    continue
                if GRADLE_PATH.match(token):
                    project = token.rsplit(":", 1)[0]
                    if project not in self.gradle_projects():
                        problems.append(Problem(rel, number, f"Gradle project not in settings.gradle.kts: {token}"))
                    continue
                after_cd = index > 0 and tokens[index - 1] == "cd"
                if after_cd and not (self.root / token).is_dir():
                    problems.append(Problem(rel, number, f"missing directory: cd {token}"))
                elif not after_cd and self.looks_like_repo_path(token, first_cell) and not self.path_exists(token):
                    problems.append(Problem(rel, number, f"missing repository path: {token}"))

        for number, line, in_fence in _lines_outside_code(text):
            if in_fence:
                # Commands; comment lines and trailing comments are prose.
                if not FENCE.match(line) and not line.lstrip().startswith("#"):
                    check_tokens(number, line.split(" #", 1)[0])
                continue
            if line.lstrip().startswith("|"):
                cells = line.strip().strip("|").split("|")
                for match in CODE_SPAN.finditer(cells[0] if cells else ""):
                    check_tokens(number, match.group(2), first_cell=True)
                rest = "|".join(cells[1:])
            else:
                rest = line
            for match in CODE_SPAN.finditer(rest):
                check_tokens(number, match.group(2))
        return problems

    def check_file(self, source: Path) -> list[Problem]:
        text = source.read_text(encoding="utf-8")
        problems = [p for number, target in links_of(text) if (p := self.check_link(source, number, target))]
        if self.rel(source) in PATH_CHECKED_FILES:
            problems += self.check_paths(source, text)
        return problems


def markdown_files(root: Path) -> list[Path]:
    """Tracked and new (not ignored) Markdown files; without git, a walk that skips build output."""
    try:
        output = subprocess.run(
            ["git", "-C", str(root), "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", "*.md"],
            check=True, capture_output=True, text=True).stdout
        return sorted({root / name for name in output.split("\0") if name and (root / name).is_file()})
    except (OSError, subprocess.CalledProcessError):
        found = []
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = sorted(d for d in dirnames if d not in SKIPPED_DIRS)
            found += [Path(dirpath) / f for f in filenames if f.endswith(".md")]
        return sorted(found)


def run(root: Path, files: list[Path] | None = None) -> list[Problem]:
    checker = Checker(root)
    problems: list[Problem] = []
    for source in files if files is not None else markdown_files(checker.root):
        problems += checker.check_file(source)
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("files", nargs="*", type=Path, help="Markdown files (default: all in the repository)")
    args = parser.parse_args(argv)
    files = [f.resolve() for f in args.files] or None
    problems = run(args.root, files)
    for problem in problems:
        print(problem)
    checked = len(files) if files else len(markdown_files(args.root.resolve()))
    print(f"{checked} Markdown files checked, {len(problems)} broken link(s)", file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
