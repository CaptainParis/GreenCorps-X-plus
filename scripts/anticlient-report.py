#!/usr/bin/env python3
"""Summarize Anti-Client JSONL session files: precision/recall, lead times, tuning hints.

Usage: python3 scripts/anticlient-report.py <session.jsonl> [more.jsonl ...]
Files live in <minecraft dir>/greencorps/anti-client/. Standard library only.
"""
import json
import statistics
import sys
from collections import defaultdict


def pct(vals, p):
    if not vals:
        return None
    s = sorted(vals)
    return s[max(0, min(len(s) - 1, int(-(-p / 100.0 * len(s) // 1)) - 1))]


def fmt(vals):
    if not vals:
        return "n=0"
    return "n=%d min=%.1f p50=%.1f p95=%.1f max=%.1f" % (
        len(vals), min(vals), pct(vals, 50), pct(vals, 95), max(vals))


def load(paths):
    for path in paths:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    yield json.loads(line)
                except json.JSONDecodeError:
                    continue


def main(paths):
    by_sc = defaultdict(lambda: defaultdict(list))
    counts = defaultdict(lambda: defaultdict(int))
    misses = []
    for ev in load(paths):
        t = ev.get("type")
        if "fr" in ev or t in ("tk", "flight_dump"):
            continue
        sc = ev.get("sc", "none")
        c = counts[sc]
        d = by_sc[sc]
        if t == "commit":
            c["commits"] += 1
            d["commit_score"].append(ev.get("score", 0))
        elif t == "commit_fp":
            c["fp_" + ev.get("arch", "?")] += 1
            d["fp_score"].append(ev.get("score", 0))
        elif t == "hit":
            arch = ev.get("arch", "NONE")
            if arch == "NONE":
                c["hits_other"] += 1
            elif ev.get("miss"):
                c["miss_" + arch] += 1
                misses.append((sc, arch, ev.get("top_score", 0), ev.get("dmg", 0)))
            else:
                c["tp_" + arch] += 1
                d["lead"].append(ev.get("lead", 0))
                d["eta_err"].append(ev.get("eta_err", 0))
            if arch == "MACE" and ev.get("dmg"):
                d["mace_dmg"].append(ev["dmg"])
            if arch == "CRYSTAL" and ev.get("dmg"):
                d["crystal_dmg"].append(ev["dmg"])
                if "pred_after_armor" in ev:
                    d["crystal_err"].append(ev["dmg"] - ev["pred_after_armor"])
        elif t == "dive_land":
            d["land_err_blocks"].append(ev.get("err_blocks", 0))
            d["land_err_ticks"].append(ev.get("err_ticks", 0))
        elif t == "crystal_gone":
            d["crystal_life"].append(ev.get("life", 0))
        elif t == "refill":
            d["refill"].append(ev.get("ticks", 0))
        elif t == "totem_pop" and ev.get("who") == "self":
            c["pops"] += 1
        elif t == "survival":
            c["survival_" + ev.get("kind", "?")] += 1
        elif t == "safety":
            c["safety_" + ev.get("mode", "?")] += 1
        elif t == "death":
            c["deaths"] += 1
        elif t == "mark":
            c["marks"] += 1
        elif t == "explosion" and "kb_mag" in ev:
            d["kb_mag"].append(ev["kb_mag"])

    for sc in sorted(counts):
        c, d = counts[sc], by_sc[sc]
        print("=== scenario %s ===" % sc)
        for arch in ("MACE", "CRYSTAL"):
            tp, fp, ms = c["tp_" + arch], c["fp_" + arch], c["miss_" + arch]
            prec = tp / (tp + fp) if tp + fp else float("nan")
            rec = tp / (tp + ms) if tp + ms else float("nan")
            print("%-8s TP=%d FP=%d MISS=%d precision=%.2f recall=%.2f" % (arch, tp, fp, ms, prec, rec))
        print("lead ticks      :", fmt(d["lead"]))
        print("eta error ticks :", fmt(d["eta_err"]))
        print("landing err blk :", fmt(d["land_err_blocks"]))
        print("landing err tick:", fmt(d["land_err_ticks"]))
        print("crystal life    :", fmt(d["crystal_life"]))
        print("crystal err (hp):", fmt(d["crystal_err"]))
        print("refill ticks    :", fmt(d["refill"]), "| pops", c["pops"])
        print("wind/expl kb    :", fmt(d["kb_mag"]))
        print("safety/deaths   :", {k: v for k, v in c.items() if k.startswith("safety_") or k == "deaths"})
        print("survival rows   :", {k: v for k, v in c.items() if k.startswith("survival_")})
        hints = []
        if d["eta_err"] and statistics.median(d["eta_err"]) > 3:
            hints.append("ETA runs late: raise commit-window by ~%d ticks" % round(statistics.median(d["eta_err"])))
        if d["eta_err"] and statistics.median(d["eta_err"]) < -3:
            hints.append("ETA runs early: lower commit-window or add ping-lead-extra")
        if d["lead"] and pct(d["lead"], 5) is not None and pct(d["lead"], 5) < 3:
            hints.append("p5 lead < 3 ticks: too little time to act, commit earlier")
        if d["fp_score"] and d["commit_score"] and len(d["fp_score"]) > 0.4 * len(d["commit_score"]):
            hints.append("many false alarms: raise commit-score or min-fall")
        for h in hints:
            print("HINT:", h)
        print()
    if misses:
        print("MISSES (scenario, arch, top score before hit, damage):")
        for m in misses[:20]:
            print("  ", m)
        print("If top scores are 30-54 the signals fired but thresholds are too high; near 0 means no signal.")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1:])
