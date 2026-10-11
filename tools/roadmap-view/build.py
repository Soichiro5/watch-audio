#!/usr/bin/env python3
"""Builds the roadmap dashboard (single HTML file) from the repo's GitHub issues.

Layout: a status legend and per-release use-case counts, then releases
(Milestones; the current one first) each with one headline (overall status,
what happens next and who acts) and one row per use case (parent issues
labelled `usecase`; their work issues = sub-issues are collapsed), then
dev-flow issues, then a health check listing open issues that belong to neither.

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
UC_STATUS = {  # use-case status key -> (icon, label, css token, legend text)
    "done": ("✅", "完了", "done", "ユースケースの Issue が閉じている"),
    "device": ("🧪", "実機確認待ち", "device", "開発環境での作業は完了。残りは実機での確認だけ"),
    "dev": ("🛠", "開発中", "doing", "実機確認以外の作業 Issue が残っている"),
    "todo": ("⬜", "未着手", "later", "完了した作業 Issue も実装中の作業 Issue もない"),
}
UC_ORDER = ["todo", "dev", "device", "done"]  # least advanced first
STATUS = {  # work-issue status key -> (label, css token)
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
        "device": "needs-device" in labels, "inProgress": "in-progress" in labels, "spike": "spike" in labels,
        "tags": [PLATFORM_TAGS[l] for l in labels if l in PLATFORM_TAGS],
        "openBlockers": open_blockers,
        "milestone": (i.get("milestone") or {}).get("number"),
    }


items = {}
for n in sorted(set(parent_of) | {i["number"] for i in issues if "dev-flow" in label_names(i)}):
    items[n] = work_item(n)


def usecase_status(state, kids):
    """One status per use case, computed from the use case and its sub-issues.

    done   - the use-case issue is closed
    device - open; every open sub-issue is `needs-device` (or none is left open):
             done in the dev environment, only real-device checks remain
    todo   - open; no sub-issue is closed or `in-progress` (also: no sub-issues at all)
    dev    - open; at least one open sub-issue still needs development
    """
    if state == "closed":
        return "done"
    if not kids:
        return "todo"
    if all(k["device"] for k in kids if k["state"] == "open"):
        return "device"
    if not any(k["state"] == "closed" or k["inProgress"] for k in kids):
        return "todo"
    return "dev"


def usecase_view(uc):
    kids = [items[n] for n in children[uc["number"]]]
    m = re.match(r"^(UC\d+)\s*[:：]\s*(.*)$", uc["title"])
    code, title = (m.group(1), m.group(2)) if m else ("", uc["title"])
    ms = (uc.get("milestone") or {}).get("number")
    for k in kids:  # flag open work issues whose release differs from their use case's
        k["msMismatch"] = k["state"] == "open" and k["milestone"] != ms
    return {"n": uc["number"], "code": code, "title": title, "url": uc["html_url"], "state": uc["state"],
            "status": usecase_status(uc["state"], kids), "kids": kids, "milestone": ms,
            "completed": sum(1 for k in kids if k["state"] == "closed")}


def by_priority(ks):
    """Order for "next" lists: regular work before spikes (measurements), then priority, then number."""
    return sorted(ks, key=lambda k: (k["spike"], k["prio"] or "P9", k["n"]))


def next_step(ucs):
    """What a release needs next and who acts: (overall status, text, owner, issues).

    Overall status is the least advanced use case. Next step: open non-device work of
    the use cases in development (Claude), else the open device checks (the owner).
    """
    if not ucs:
        return "todo", "ユースケースはまだありません", "", []
    overall = min((u["status"] for u in ucs), key=UC_ORDER.index)
    dev = [u for u in ucs if u["status"] == "dev"]
    device = [u for u in ucs if u["status"] == "device"]
    if dev:
        ks = {k["n"]: k for u in dev for k in u["kids"] if k["state"] == "open" and not k["device"]}
        return overall, "", "Claude（開発）", by_priority(ks.values())
    if device:
        ks = {k["n"]: k for u in device for k in u["kids"] if k["state"] == "open"}
        text = "" if ks else "実機で完了条件を確かめて、ユースケースの Issue を閉じる"
        return overall, text, "オーナー（実機）", by_priority(ks.values())
    if overall == "done":
        return overall, "なし（すべて完了）", "", []
    all_p2 = all(k["prio"] == "P2" for u in ucs for k in u["kids"] if k["state"] == "open")
    return overall, "未着手（P2）" if all_p2 else "未着手", "", []


uc_views = [usecase_view(uc) for uc in usecases]
releases = []
for m in milestones:
    ucs = [u for u in uc_views if u["milestone"] == m["number"]]
    releases.append({
        "title": m["title"], "description": clean_description(m.get("description")), "state": m["state"],
        "due": m["due_on"][:10] if m.get("due_on") else "", "url": m["html_url"], "ucs": ucs,
    })
no_release = [u for u in uc_views if u["milestone"] not in {m["number"] for m in milestones}]
if no_release:
    releases.append({"title": "リリース未定", "description": "Milestone が付いていないユースケース。", "state": "open",
                     "due": "", "url": "", "ucs": no_release})
for r in releases:
    r["overall"], r["nextText"], r["owner"], r["nextIssues"] = next_step(r["ucs"])
# Current release (first open milestone) first, then the other open ones, closed releases last.
releases.sort(key=lambda r: r["state"] != "open")

devflow = [items[n] for n in sorted(items) if "dev-flow" in label_names(by_num[n]) and n not in parent_of]
unclassified = [
    work_item(i["number"]) for i in issues
    if i["state"] == "open" and "usecase" not in label_names(i) and "dev-flow" not in label_names(i)
    and i["number"] not in parent_of
]

# ---------------------------------------------------------------- render


def compact_title(title, limit=34):
    """Short form for one-line lists: drops parenthesised details and truncates."""
    t = re.split(r"[（(]", title, maxsplit=1)[0].strip() or title
    return t if len(t) <= limit else t[:limit - 1] + "…"


def short_release(title):
    m = re.match(r"^(v\d+(?:\.\d+)*)\b", title)
    return m.group(1) if m else title


def badge(status, big=False):
    icon, label, token, _ = UC_STATUS[status]
    return f'<span class="ucs-badge {token}{" big" if big else ""}">{icon} {esc(label)}</span>'


def issue_link(k, mark_device=True):
    marks = ""
    if k["inProgress"]:
        marks += '<span class="mini doing">実装中</span>'
    if k["device"] and mark_device:
        marks += '<span class="mini device">実機</span>'
    return (f'<a class="ilink" href="{esc(k["url"])}" target="_blank" rel="noopener" title="{esc(k["title"])}">'
            f'<span class="num">#{k["n"]}</span> {esc(compact_title(k["title"]))}{marks}</a>')


def inline_list(ks, limit=4):
    mixed = not all(k["device"] for k in ks)  # the 実機 mark only helps when the list mixes both kinds
    shown = "、".join(issue_link(k, mark_device=mixed) for k in ks[:limit])
    if len(ks) > limit:
        shown += f'、<span class="more">ほか {len(ks) - limit} 件</span>'
    return shown


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


def work_list(kids):
    order = {s: i for i, s in enumerate(STATUS_ORDER)}
    open_ = sorted((k for k in kids if k["state"] == "open"), key=lambda k: (order[k["status"]], k["prio"] or "P9", k["n"]))
    closed = sorted((k for k in kids if k["state"] == "closed"), key=lambda k: k["n"])
    out = ""
    if open_:
        out += f'<h4>残りの作業</h4><ul class="rows">{"".join(row(k) for k in open_)}</ul>'
    if closed:
        out += f'<h4>完了した作業</h4><ul class="rows">{"".join(row(k) for k in closed)}</ul>'
    return out


def usecase_html(u):
    open_kids = by_priority(k for k in u["kids"] if k["state"] == "open")
    if u["status"] == "done":
        rest = "完了"
    elif open_kids:
        rest = "残り: " + inline_list(open_kids)
    elif u["kids"]:
        rest = "残り: 作業 Issue はすべて完了。実機で完了条件を確かめて閉じる"
    else:
        rest = "残り: 作業 Issue がまだありません"
    details = (f'<details class="work"><summary>作業 Issue（完了 {u["completed"]} / {len(u["kids"])}）</summary>'
               f'{work_list(u["kids"])}</details>') if u["kids"] else ""
    return (f'<li class="uc" id="uc-{u["n"]}">{badge(u["status"])}'
            f'<div class="uc-body"><a class="uc-title" href="{esc(u["url"])}" target="_blank" rel="noopener">'
            f'<span class="code">{esc(u["code"] or "#" + str(u["n"]))}</span> {esc(u["title"])}</a>'
            f'<p class="rest">{rest}</p>{details}</div></li>')


def headline_html(r):
    what = inline_list(r["nextIssues"], limit=6) if r["nextIssues"] else esc(r["nextText"])
    owner = (f'<span class="sep">/</span><span class="owner">担当: <b>{esc(r["owner"])}</b></span>'
             if r["owner"] else "")
    return (f'<div class="headline">{badge(r["overall"], big=True)}'
            f'<p class="next"><span class="k">次にやること:</span> {what}{owner}</p></div>')


def release_html(r, idx, is_current):
    due = f'<span class="due">期日 {esc(r["due"])}</span>' if r["due"] else ""
    closed = '<span class="pill">クローズ済み</span>' if r["state"] == "closed" else ""
    cur = '<span class="pill cur">現在のリリース</span>' if is_current else ""
    title = (f'<a href="{esc(r["url"])}" target="_blank" rel="noopener">{esc(r["title"])}</a>'
             if r["url"] else esc(r["title"]))
    desc = f'<p class="desc">{esc(r["description"])}</p>' if is_current and r["description"] else ""
    ucs = (f'<ul class="uc-list">{"".join(usecase_html(u) for u in r["ucs"])}</ul>' if r["ucs"]
           else '<p class="empty">ユースケースはまだありません</p>')
    return (f'<section class="release panel {"current" if is_current else "compact"}" id="rel-{idx}" '
            f'aria-labelledby="rel-{idx}-h"><div class="rel-head"><h2 id="rel-{idx}-h">{cur}{title}{closed}{due}</h2>'
            f'{desc}</div>{headline_html(r)}{ucs}</section>')


def counts_html(r, idx):
    n = {s: sum(1 for u in r["ucs"] if u["status"] == s) for s in UC_ORDER}
    parts = [f'{UC_STATUS[s][0]} {n[s]}' for s in ("dev", "device", "todo") if n[s]] + [f'✅ {n["done"]}']
    tip = "、".join(f"{UC_STATUS[s][1]} {n[s]}" for s in UC_ORDER)
    return (f'<a class="count" href="#rel-{idx}" title="{esc(r["title"])}: {esc(tip)}">'
            f'<span>{esc(short_release(r["title"]))}:</span><b>{" / ".join(parts)}</b></a>')


legend = "".join(f'<li>{badge(s)}<span>{esc(UC_STATUS[s][3])}</span></li>' for s in ("done", "device", "dev", "todo"))
current = releases[0] if releases and releases[0]["state"] == "open" else None
goal = f'現在のリリース: {esc(current["title"])} — {esc(current["description"])}' if current else ""
release_counts = "".join(counts_html(r, i) for i, r in enumerate(releases))

open_devflow = sorted((k for k in devflow if k["state"] == "open"),
                      key=lambda k: (not k["inProgress"], k["prio"] or "P9", k["n"]))
devflow_html = (f'<ul class="flow">{"".join(f"<li>{issue_link(k)}</li>" for k in open_devflow)}</ul>' if open_devflow
                else '<p class="empty">開いている開発の進め方の Issue はありません</p>')
unclassified_html = (
    f'<ul class="rows">{"".join(row(k) for k in unclassified)}</ul>' if unclassified
    else '<p class="empty ok">なし（開いている Issue はすべてユースケースか開発の進め方に属しています）</p>')

body = "".join(release_html(r, i, r is current) for i, r in enumerate(releases))
generated = datetime.now(JST).strftime("%Y-%m-%d %H:%M JST")
template = (Path(__file__).parent / "template.html").read_text(encoding="utf-8")
page = (template
        .replace("__GOAL__", goal)
        .replace("__LEGEND__", legend)
        .replace("__RELEASE_COUNTS__", release_counts)
        .replace("__RELEASES__", body)
        .replace("__DEVFLOW__", devflow_html)
        .replace("__DEVFLOW_COUNT__", str(len(open_devflow)))
        .replace("__UNCLASSIFIED__", unclassified_html)
        .replace("__UNCLASSIFIED_COUNT__", str(len(unclassified)))
        .replace("__FOOT__", esc(f"GitHub Issues と Milestone（{REPO}）から生成: {generated}。"
                                 "Issue 番号やタイトルをクリックすると GitHub の Issue が開きます。")))
out = sys.argv[1] if len(sys.argv) > 1 else "roadmap.html"
Path(out).write_text(page, encoding="utf-8")
print(f"wrote {out}: {len(releases)} releases, {len(uc_views)} use cases; "
      + "; ".join(f'{short_release(r["title"])}={r["overall"]} '
                  f'[{" ".join(u["code"] + ":" + u["status"] for u in r["ucs"])}]' for r in releases)
      + f"; dev-flow open {len(open_devflow)}, unclassified {len(unclassified)}")


