#!/usr/bin/env python3
"""Physical Dome NN curriculum with real-world checkpoint continuity.

Each trial launches a fresh Fabric GameTest JVM. We never synthesize labels,
never call the training model from Python, never alter legal-movement checks,
and never claim success from a test-harness exit code alone.
"""
from __future__ import annotations
import argparse
import gzip
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ROOT_OUT = ROOT / "build" / "nn-series"
STATE = ROOT_OUT / "state"
TRIALS = ROOT_OUT / "results"
RESULT_MARK = "[NN DOME RESULT] "
START_MARK = "[NN DOME START] "

def args():
    p = argparse.ArgumentParser()
    p.add_argument("--stage", required=True, choices=[
        "roof-ascent", "far-side", "roof-cap", "full-dome", "evaluation"])
    p.add_argument("--count", type=int, required=True)
    p.add_argument("--prefill", type=int, default=-1)
    p.add_argument("--wall-seconds", type=int, default=240)
    p.add_argument("--tick-limit", type=int, default=90000)
    p.add_argument("--git-sha", default="")
    a = p.parse_args()
    if not 1 <= a.count <= 10 or not -1 <= a.prefill <= 35:
        p.error("invalid count or prefill")
    if not 15 <= a.wall_seconds <= 900 or not 1000 <= a.tick_limit <= 180000:
        p.error("invalid physical run budget")
    if a.stage == "evaluation" and a.prefill != -1:
        p.error("evaluation must use the complete, empty original Dome")
    return a

def hash_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest() if path.exists() else "MISSING"

def state_summary() -> dict:
    results = {}
    for name in ("frontier-policy.json", "roof-maneuver-policy.json"):
        p = STATE / name
        if not p.exists():
            results[name] = {"sha256": "MISSING"}
            continue
        try:
            obj = json.loads(p.read_text())
            results[name] = {
                "sha256": hash_file(p), "schema": obj.get("schema"),
                "updates": obj.get("updates"), "successful": obj.get("completed"),
                "failed": obj.get("failed"), "placements": obj.get("placements")}
        except (OSError, ValueError) as exc:
            results[name] = {"sha256": hash_file(p), "error": str(exc)}
    return results

def exact_fields(s: str) -> dict[str, str]:
    return dict(re.findall(r"([A-Za-z][A-Za-z0-9_]*)=([^\s]+)", s))

def one_trial(stage: str, n: int, mode: str, seed: int,
              prefill: int, wall: int, ticks: int, sha: str) -> dict:
    folder = TRIALS / stage
    folder.mkdir(parents=True, exist_ok=True)
    key = f"{n:02d}-{mode}-s{seed}"
    log_path = folder / f"{key}.log.gz"
    env = os.environ.copy()
    nn = mode == "neural"
    frozen = stage == "evaluation"
    env.update({
        "NN_DOME_MODE": "AUTO8", "NN_COURSE": "dome", "NN_TEAM_SIZE": "8",
        "NN_DOME_TRIAL_SEED": str(seed), "NN_DOME_PREFILL_BELOW_REL_Y": str(prefill),
        "NN_DOME_RUN_TICKS": str(ticks), "NN_DOME_WALL_SECONDS": str(wall),
        "NN_TRAINING_STAGE": stage,
        "NN_TEAM_STALL_TICKS": "2400", "GOLEMS_NN_RECORD": "true",
        "GOLEMS_NN_TEAM_RECORD": "true", "GOLEMS_NN_SHADOW": "false",
        "GOLEMS_NN_CONTROL": "false", "GOLEMS_NN_CEILING_TAIL": "false",
        "GOLEMS_NN_FRONTIER_CONTROL": str(nn).lower(),
        "GOLEMS_NN_DETOUR_TRAINING": str(nn).lower(),
        "GOLEMS_NN_LONG_DETOUR": str(nn).lower(),
        "GOLEMS_NN_ROOF_MANEUVER_POLICY": str(nn).lower(),
        "GOLEMS_NN_FROZEN": str(frozen).lower(),
        "GOLEMS_NN_FRONTIER_MODEL_PATH": str(STATE / "frontier-policy.json"),
        "GOLEMS_NN_ROOF_POLICY_PATH": str(STATE / "roof-maneuver-policy.json"),
        "GOLEMS_DOME_FEASIBLE_LIMIT": "8",
        "GOLEMS_NN_REACHABLE_HANDOFF": "false",
        "GOLEMS_NN_PARTIAL_YIELD": "false", "GOLEMS_NN_TEAM_LOCAL": "false",
        "GOLEMS_NN_FRONTIER_STAGING": "true", "GOLEMS_NN_ROOF_FRONTIER": "true",
        "GOLEMS_NN_ROOF_RADIUS": "24", "GOLEMS_NN_MULTI_LEG_ROOF": "true",
        "GOLEMS_NN_SHAPE_EXIT": "true", "GOLEMS_NN_SCAFFOLD_CHAIN": "true",
        "GOLEMS_NN_STRUCTURES": "true", "GOLEMS_NN_STRUCTURE_LOOKAHEAD": "true",
        "GOLEMS_NN_PROJECT_YIELD": "true", "GOLEMS_NN_AUTO": "true",
        "GOLEMS_NN_STRIP_PILOT": "false", "GOLEMS_BUILDER_SCAFFOLDING": "false"
    })
    beginning = state_summary()
    start_line, end_line, roof_policy_active, roof_policy_loaded = None, None, False, False
    counts = dict(roof_choices=0, roof_changed=0, roof_above=0, roof_overshoot=0,
                  roof_outcomes=0, roof_landings=0, roof_failures=0, roof_placements=0,
                  frontier_choices=0, frontier_changed=0, training_feedback=0,
                  successful_detour_episodes=0, successful_over_target=0,
                  successful_beyond_xz=0, overshoot_plans=0)
    t0 = time.monotonic()
    print(f"[NN SERIES START] stage={stage} trial={n} mode={mode} seed={seed}"
          f" prefill={prefill} frozen={frozen}", flush=True)
    # Streaming to gzip avoids holding huge GameTest logs in RAM or
    # GitHub Actions console output. Store full unmodified log as evidence.
    with gzip.open(log_path, "wt", encoding="utf-8", errors="replace",
                   compresslevel=3) as dest:
        proc = subprocess.Popen(["gradle", "--no-daemon", "runGameTest"],
                                cwd=ROOT, env=env, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True,
                                errors="replace", bufsize=1)
        assert proc.stdout is not None
        for line in proc.stdout:
            dest.write(line)
            if START_MARK in line:
                start_line = line.split(START_MARK, 1)[1].strip()
            if RESULT_MARK in line and "mode=AUTO8 " in line:
                end_line = line.split(RESULT_MARK, 1)[1].strip()
                print("[NN SERIES PHYSICAL] " + end_line[:400], flush=True)
            if "[NN ROOF POLICY ENABLED] " in line:
                roof_policy_active = True
            if "[NN ROOF POLICY LOAD] " in line:
                roof_policy_loaded = True
            if "[NN ROOF CHOICE] " in line:
                counts["roof_choices"] += 1
                try:
                    z = json.loads(line.split("[NN ROOF CHOICE] ", 1)[1])
                    counts["roof_changed"] += bool(z.get("changed"))
                    counts["roof_above"] += bool(z.get("aboveTarget"))
                    counts["roof_overshoot"] += bool(z.get("beyondXZ"))
                except (ValueError, TypeError): pass
            if "[NN ROOF OUTCOME] " in line:
                counts["roof_outcomes"] += 1
                try:
                    z = json.loads(line.split("[NN ROOF OUTCOME] ", 1)[1])
                    counts["roof_landings"] += z.get("outcome") in (
                        "SAFE_WORK_POSE", "SUPPORTED_INTERMEDIATE_LANDING")
                    counts["roof_failures"] += str(z.get("outcome", "")).startswith(
                        "EXECUTION_FAILED_")
                except (ValueError, TypeError): pass
            if "[NN ROOF PLACEMENT] " in line: counts["roof_placements"] += 1
            if "[NN FRONTIER DECISION] " in line:
                counts["frontier_choices"] += 1
                try:
                    z = json.loads(line.split("[NN FRONTIER DECISION] ", 1)[1])
                    counts["frontier_changed"] += bool(z.get("changed"))
                except (ValueError, TypeError): pass
            if "[NN FRONTIER FEEDBACK] " in line: counts["training_feedback"] += 1
            if "[NN DETOUR OUTCOME] " in line and "success=true" in line:
                counts["successful_detour_episodes"] += 1
                counts["successful_over_target"] += "climbedAbove=true" in line
                counts["successful_beyond_xz"] += (
                    "pastX=true" in line or "pastZ=true" in line)
            if "[NN ROUTE OVERSHOOT PLAN] " in line: counts["overshoot_plans"] += 1
        returncode = proc.wait()
    after = state_summary()
    fields = exact_fields(end_line) if end_line else {}
    first = exact_fields(start_line) if start_line else {}
    correct = int(fields.get("correct", "-1"))
    prebuilt = int(fields.get("prebuilt", first.get("prebuilt", "-1")))
    result = {
        "schema": 1, "stage": stage, "run": n, "mode": mode, "seed": seed,
        "commit": sha, "prefill_below_y": prefill, "prebuilt": prebuilt,
        "correct": correct, "new_correct": max(0, correct-prebuilt),
        "total": int(fields.get("total", "-1")),
        "outcome": fields.get("outcome", "NO_TERMINAL_RESULT"),
        "elapsed_ticks": int(fields.get("ticks", "-1")),
        "distance": float(fields.get("distance", "nan")),
        "alive": int(fields.get("alive", "-1")),
        "exit_code": returncode,
        "wall_seconds": round(time.monotonic()-t0, 1),
        "trained": nn and not frozen,
        "frozen": frozen, "roof_policy_active": roof_policy_active,
        "roof_policy_loaded": roof_policy_loaded,
        "state_before": beginning, "state_after": after,
        **counts
    }
    result["valid"] = (
        returncode == 0 and bool(end_line) and result["total"] == 3493
        and result["alive"] == 8 and correct >= prebuilt >= 0
        and (not nn or roof_policy_active)
        and (not nn or roof_policy_loaded)
        and (not frozen or beginning == after)
    )
    (folder / f"{key}.json").write_text(json.dumps(result, indent=2)+"\n")
    print("[NN SERIES RESULT] "+json.dumps({
        k:result[k] for k in ("stage","run","mode","seed","correct",
                               "new_correct","prebuilt","outcome",
                               "roof_choices","roof_changed","roof_landings",
                               "roof_failures","roof_placements","valid")}),flush=True)
    return result

def main():
    a = args()
    STATE.mkdir(parents=True, exist_ok=True)
    TRIALS.mkdir(parents=True, exist_ok=True)
    # A missing checkpoint is a hard error, NEVER a silently reset model.
    for model in ("frontier-policy.json", "roof-maneuver-policy.json"):
        f = STATE / model
        if not f.is_file() or f.stat().st_size < 200:
            raise SystemExit(f"Required continuously trained checkpoint missing: {f}")
    schedule = {
        "roof-ascent": 10000, "far-side": 11000, "roof-cap": 12000,
        "full-dome": 13000, "evaluation": 14000}
    results = []
    for n in range(1, a.count+1):
        if a.stage == "evaluation":
            for mode in ("baseline", "neural"):
                result = one_trial(a.stage,n,mode,schedule[a.stage]+n,
                                   -1,a.wall_seconds,a.tick_limit,a.git_sha)
                results.append(result)
                if not result["valid"]:break
        else:
            # Different prefill heights near each curriculum band supply
            # authentic, physically evolving roof shapes, not new inventions.
            prefill=a.prefill + (n % 3)-1 if a.prefill>=0 else -1
            result=one_trial(a.stage,n,"neural",schedule[a.stage]+n,
                             prefill,a.wall_seconds,a.tick_limit,a.git_sha)
            results.append(result)
        # Preserve every valid trial immediately before trying the next one.
        manifest={"stage":a.stage,"completed":len(results),
                  "requested":a.count*(2 if a.stage=="evaluation" else 1),
                  "results":[{
                      k:v for k,v in r.items() if k not in
                      ("state_before","state_after")} for r in results]}
        (TRIALS / f"{a.stage}-manifest.json").write_text(
            json.dumps(manifest,indent=2)+"\n")
        if not results[-1]["valid"]:
            print("[NN SERIES STOP] Invalid physical run, do not propagate",
                  results[-1]["stage"],results[-1]["run"],file=sys.stderr,flush=True)
            return 1
    summary = {
        "stage":a.stage,"total_trials":len(results),
        "fully_complete":sum(r["outcome"]=="COMPLETE" and r["correct"]==3493
                             for r in results),
        "avg_correct":sum(r["correct"] for r in results)/len(results),
        "roof_choices":sum(r["roof_choices"] for r in results),
        "roof_changed":sum(r["roof_changed"] for r in results),
        "roof_landings":sum(r["roof_landings"] for r in results),
        "roof_failures":sum(r["roof_failures"] for r in results),
        "world_placements":sum(r["roof_placements"] for r in results),
        "state_after":state_summary()}
    (TRIALS / f"{a.stage}-summary.json").write_text(json.dumps(summary,indent=2)+"\n")
    if os.getenv("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"],"a") as out:
            out.write(f"## NN physical training: {a.stage}\n")
            out.write(f"{len(results)} valid trials, {summary['fully_complete']} Dome finishes. "
                      f"Mean world-correct blocks: {summary['avg_correct']:.1f}/3493. "
                      f"Roof maneuvers: {summary['roof_choices']}, "
                      f"safe landings: {summary['roof_landings']}, "
                      f"failures: {summary['roof_failures']}.\n")
    print("[NN SERIES STAGE COMPLETE] "+json.dumps(summary),flush=True)
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
