#!/usr/bin/env python3
"""Embed all ACTUAL physical Minecraft generation traces for the last 3 CI trials.

Never synthesize or interpolate physical coordinates. The files originate solely
from BuilderGolem.move(MoverType.SELF) in Minecraft GameTest.
"""
import argparse
import json
from pathlib import Path
import re

BEGIN = "<!-- LIVE_GENERATIONS_BEGIN -->"
END = "<!-- LIVE_GENERATIONS_END -->"
COURSE = "timetrial-10-builders-360-ticks-v2-diagonal-step"
FORMAT = "golems-plus-real-movement-v1"


def export(directory: Path, viewer: Path, prefix: str):
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
    trials = []
    for name in sorted(grouped, key=lambda x: int(x.rsplit("-", 1)[-1])):
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
    viewer.write_text(updated, encoding="utf8")
    print(f"Embedded {len(trials)} REAL Minecraft trials, "
          f"{sum(len(t['generations']) for t in trials)} generations "
          f"(all 10 entity routes per generation).")
    if len(trials) != 3:
        raise ValueError(f"Expected three real test sessions, got {len(trials)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--session-prefix", required=True)
    parser.add_argument("--viewer", type=Path, required=True)
    parser.add_argument("--live", type=Path, default=Path("training/replays/live"))
    args = parser.parse_args()
    export(args.live, args.viewer, args.session_prefix)
