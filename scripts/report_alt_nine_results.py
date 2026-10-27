#!/usr/bin/env python3
"""Summarize *measured* BuilderGolem GameTests; never infer success from plans.

Reports are small text/JSON artifacts, not replays. Training checkpoints,
raw movement evidence, the protected champion, and history remain independent.
"""
import argparse
import json
import os
from pathlib import Path
import re

GEN = re.compile(
    r"\[TIMETRIAL REAL GENETICS\] gen=(?P<generation>\d+)"
    r" realBuilderGolems=(?P<golems>\d+)"
    r" finish13s=(?P<finish13>\d+)/(?P<den13>\d+)"
    r" finish11_2s=(?P<finish112>\d+)/(?P<den112>\d+)"
)
FIELDS = ("doorPlans", "doorCrossings", "collisions", "jumps",
          "slabAscents", "slabEntryStalls", "doorEntryAlignTicks", "doorEntryStalls", "fastestTicks", "slowestTicks")
VALUE = re.compile(r"\b([A-Za-z][A-Za-z0-9]*)=(-?\d+)\b")


def load_json(path, default=None):
    if not path or not Path(path).is_file():
        return default
    return json.loads(Path(path).read_text(encoding="utf-8"))


def read_trials(directory):
    results = []
    for path in sorted(Path(directory).glob("real-builder-trial-*.log")):
        rows = []
        traffic_replans = 0
        for line in path.open(encoding="utf-8", errors="replace"):
            if "[ALT TRAFFIC REPLAN]" in line:
                traffic_replans += 1
            match = GEN.search(line)
            if not match:
                continue
            row = {name: int(value) for name, value in match.groupdict().items()}
            row.update({key: int(value) for key, value in VALUE.findall(line)
                        if key in FIELDS})
            if row["golems"] != 9 or row["den13"] != 9 or row["den112"] != 9:
                raise ValueError("Invalid physical-crew count: " + path.name)
            if not (0 <= row["finish112"] <= row["finish13"] <= 9):
                raise ValueError("Inconsistent physical finishing counts")
            rows.append(row)
        results.append({"name": path.name, "generations": rows,
                        "trafficReplans": traffic_replans})
    return results


def top_pool(state):
    pool = state.get("knowledgePool", []) if isinstance(state, dict) else []
    return [{"slot": p["slot"], "fitness": p["fitness"],
             "generation": p.get("generation")}
            for p in pool if isinstance(p, dict) and "slot" in p and "fitness" in p]


def report(trials, before, after, champion, run_id="", run_url="", mode="evolve"):
    before = before or {}
    after = after or {}
    generations = [g for t in trials for g in t["generations"]]
    maxima = {
        "finish13": max((g["finish13"] for g in generations), default=None),
        "finish112": max((g["finish112"] for g in generations), default=None),
    }
    total = len(generations)
    first, last = (generations[0], generations[-1]) if total else (None, None)
    bcount, acount = before.get("completedGenerations"), after.get("completedGenerations")
    growth = acount - bcount if isinstance(bcount, int) and isinstance(acount, int) else None
    bobs = before.get("strategyObservations")
    aobs = len(after.get("strategyMemory", {}).get("observations", [])) if after else None
    observed = aobs - bobs if isinstance(aobs, int) and isinstance(bobs, int) else None
    old_scores = before.get("personalScores", [])
    new_scores = after.get("personalScores", [])
    improved = [i for i, (a, b) in enumerate(zip(old_scores, new_scores))
                if isinstance(a, (float, int)) and isinstance(b, (float, int))
                and b > a] if len(old_scores) == len(new_scores) == 9 else []
    previous_pool = top_pool(before)
    current_pool = top_pool(after)
    pool_changed = ([(p["slot"], p["fitness"]) for p in previous_pool]
                    != [(p["slot"], p["fitness"]) for p in current_pool]
                    if previous_pool and current_pool else None)
    champion_tick = champion.get("slowestTicks") if isinstance(champion, dict) else None
    summed = {field: sum(g.get(field, 0) for g in generations) for field
              in ("doorPlans", "doorCrossings", "collisions", "jumps",
                  "slabAscents", "slabEntryStalls", "doorEntryAlignTicks", "doorEntryStalls")}
    summed["trafficReplans"] = sum(t["trafficReplans"] for t in trials)
    has_slab_telemetry = bool(generations) and all(
        "slabAscents" in g and "slabEntryStalls" in g for g in generations)
    has_door_telemetry = bool(generations) and all(
        "doorEntryAlignTicks" in g and "doorEntryStalls" in g for g in generations)
    summary = {
        "runId": str(run_id), "mode": mode,
        "trials": len(trials), "physicalGenerations": total,
        "max13s": maxima["finish13"], "max11_2s": maxima["finish112"],
        "latest13s": last["finish13"] if last else None,
        "latest11_2s": last["finish112"] if last else None,
        "first13s": first["finish13"] if first else None,
        "completedGenerationDelta": growth, "strategyEvidenceDelta": observed,
        "improvedPersonalSlots": improved, "poolChanged": pool_changed,
        "pool": current_pool, "historicChampionSlowestTicks": champion_tick,
        "totals": summed, "hasSlabTelemetry": has_slab_telemetry,
        "hasDoorTelemetry": has_door_telemetry,
        "perTrial": [{
            "name": t["name"], "generations": len(t["generations"]),
            "best13s": max((g["finish13"] for g in t["generations"]), default=None),
            "best11_2s": max((g["finish112"] for g in t["generations"]), default=None),
            "final13s": t["generations"][-1]["finish13"] if t["generations"] else None,
            "final11_2s": t["generations"][-1]["finish112"] if t["generations"] else None,
        } for t in trials]
    }
    notes = []
    if not generations:
        notes.append("**No scored generations found.** Inspect Minecraft startup, compilation, and GameTest errors; do not claim training occurred.")
    else:
        if not has_slab_telemetry:
            notes.append("**Slab telemetry incomplete:** physical generation logs do not consistently contain ascent/stall counters. Confirm instrumentation is active before interpreting slab progress.")
        elif summed["slabEntryStalls"] > summed["slabAscents"] and summed["slabEntryStalls"] > 0:
            notes.append("**Slab entry remains a bottleneck:** more physical slab-entry stalls than successful ascents. Investigate edge alignment, step-up grounding, and nearby worker clearance; verify with controller-level slab tests.")
        elif has_slab_telemetry and summed["slabAscents"] > 0:
            notes.append("**Slabs are physically accessible:** observed real ascents. Compare entry stalls and completion rates before changing slab route costs.")
        elif has_slab_telemetry:
            notes.append("**Slab skill not demonstrated in the scored crew:** no recorded native ascents. Check whether plans enter slabs, then check approach geometry and instrumentation.")
        if has_door_telemetry and summed["doorEntryStalls"] > 0:
            notes.append("**Door-entry hesitation observed:** check collision-safe alignment and congestion at door lips; require the real-controller no-jump regression to pass.")
        if summed["doorPlans"] > 0 and summed["doorCrossings"] == 0:
            notes.append("**Door exploration does not translate to traversal:** planned door routes were recorded, but no golem used a door. Investigate door approach/leaf clearance and traffic; do not increase door preference blindly.")
        elif summed["doorCrossings"] > 0:
            notes.append("**Door routes were attempted:** compare completion times of door users versus other routes before assigning a larger door preference.")
        if total >= 6 and last["finish13"] < maxima["finish13"]:
            notes.append("**Final generation regressed from the session best.** Keep the protected champion and individual top-three pool, then test gene changes against repeated identical conditions.")
        if summed["trafficReplans"] > total * 4:
            notes.append("**Frequent traffic replanning:** evaluate wait-to-reroute thresholds and predictable right-of-way; excessive reroutes can cause the crew to repeatedly abandon short slab entrances.")
        if summed["collisions"] > total * 15:
            notes.append("**High collision burden:** inspect start-area crowding, right-of-way arbitration, and whether teammate contacts are counted as static wall failures.")
        if maxima["finish112"] is not None and maxima["finish112"] < 9:
            notes.append("**Speed target not met:** optimize reliable 9/9 completion under 260 ticks before accepting faster individual records as team-wide improvement.")
    if mode == "evolve":
        if growth is None or growth <= 0:
            notes.insert(0, "**Training retention warning:** completed generation count did not advance (or the checkpoint is missing). Inspect state save and merge.")
        if observed is None or observed <= 0:
            notes.insert(0, "**Experience retention warning:** no new persisted strategy observations were verified. Inspect observation capture and checkpoint merge.")
        if not improved and pool_changed is False:
            notes.append("**Knowledge pool unchanged:** new physical generations did not beat the retained personal-best leaders. Prefer controlled motor/strategy experiments instead of counting generation increments as improvement.")
    notes = notes[:7]
    lines = ["# Builder Golem physical training — latest results", ""]
    if run_url:
        lines.append(f"**GitHub Actions:** {run_url}")
    if run_id:
        lines.append(f"**Run ID:** {run_id}")
    lines += [f"**Mode:** {mode} | **Course:** existing alt course | **Real entities:** 9 per generation",
              "", "## Results", ""]
    if total:
        lines += [
            f"- **Best 13s (260 ticks):** {maxima['finish13']}/9; **best 11.2s (224 ticks):** {maxima['finish112']}/9.",
            f"- **First → last 13s:** {first['finish13']}/9 → {last['finish13']}/9. Latest within 11.2s: {last['finish112']}/9.",
            f"- **Physical generations scored in logs:** {total} across {len(trials)} trial logs.",
        ]
    else:
        lines.append("- **No verified full-crew generations in the supplied logs.**")
    lines += [
        f"- **Checkpoint generation change:** {bcount if bcount is not None else 'unavailable'} → {acount if acount is not None else 'unavailable'} ({growth if growth is not None else 'unknown'}).",
        f"- **Recorded strategy observations change:** {bobs if bobs is not None else 'unavailable'} → {aobs if aobs is not None else 'unavailable'} ({observed if observed is not None else 'unknown'}).",
        f"- **Personal-best improvements this run:** {', '.join('golem '+str(i) for i in improved) if improved else 'none verified'}.",
        f"- **Shared top three:** {', '.join('golem '+str(k['slot'])+' ('+str(round(k['fitness'], 1))+')' for k in current_pool) if current_pool else 'unavailable'}; pool changed: {'yes' if pool_changed is True else 'no' if pool_changed is False else 'unknown'}.",
        f"- **Protected historical champion:** {champion_tick} ticks (9/9) — historical, NOT evidence of this run." if champion_tick is not None else "- **Historical champion:** unavailable.",
        "",
        "## Physical movement signals", "",
        (f"- Slab ascents: **{summed['slabAscents']}**; slab-entry stalls: **{summed['slabEntryStalls']}**."
         if has_slab_telemetry else "- Slab ascents/stalls: telemetry incomplete."),
        f"- Door plans: **{summed['doorPlans']}**; door crossing attempts: **{summed['doorCrossings']}**.",
        (f"- Door alignment ticks: **{summed['doorEntryAlignTicks']}**; door-entry stalls: **{summed['doorEntryStalls']}**."
         if has_door_telemetry else "- Door-entry alignment/stalls: telemetry unavailable in this run."),
        f"- Collision events: **{summed['collisions']}**; jump events: **{summed['jumps']}**; traffic replans: **{summed['trafficReplans']}**.",
        "", "## Trial breakdown", "",
        "| Trial | Generations | Best ≤260t | Best ≤224t | Final ≤260t |",
        "|---|---:|---:|---:|---:|",
    ]
    for t in summary["perTrial"]:
        def score(x):
            return str(x)+"/9" if x is not None else "—"
        lines.append(f"| {t['name']} | {t['generations']} | {score(t['best13s'])} | {score(t['best11_2s'])} | {score(t['final13s'])} |")
    lines += ["", "## Notes for improving the process", ""]
    for i, note in enumerate(notes, 1):
        lines.append(f"{i}. {note}")
    lines += ["", "_Based on scored real-entity logs and checkpoint evidence only. "
              "No stored route or headless simulation counts as a physical pass. "
              "Historical results are retained separately; the HTML viewer remains paused._", ""]
    summary["recommendations"] = notes
    return "\n".join(lines), summary


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--logs", type=Path, default=Path("build/timetrial-training"))
    parser.add_argument("--before", type=Path, default=Path("build/timetrial-training/original-state.json"))
    parser.add_argument("--state", type=Path, default=Path("training/alt/state-nine/checkpoint.json"))
    parser.add_argument("--champion", type=Path, default=Path("training/alt/state-nine/champion.json"))
    parser.add_argument("--out", type=Path, default=Path("training/alt/reports/latest.md"))
    parser.add_argument("--json-out", type=Path, default=Path("training/alt/reports/latest.json"))
    parser.add_argument("--mode", default=os.getenv("GOLEMS_EVOLUTION_MODE", "evolve"))
    parser.add_argument("--run-id", default=os.getenv("GITHUB_RUN_ID", ""))
    parser.add_argument("--run-url", default="")
    args = parser.parse_args()
    url = args.run_url or (
        "https://github.com/" + os.environ.get("GITHUB_REPOSITORY", "CaszGamerMD/golems_plus")
        + "/actions/runs/" + args.run_id if args.run_id else "")
    md, result = report(read_trials(args.logs),
                        load_json(args.before, {}), load_json(args.state, {}),
                        load_json(args.champion, {}), args.run_id, url, args.mode)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.json_out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(md, encoding="utf-8")
    args.json_out.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(md)
    if os.getenv("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as handle:
            handle.write(md + "\n")


if __name__ == "__main__":
    main()
