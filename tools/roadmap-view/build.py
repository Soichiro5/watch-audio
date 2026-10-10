#!/usr/bin/env python3
"""Builds the roadmap dashboard (single HTML file) from the repo's GitHub issues.

Usage: python3 tools/roadmap-view/build.py <output.html>
Requires the `gh` CLI with REST access to the repo.
"""
import html
import json
import re
import subprocess
import sys
from datetime import datetime, timezone, timedelta

REPO = "Soichiro5/watch-audio"
JST = timezone(timedelta(hours=9))


def gh(path):
    out = subprocess.run(["gh", "api", path], capture_output=True, text=True, check=True).stdout
    return json.loads(out)


def short_title(title):
    t = re.sub(r"^\[[^\]]+\]\s*", "", title)          # drop "[M1-3] "
    t = re.sub(r"^\[Spike\]\s*", "Spike: ", t)
    return t


def code_of(title):
    m = re.match(r"^\[([^\]]+)\]", title)
    return m.group(1) if m else ""


issues = [i for i in gh(f"repos/{REPO}/issues?state=all&per_page=100") if "pull_request" not in i]
by_num = {i["number"]: i for i in issues}
epics = {i["number"] for i in issues if any(l["name"] == "epic" for l in i["labels"])}

items = []
for i in issues:
    n = i["number"]
    if n in epics:
        continue
    labels = [l["name"] for l in i["labels"]]
    blocked_by = [b["number"] for b in gh(f"repos/{REPO}/issues/{n}/dependencies/blocked_by")] if i["state"] == "open" else []
    open_blockers = [b for b in blocked_by if by_num.get(b, {}).get("state") == "open"]
    prio = next((p for p in ("P0", "P1", "P2") if p in labels), "P2")
    if i["state"] == "closed":
        status = "done"
    elif "needs-device" in labels:
        status = "device"
    elif "in-progress" in labels:
        status = "doing"
    elif prio == "P2":
        status = "later"
    elif open_blockers:
        status = "waiting"
    else:
        status = "ready"
    milestone = next((l for l in labels if re.fullmatch(r"M\d", l)), "P2" if "phase2" in labels else "")
    platforms = [l for l in labels if l in ("watchOS", "wearOS", "asr", "sync", "ui", "search", "ai", "setup", "release", "spike")]
    items.append({
        "n": n, "code": code_of(i["title"]), "title": short_title(i["title"]), "state": i["state"],
        "status": status, "prio": prio, "milestone": milestone, "tags": platforms,
        "blockedBy": blocked_by, "openBlockers": open_blockers,
        "stateReason": i.get("state_reason"), "url": i["html_url"],
    })

items.sort(key=lambda x: (x["milestone"] or "Z", x["code"] or "Z9", x["n"]))
blocks = {}  # n -> issues it blocks
for it in items:
    for b in it["blockedBy"]:
        blocks.setdefault(b, []).append(it["n"])
for it in items:
    it["blocks"] = sorted(blocks.get(it["n"], []))

core = [x for x in items if x["prio"] == "P0" and x["stateReason"] != "not_planned"]
core_done = sum(1 for x in core if x["state"] == "closed")

# Mermaid: P0/P1 dependency flow, colored by status.
graph_items = [x for x in items if x["prio"] in ("P0", "P1") and x["stateReason"] != "not_planned"]
ids = {x["n"] for x in graph_items}
lines = ["flowchart LR"]
for x in graph_items:
    label = f'#{x["n"]} {x["title"]}'
    label = re.sub(r"[（(].*?[）)]", "", label).replace('"', "'")[:28]
    lines.append(f'  i{x["n"]}["{label}"]:::{x["status"]}')
for x in graph_items:
    for b in x["blockedBy"]:
        if b in ids:
            lines.append(f"  i{b} --> i{x['n']}")
lines += [
    "  classDef done fill:#d9efe0,stroke:#2f7d4f,color:#173d27",
    "  classDef doing fill:#dbe7fb,stroke:#2f5fb3,color:#14284d",
    "  classDef ready fill:#fff1c9,stroke:#b07d00,color:#3d2c00",
    "  classDef waiting fill:#eceef3,stroke:#7a8296,color:#2a2f3a",
    "  classDef device fill:#fbe0d6,stroke:#b4461f,color:#4a1a09",
    "  classDef later fill:#f3f3f3,stroke:#aaa,color:#555",
]
for x in graph_items:
    lines.append(f'  click i{x["n"]} "{x["url"]}" _blank')
mermaid = "\n".join(lines)

data = {
    "generatedAt": datetime.now(JST).strftime("%Y-%m-%d %H:%M JST"),
    "items": items,
    "coreTotal": len(core),
    "coreDone": core_done,
    "repo": REPO,
}

template = open(__file__.replace("build.py", "template.html"), encoding="utf-8").read()
page = template.replace("/*__DATA__*/null", json.dumps(data, ensure_ascii=False)).replace("__MERMAID__", html.escape(mermaid))
out = sys.argv[1] if len(sys.argv) > 1 else "roadmap.html"
open(out, "w", encoding="utf-8").write(page)
print(f"wrote {out}: {len(items)} issues, core {core_done}/{len(core)}")
