#!/usr/bin/env python3
"""Compare three independent real Matrix builds, never confuse a cutoff with completion."""
import json
import os
import re
import sys
from pathlib import Path

FIELDS = re.compile(r"\[MATRIX TRIAL RESULT\] outcome=(\w+) layer=(\S+) elapsedMs=(\d+) tick=(\d+) correct=(\d+) total=(\d+) best=(\d+)(?: ignoredPistonDisplacements=(\d+))? unsupported=(\d+)")


# Compare only actual observations at equal simulation ticks.
PROGRESS = re.compile(
    r"\[MATRIX FULL PROGRESS\] tick=(\d+) matchingSourceBlocks=(\d+)/(\d+)"
    r"(?: ignoredPistonDisplacements=(\d+))? best=(\d+) cursor=(\d+)/(\d+) currentLayer=(\S+) noWorkTicks=(\d+)"
    r" crewAlive=(\d+)"
)

def progress(log):
    rows = {}
    for m in PROGRESS.finditer(log):
        tick, correct, total, displaced, best, cursor, steps, layer, idle, alive = m.groups()
        rows[int(tick)] = dict(tick=int(tick), correct=int(correct), total=int(total),
                              best=int(best), ignoredPistonDisplacements=int(displaced or 0), cursor=int(cursor), steps=int(steps),
                              layer=layer, noWorkTicks=int(idle), crewAlive=int(alive))
    return rows

def compare_progress(timelines):
    """Locate observed divergence, without implying its cause."""
    available = len(timelines) == 3 and all(timelines.values())
    common = sorted(set.intersection(*(set(t) for t in timelines.values()))) if available else []
    first = None
    first_by_trial = {}
    consecutive = {}
    previous = {}
    for tick in common:
        rows = {trial: timeline[tick] for trial, timeline in timelines.items()}
        values = [r["correct"] for r in rows.values()]
        lead = max(values)
        if first is None and (len(set(values)) > 1 or len({r["layer"] for r in rows.values()}) > 1):
            first = dict(tick=tick, correctSpread=max(values)-min(values), trials=rows)
        for trial, row in rows.items():
            gap = lead-row["correct"]
            # Require two successive shared observations, not a single tick
            # of scheduling noise. Never extrapolate beyond a run.
            if gap >= 100:
                if consecutive.get(trial, 0) == 0:
                    previous[trial] = dict(tick=tick, gap=gap, trials=rows)
                consecutive[trial] = consecutive.get(trial, 0)+1
                if consecutive[trial] == 2 and trial not in first_by_trial:
                    first_by_trial[trial] = dict(first=previous[trial], confirmedTick=tick, gap=gap)
            else:
                consecutive[trial] = 0
    return dict(commonCheckpoints=len(common), firstObservedDifference=first,
                sustainedLagByTrial=first_by_trial,
                checkpointsByTrial={trial:len(t) for trial,t in timelines.items()},
                note="100 fewer correct source blocks at two successive shared game-tick observations is a diagnostic flag, not a root cause; no interpolation.")

def progress_from_artifacts(root):
    timelines = {}
    for trial in (1,2,3):
        log = root/f"matrix-trial-{trial}"/"matrix-trial.log"
        timelines[str(trial)] = progress(log.read_text(errors="replace")) if log.exists() else {}
    return compare_progress(timelines)


def extract(log):
    matches = FIELDS.findall(log)
    if not matches:
        return {"outcome": "MISSING_RESULT"}
    outcome, layer, elapsed, tick, correct, total, best, displaced, unsupported = matches[-1]
    env = re.findall(r"\[MATRIX CONTROLLED ENV\] ([^\r\n]+)", log)
    targets = re.findall(r"\[MATRIX FULL TARGET\] sourceBlocks=(\d+) ignoredPistonHeads=(\d+) requiredBlocks=(\d+)", log)
    source, ignored, required = map(int, targets[-1]) if targets else (15376, 0, 15376)
    return dict(outcome=outcome, layer=layer, elapsedMs=int(elapsed), tick=int(tick),
                correct=int(correct), total=int(total), best=int(best), unsupported=int(unsupported),
                ignoredPistonDisplacements=int(displaced or 0),
                environment=env[-1] if env else None,
                sourceBlocks=source, ignoredPistonHeads=ignored, requiredBlocks=required)

def compare(rows):
    valid = len(rows) == 3 and all("elapsedMs" in r for r in rows)
    spread = max(r["elapsedMs"] for r in rows)-min(r["elapsedMs"] for r in rows) if valid else None
    same_layer = valid and len({r["layer"] for r in rows}) == 1
    natural_stop = valid and all(r["outcome"] in ("COMPLETE","ALL_BUILDERS_STALLED","NO_WORK_PROGRESS") for r in rows)
    controlled = os.environ.get("MATRIX_TEST_MODE") == "controlled"
    same_environment = bool(valid and all(r.get("environment") for r in rows)
                            and len({r["environment"] for r in rows}) == 1)
    environment_ok = not controlled or same_environment
    tick_spread = max(r["tick"] for r in rows)-min(r["tick"] for r in rows) if valid else None
    consistent = bool(environment_ok and same_layer and natural_stop and spread <= 300000)
    def full_target(r):
        source = r.get("sourceBlocks", 15376)
        ignored = r.get("ignoredPistonHeads", 0)
        required = r.get("requiredBlocks", 15376)
        return source == 15376 and 0 <= ignored < source and required == source-ignored and r["correct"] == r["total"] == required
    complete = valid and len({r.get("requiredBlocks",15376) for r in rows}) == 1 and all(r["outcome"]=="COMPLETE" and full_target(r) for r in rows)
    # Repeating a very early failure is diagnostic, not a passing build.
    # The current development milestone is at least Y13 and 9,700 correct
    # cells in every trial, with the original same-layer / five-minute rule.
    def reaches_milestone(r):
        try:
            return int(r["layer"]) >= 13 and r["correct"] >= 9700
        except (KeyError, ValueError, TypeError):
            return False
    milestone = bool(consistent and (complete or all(reaches_milestone(r) for r in rows)))
    return dict(trials=rows, sameLayer=bool(same_layer), elapsedSpreadMs=spread,
                repeatableStop=consistent, allComplete=bool(complete),
                milestoneReached=milestone, controlled=controlled,
                sameEnvironment=same_environment, tickSpread=tick_spread,
                result="INVALID_ENVIRONMENT" if not environment_ok else
                "FULL_SUCCESS" if consistent and complete else
                "MILESTONE_REACHED" if milestone else
                "REPEATABLE_STALL" if consistent else "NOT_YET_RELIABLE")

if __name__ == "__main__":
    if sys.argv[1]=="extract":
        result=extract(Path(sys.argv[2]).read_text(errors="replace"))
        result["trial"]=int(sys.argv[3])
        Path(sys.argv[4]).write_text(json.dumps(result,indent=2)+"\n")
    elif sys.argv[1]=="compare":
        root=Path(sys.argv[2])
        rows=[]
        for trial in (1,2,3):
            path=root/f"matrix-trial-{trial}"/"result.json"
            rows.append(json.loads(path.read_text()) if path.exists() else
                        {"trial":trial,"outcome":"MISSING_RESULT"})
        result=compare(rows)
        result["progressComparison"]=progress_from_artifacts(root)
        print(json.dumps(result,indent=2))
        Path("matrix-reliability.json").write_text(json.dumps(result,indent=2)+"\n")
        with open(sys.argv[3],"a") as f:
            f.write("## Three-run Matrix reliability\n\n")
            f.write("| Trial | Outcome | Layer | Correct blocks | Elapsed | Game ticks |\n|---|---|---|---:|---:|---:|\n")
            for r in rows:
                sec=r.get("elapsedMs",0)//1000
                elapsed=f"{sec//60}:{sec%60:02}" if "elapsedMs" in r else "—"
                f.write(f"| {r['trial']} | {r['outcome']} | {r.get('layer','—')} | {r.get('correct','—')} | {elapsed} | {r.get('tick','—')} |\n")
            f.write(f"\n**Result: {result['result']}**\n\n")
            f.write(f"Same stopping layer: {result['sameLayer']}. Runtime spread: {result['elapsedSpreadMs']} ms. All complete: {result['allComplete']}.\n")
            p=result["progressComparison"]
            f.write("\n### Progress divergence (equal game ticks)\n\n")
            f.write(f"Shared checkpoints: {p['commonCheckpoints']}.\n\n")
            first=p["firstObservedDifference"]
            if first:
                f.write(f"First observed difference: tick {first['tick']}, "
                        f"correct-block spread {first['correctSpread']}.\n\n")
            else:
                f.write("No differing shared checkpoint observed (or insufficient logs).\n\n")
            for trial, lag in p["sustainedLagByTrial"].items():
                f.write(f"- Trial {trial}: sustained lag starts at tick {lag['first']['tick']}, "
                        f"confirmed at {lag['confirmedTick']} ({lag['gap']} fewer correct blocks).\n")
            f.write("\n"+p["note"]+"\n")
        sys.exit(0 if result["milestoneReached"] else 1)
