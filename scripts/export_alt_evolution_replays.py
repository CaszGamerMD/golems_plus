#!/usr/bin/env python3
"""Embed recent ACTUAL Minecraft traces without exceeding GitHub's file limit.

Never synthesize or interpolate physical coordinates. The files originate solely
from BuilderGolem.move(MoverType.SELF) in Minecraft GameTest.
"""
import argparse
import json
from pathlib import Path
import re

BEGIN = "<!-- LIVE_GENERATIONS_BEGIN -->"
END = "<!-- LIVE_GENERATIONS_END -->"
COURSE = "timetrial-alt-vanilla-10-builders-260-ticks-v2"
FORMAT = "golems-plus-real-movement-v1"


def export(directory: Path, viewer: Path, prefix: str, batches=3):
    if batches < 0:
        raise ValueError("Replay bundle limit cannot be negative")
    grouped = {}
    for path in sorted(directory.glob("trial-*-generation-*.json")):
        data = json.loads(path.read_text(encoding="utf8"))
        if data.get("format") != FORMAT or data.get("course") != COURSE:
            raise ValueError(f"Unexpected or non-physical replay {path}")
        if data.get("physicalEntities") is not True or len(data.get("bots", [])) != 10:
            raise ValueError(f"Missing real BuilderGolem population in {path}")
        trial = data["trial"]
        if not trial.startswith(prefix):
            continue
        if any(not bot.get("positions") for bot in data["bots"]):
            raise ValueError(f"Unrecorded entity positions in {path}")
        grouped.setdefault(trial, []).append(data)
    # Keep the raw per-generation archive intact; only bound the inline
    # HTML. An ever-growing 109 MB viewer blocked checkpoint AND feed
    # publication when GitHub rejected the entire push at 100 MiB.
    if batches:
        bundles = sorted({t.rsplit('-', 1)[0] for t in grouped},
                         key=lambda t: (0, int(t)) if t.isdigit() else (1, t))[-batches:]
        grouped = {t: rows for t, rows in grouped.items()
                   if t.rsplit('-', 1)[0] in bundles}
    trials = []
    for name in sorted(grouped, key=lambda x: (
            x.rsplit("-", 1)[0], int(x.rsplit("-", 1)[-1])
            if x.rsplit("-", 1)[-1].isdigit() else 0)):
        generations = sorted(grouped[name], key=lambda g: g["completedGeneration"])
        trials.append({"trial": name, "generations": generations})
    payload = json.dumps({"format": FORMAT, "tests": trials},
                         separators=(",", ":"), ensure_ascii=True)
    if "</script" in payload.lower():
        raise ValueError("Unexpected HTML in physical replay data")
    original = viewer.read_text(encoding="utf8")
    if original.count(BEGIN) != 1 or original.count(END) != 1:
        raise ValueError("Missing unique HTML live replay markers")
    updated = re.sub(re.escape(BEGIN) + r".*?" + re.escape(END),
                     lambda _: BEGIN + "\n<script id=\"current-replays\" type=\"application/json\">"
                               + payload + "</script>\n" + END,
                     original, flags=re.S)
    if len(updated.encode('utf8')) >= 90 * 1024 * 1024:
        raise ValueError("Inline viewer exceeds 90 MiB; reduce --batches")
    viewer.write_text(updated, encoding="utf8")
    print(f"Embedded {len(trials)} REAL Minecraft trials, "
          f"{sum(len(t['generations']) for t in trials)} generations "
          f"(all 10 entity routes per generation).")
    if len(trials) < 3:
        raise ValueError(f"Expected at least three real test sessions, got {len(trials)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--session-prefix", required=True)
    parser.add_argument("--viewer", type=Path, required=True)
    parser.add_argument("--live", type=Path, default=Path("training/alt/replays/live"))
    parser.add_argument("--batches", type=int, default=3,
                        help="Newest run bundles in HTML; raw trace files stay intact")
    args = parser.parse_args()
    export(args.live, args.viewer, args.session_prefix, args.batches)
