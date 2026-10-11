#!/usr/bin/env python3
"""Builds the roadmap dashboard (single HTML file) from the repo's GitHub issues.

Layout: releases (Milestones) -> use cases (parent issues labelled `usecase`)
-> work issues (their sub-issues), then dev-flow issues, then a health check
listing open issues that belong to neither.

Usage: python3 tools/roadmap-view/build.py <output.html>
Requires the `gh` CLI with REST access to the repo (GraphQL is not used).
"""
import html
import json
import re
import subprocess
import sys
from datetime import datetime, timezone, timedelta
from pathlib import Path

REPO = "Soichiro5/watch-audio"
JST = timezone(timedelta(hours=9))
PLATFORM_TAGS = {
    "watchOS": "watchOS", "wearOS": "Wear OS", "asr": "文字起こし", "sync": "転送", "ui": "UI",
    "search": "検索", "ai": "AI", "setup": "基盤", "release": "リリース", "spike": "検証",
}
STATUS = {  # key -> (label, css token)
    "doing": ("実装中", "doing"),
    "ready": ("着手可能", "ready"),
    "waiting": ("依存待ち", "waiting"),
    "device": ("実機待ち", "device"),
    "later": ("後回し（P2）", "later"),
    "done": ("完了", "done"),
}
STATUS_ORDER = ["doing", "ready", "waiting", "device", "later", "done"]


def gh(path):
    out = subprocess.run(["gh", "api", path], capture_output=True, text=True, check=True).stdout
    return json.loads(out)


def gh_all(path):
    """Fetches every page of a list endpoint (100 per page)."""
    sep = "&" if "?" in path else "?"
    items, page = [], 1
    while True:
        batch = gh(f"{path}{sep}per_page=100&page={page}")
        items += batch
        if len(batch) < 100:
            return items
        page += 1


def esc(s):
    return html.escape(str(s), quote=True)


def short_title(title):
    t = re.sub(r"^\[Spike\]\s*", "Spike: ", title)
    return re.sub(r"^\[[^\]]+\]\s*", "", t)          # drop "[M1-3] "


def label_names(issue):
    return [l["name"] for l in issue["labels"]]


def clean_description(text):
    """Milestone description without the trailing generator footer."""
    text = (text or "").split("\n---")[0]
    return text.strip()


# ---------------------------------------------------------------- data

issues = [i for i in gh_all(f"repos/{REPO}/issues?state=all") if "pull_request" not in i]
by_num = {i["number"]: i for i in issues}
milestones = sorted(gh_all(f"repos/{REPO}/milestones?state=all"), key=lambda m: m["number"])

usecases = sorted((i for i in issues if "usecase" in label_names(i)), key=lambda i: i["number"])
children = {}   # use case number -> [sub-issue numbers]
parent_of = {}  # work issue number -> use case number
for uc in usecases:
    subs = gh_all(f"repos/{REPO}/issues/{uc['number']}/sub_issues")
    children[uc["number"]] = [s["number"] for s in subs]
    for s in subs:
        by_num.setdefault(s["number"], s)
        parent_of[s["number"]] = uc["number"]


def work_item(n):
    """View model for one work issue (status mirrors the labels and open dependencies)."""
    i = by_num[n]
    labels = label_names(i)
    prio = next((p for p in ("P0", "P1", "P2") if p in labels), "")
    open_blockers = []
    if i["state"] == "open":
        blocked_by = [b["number"] for b in gh(f"repos/{REPO}/issues/{n}/dependencies/blocked_by")]
        open_blockers = [b for b in blocked_by if by_num.get(b, {}).get("state", "open") == "open"]
    if i["state"] == "closed":
        status = "done"
    elif "in-progress" in labels:
        status = "doing"
    elif "needs-device" in labels:
        status = "device"
    elif prio == "P2":
        status = "later"
    elif open_blockers:
        status = "waiting"
    else:
        status = "ready"
    return {
        "n": n, "title": short_title(i["title"]), "url": i["html_url"], "state": i["state"],
        "notPlanned": i.get("state_reason") == "not_planned", "status": status, "prio": prio,
        "device": "needs-device" in labels, "inProgress": "in-progress" in labels,
        "tags": [PLATFORM_TAGS[l] for l in labels if l in PLATFORM_TAGS],
        "openBlockers": open_blockers,
        "milestone": (i.get("milestone") or {}).get("number"),
    }


items = {}
for n in sorted(set(parent_of) | {i["number"] for i in issues if "dev-flow" in label_names(i)}):
    items[n] = work_item(n)


def usecase_view(uc):
    kids = [items[n] for n in children[uc["number"]]]
    summary = uc.get("sub_issues_summary") or {}
    total = summary.get("total", len(kids))
    completed = summary.get("completed", sum(1 for k in kids if k["state"] == "closed"))
    m = re.match(r"^(UC\d+)\s*[:：]\s*(.*)$", uc["title"])
    code, title = (m.group(1), m.group(2)) if m else ("", uc["title"])
    note = ""
    if uc["state"] == "open" and total and completed == total:
        if any(k["device"] for k in kids) or "実機" in (uc.get("body") or ""):
            note = "作業 Issue はすべて完了。実機での確認待ち"
        else:
            note = "作業 Issue はすべて完了。完了条件の確認待ち"
    elif uc["state"] == "open" and kids and all(k["device"] for k in kids if k["state"] == "open"):
        note = "残りは実機での確認のみ"
    ms = (uc.get("milestone") or {}).get("number")
    for k in kids:  # flag open work issues whose release differs from their use case's
        k["msMismatch"] = k["state"] == "open" and k["milestone"] != ms
    return {"n": uc["number"], "code": code, "title": title, "url": uc["html_url"], "state": uc["state"],
            "total": total, "completed": completed, "pct": summary.get("percent_completed", 0),
            "note": note, "kids": kids, "milestone": ms}


uc_views = [usecase_view(uc) for uc in usecases]
releases = []
for m in milestones:
    ucs = [u for u in uc_views if u["milestone"] == m["number"]]
    work = {k["n"]: k for u in ucs for k in u["kids"]}.values()
    releases.append({
        "title": m["title"], "description": clean_description(m.get("description")), "state": m["state"],
        "due": m["due_on"][:10] if m.get("due_on") else "", "url": m["html_url"], "ucs": ucs,
        "done": sum(1 for k in work if k["state"] == "closed"), "total": len(work),
    })
no_release = [u for u in uc_views if u["milestone"] not in {m["number"] for m in milestones}]
if no_release:
    releases.append({"title": "リリース未定", "description": "Milestone が付いていないユースケース。", "state": "open",
                     "due": "", "url": "", "ucs": no_release, "total": 0, "done": 0})

devflow = [items[n] for n in sorted(items) if "dev-flow" in label_names(by_num[n]) and n not in parent_of]
unclassified = [
    work_item(i["number"]) for i in issues
    if i["state"] == "open" and "usecase" not in label_names(i) and "dev-flow" not in label_names(i)
    and i["number"] not in parent_of
]
all_work = list({k["n"]: k for r in releases for u in r["ucs"] for k in u["kids"]}.values()) + devflow + unclassified

# ---------------------------------------------------------------- render


def bar(kids, total):
    if not total:
        return '<div class="bar"></div>'
    segs = []
    for s in ["done", "doing", "device", "ready", "waiting", "later"]:
        n = sum(1 for k in kids if k["status"] == s)
        if n:
            segs.append(f'<span title="{esc(STATUS[s][0])}: {n}" style="width:{n / total * 100:.2f}%;'
                        f'background:var(--{STATUS[s][1]})"></span>')
    return f'<div class="bar">{"".join(segs)}</div>'


def row(k):
    badges = []
    if k["prio"]:
        badges.append(f'<span class="prio {k["prio"]}">{k["prio"]}</span>')
    if k["inProgress"] and k["state"] == "open":
        badges.append('<span class="badge doing">実装中</span>')
    if k["device"]:
        badges.append('<span class="badge device">実機</span>')
    if k["notPlanned"]:
        badges.append('<span class="badge later">見送り</span>')
    if k.get("msMismatch"):
        badges.append('<span class="badge warn" title="ユースケースと違う Milestone が付いている">Milestone 不一致</span>')
    badges += [f'<span class="tag">{esc(t)}</span>' for t in k["tags"]]
    deps = ""
    if k["openBlockers"]:
        deps = f'<span class="deps">依存: {" ".join("#" + str(b) for b in k["openBlockers"])}</span>'
    label, token = STATUS[k["status"]]
    return (f'<li><a class="row" href="{esc(k["url"])}" target="_blank" rel="noopener" style="--stripe:var(--{token})">'
            f'<span class="num">#{k["n"]}</span>'
            f'<span class="title">{esc(k["title"])}</span>'
            f'<span class="state {token}">{esc(label)}</span>'
            f'<span class="meta">{"".join(badges)}{deps}</span></a></li>')


def work_list(kids, empty_text="なし"):
    order = {s: i for i, s in enumerate(STATUS_ORDER)}
    open_ = sorted((k for k in kids if k["state"] == "open"), key=lambda k: (order[k["status"]], k["prio"] or "P9", k["n"]))
    closed = sorted((k for k in kids if k["state"] == "closed"), key=lambda k: k["n"])
    out = f'<ul class="rows">{"".join(row(k) for k in open_)}</ul>' if open_ else f'<p class="empty">{esc(empty_text)}</p>'
    if closed:
        out += (f'<details class="closed"><summary>完了した作業 {len(closed)} 件</summary>'
                f'<ul class="rows">{"".join(row(k) for k in closed)}</ul></details>')
    return out


def usecase_html(u):
    state = '<span class="pill done">達成</span>' if u["state"] == "closed" else '<span class="pill open">進行中</span>'
    note = f'<p class="note">{esc(u["note"])}</p>' if u["note"] else ""
    return (f'<article class="uc{" is-closed" if u["state"] == "closed" else ""}" id="uc-{u["n"]}">'
            f'<div class="uc-head"><a class="uc-title" href="{esc(u["url"])}" target="_blank" rel="noopener">'
            f'<span class="code">{esc(u["code"])}</span> {esc(u["title"])}</a>{state}</div>'
            f'<div class="meter"><div class="meter-label"><span>作業 Issue の完了（#{u["n"]}）</span>'
            f'<b>{u["completed"]} / {u["total"]}</b></div>{bar(u["kids"], u["total"])}</div>'
            f'{note}{work_list(u["kids"], "残りの作業 Issue はありません")}</article>')


def release_html(r, idx):
    pct = r["done"] / r["total"] * 100 if r["total"] else 0
    due = f'<span class="due">期日 {esc(r["due"])}</span>' if r["due"] else ""
    closed = '<span class="pill done">完了</span>' if r["state"] == "closed" else ""
    title = (f'<a href="{esc(r["url"])}" target="_blank" rel="noopener">{esc(r["title"])}</a>'
             if r["url"] else esc(r["title"]))
    ucs = "".join(usecase_html(u) for u in r["ucs"]) or '<p class="empty">ユースケースはまだありません</p>'
    return (f'<section class="release panel" aria-labelledby="rel-{idx}">'
            f'<div class="rel-head"><div><h2 id="rel-{idx}">{title} {closed}{due}</h2>'
            f'<p class="desc">{esc(r["description"])}</p></div>'
            f'<div class="meter"><div class="meter-label"><span>作業 Issue の完了</span>'
            f'<b>{r["done"]} / {r["total"]}</b></div>'
            f'<div class="bar"><span style="width:{pct:.2f}%;background:var(--done)"></span></div></div></div>'
            f'<div class="ucs">{ucs}</div></section>')


current = next((r for r in releases if r["state"] == "open"), releases[0] if releases else None)
summary_chips = "".join(
    f'<a class="count" href="#rel-{i}"><span>{esc(r["title"])}</span><b>{r["done"]} / {r["total"]}</b></a>'
    for i, r in enumerate(releases))
status_chips = "".join(
    f'<span class="count"><span class="dot" style="background:var(--{STATUS[s][1]})"></span>{esc(STATUS[s][0])}'
    f'<b>{sum(1 for k in all_work if k["status"] == s and not k["notPlanned"])}</b></span>'
    for s in STATUS_ORDER)
if current:
    goal = f'現在のリリース: {esc(current["title"])} — {esc(current["description"])}'
    cur_pct = current["done"] / current["total"] * 100 if current["total"] else 0
    goal_meter = (f'<div class="meter" aria-label="現在のリリースの進捗"><div class="meter-label">'
                  f'<span>{esc(current["title"])} の作業 Issue</span><b>{current["done"]} / {current["total"]}</b></div>'
                  f'<div class="bar"><span style="width:{cur_pct:.2f}%;background:var(--done)"></span></div></div>')
else:
    goal, goal_meter = "", ""

unclassified_html = (
    f'<ul class="rows">{"".join(row(k) for k in unclassified)}</ul>' if unclassified
    else '<p class="empty ok">なし（開いている Issue はすべてユースケースか開発の進め方に属しています）</p>')

body = "".join(release_html(r, i) for i, r in enumerate(releases))
generated = datetime.now(JST).strftime("%Y-%m-%d %H:%M JST")
template = (Path(__file__).parent / "template.html").read_text(encoding="utf-8")
page = (template
        .replace("__GOAL__", goal)
        .replace("__GOAL_METER__", goal_meter)
        .replace("__RELEASE_COUNTS__", summary_chips)
        .replace("__STATUS_COUNTS__", status_chips)
        .replace("__RELEASES__", body)
        .replace("__DEVFLOW__", work_list(devflow, "開いている開発の進め方の Issue はありません"))
        .replace("__DEVFLOW_COUNT__", str(sum(1 for k in devflow if k["state"] == "open")))
        .replace("__UNCLASSIFIED__", unclassified_html)
        .replace("__UNCLASSIFIED_COUNT__", str(len(unclassified)))
        .replace("__FOOT__", esc(f"GitHub Issues と Milestone（{REPO}）から生成: {generated}。"
                                 "行をクリックすると GitHub の Issue が開きます。")))
out = sys.argv[1] if len(sys.argv) > 1 else "roadmap.html"
Path(out).write_text(page, encoding="utf-8")
print(f"wrote {out}: {len(releases)} releases, {len(uc_views)} use cases, "
      + ", ".join(f'{r["title"]} {r["done"]}/{r["total"]}' for r in releases)
      + f", dev-flow {len(devflow)}, unclassified {len(unclassified)}")
