#!/usr/bin/env python3
"""Build phone-size offline replays AND a refreshable feed from REAL GameTests.

Only raw physical coordinates from 10 live BuilderGolem entities are encoded.
Never synthesize/interpolate or use a theoretical route as a successful replay.
The latest three test bundles remain playable offline, while an existing
downloaded HTML file can fetch newer test data when connected to GitHub.
"""
import argparse
import base64
import gzip
import json
from pathlib import Path
import re
import struct

FORMAT = "golems-plus-real-movement-v1"
COURSE = "timetrial-alt-vanilla-10-builders-260-ticks-v2"
NAME = re.compile(r"trial-(\d+)-(\d+)-generation-(\d+)\.json$")
MODES = "wspdjbcqyftr"

def select_traces(source, batches=3):
    if batches < 1:
        raise ValueError("At least one test bundle must be retained")
    candidates = []
    for path in source.glob("trial-*-generation-*.json"):
        match = NAME.fullmatch(path.name)
        if match:
            candidates.append((int(match[1]), int(match[2]), int(match[3]), path))
    if not candidates:
        raise ValueError(f"No physical GameTest traces in {source}")
    latest = set(sorted({p[0] for p in candidates})[-batches:])
    return sorted((p for p in candidates if p[0] in latest),
                  key=lambda p: p[:3])

def encode_trace(record):
    if (record.get("format") != FORMAT or record.get("course") != COURSE
            or record.get("physicalEntities") is not True
            or len(record.get("bots", [])) != 10):
        raise ValueError("Only physically recorded 10-entity GameTests allowed")
    compressed = bytearray()
    bot_summary = []
    for index, bot in enumerate(record["bots"]):
        points = bot.get("positions")
        if bot.get("slot") != index or not points or len(points) > 300:
            raise ValueError("Missing or invalid physical movement recording")
        compressed.extend(struct.pack("<H", len(points)))
        for tick, xmm, zmm, mode in points:
            if not isinstance(tick, int) or tick < 0 or tick > 260:
                raise ValueError("Invalid physical tick")
            if mode not in MODES or not isinstance(xmm, (int, float)) or not isinstance(zmm, (int, float)):
                raise ValueError("Invalid movement sample")
            compressed.extend(struct.pack("<hhB",
                    round(xmm / 10), round(zmm / 10), ord(mode)))
        bot_summary.append([
            bot["slot"], bot["startX"], bot["startZ"],
            bot["arrivedTicks"], bot["plannedRoute"],
            bot.get("collisions", 0), bot.get("doorTicks", 0),
            round(bot.get("distanceMeters", 0), 2), bot.get("slabTicks", 0),
            bot.get("reroutes", 0), bool(bot.get("initialRouteUsesDoor", False)),
            bot.get("yieldingTicks", 0),
            [len(bot.get("localEpisodes", [])),
             sum(e.get("cleared") is True for e in bot.get("localEpisodes", [])),
             sorted({e["action"] for e in bot.get("localEpisodes", [])})],
        ])
    arrivals = [bot["arrivedTicks"] for bot in record["bots"] if bot["arrivedTicks"] >= 0]
    meta = [
        record["completedGeneration"], record["trial"], record.get("generation", 0),
        len([t for t in arrivals if t <= 260]), len([t for t in arrivals if t <= 224]),
        max(arrivals) if arrivals else -1, bot_summary, record.get("experiment"),
    ]
    packed = base64.b64encode(gzip.compress(bytes(compressed), compresslevel=8, mtime=0)).decode("ascii")
    return meta, packed

def export(source, template, viewer, feed, batches=3):
    traces = select_traces(source, batches)
    meta, packed = [], []
    for run_id, test, generation, path in traces:
        record = json.loads(path.read_text(encoding="utf-8"))
        if record.get("trial") != f"{run_id}-{test}" or record.get("completedGeneration") != generation:
            raise ValueError(f"Replay metadata mismatch for {path}")
        m, p = encode_trace(record)
        meta.append(m)
        packed.append(p)
    if len({row[1] for row in meta}) < 3:
        raise ValueError("Expected at least three independently recorded tests")
    payload = {"format": FORMAT, "course": COURSE, "meta": meta, "payload": packed}
    meta_json = json.dumps(meta, ensure_ascii=True, separators=(",", ":"))
    payload_json = json.dumps(packed, ensure_ascii=True, separators=(",", ":"))
    html = template.read_text(encoding="utf-8")
    if html.count("__MOBILE_META__") != 1 or html.count("__MOBILE_PAYLOAD__") != 1:
        raise ValueError("Missing unique offline viewer placeholders")
    html = html.replace("__MOBILE_META__", meta_json).replace("__MOBILE_PAYLOAD__", payload_json)
    viewer.parent.mkdir(parents=True, exist_ok=True)
    viewer.write_text(html, encoding="utf-8")
    feed.write_text(json.dumps(payload, ensure_ascii=True, separators=(",", ":")), encoding="utf-8")
    print(f"Mobile alt viewer: {len(meta)} REAL physical generations across "
          f"{len({m[1] for m in meta})} tests in the latest "
          f"{len({m[1].split('-')[0] for m in meta})} bundles; "
          f"newest #{meta[-1][0]}; offline HTML {viewer.stat().st_size} bytes")
    return payload

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--live", type=Path, default=Path("training/alt/replays/live"))
    parser.add_argument("--template", type=Path, default=Path("training/alt/replays/mobile-template.html"))
    parser.add_argument("--viewer", type=Path, default=Path("training/alt/replays/mobile-viewer.html"))
    parser.add_argument("--feed", type=Path, default=Path("training/alt/replays/mobile-data.json"))
    parser.add_argument("--batches", type=int, default=3)
    args = parser.parse_args()
    export(args.live, args.template, args.viewer, args.feed, args.batches)
