#!/usr/bin/env python3
"""Evidence-only, GitHub-hosted tuning of safe construction planner heuristics.

Runs the real Fabric Minecraft GameTest each trial; never fabricates paths,
placements, golem actions or simulation scores. The state branch is isolated
from source branches and is NEVER merged into production automatically.
"""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time

STATE_BRANCH = "training/dome-autotune-state-262"
STATE_FILE = "dome-training/state.json"
TOTAL = 3537
DEFAULT = {
    "GOLEMS_DOME_SCAN_LIMIT": "32",
    "GOLEMS_DOME_FEASIBLE_LIMIT": "5",
    "GOLEMS_DOME_SKY_PENALTY": "12",
    "GOLEMS_DOME_GRAPPLE_PENALTY": "24",
    "GOLEMS_DOME_TARGET_WEIGHT": "0.65",
}
CHOICES = {
    "GOLEMS_DOME_SCAN_LIMIT": ("48", "64", "96", "16"),
    "GOLEMS_DOME_FEASIBLE_LIMIT": ("8", "12", "3"),
    "GOLEMS_DOME_SKY_PENALTY": ("0", "6", "18", "24"),
    "GOLEMS_DOME_GRAPPLE_PENALTY": ("6", "12", "36", "48"),
    "GOLEMS_DOME_TARGET_WEIGHT": ("0.25", "1.1", "1.75", "0"),
}
PROGRESS = re.compile(
    r"\[DOME PROGRESS\] tick=(\d+) realBlocks=(\d+)/3537 activeY=(-?\d+)"
)
STALL = re.compile(r"Dome stalled after (\d+) ticks")
PASS = "[DOME E2E] PASS all 3537 blocks verified"
MARKER = "[DOME TUNING]"


def git(*args, check=True, cwd=None):
    return subprocess.run(
        ["git", *args], cwd=cwd, text=True, capture_output=True, check=check
    )


def load_state(source_sha):
    state = {"source_sha": source_sha, "best": None, "trials": []}
    remote = git("ls-remote", "--exit-code", "--heads", "origin", STATE_BRANCH, check=False)
    if remote.returncode != 0:
        return state
    git("fetch", "--no-tags", "origin", "refs/heads/" + STATE_BRANCH)
    result = git("show", "FETCH_HEAD:" + STATE_FILE, check=False)
    if result.returncode == 0:
        try:
            saved = json.loads(result.stdout)
            if saved.get("source_sha") == source_sha:
                state = saved
            else:
                print("Source changed: resetting comparative scores", flush=True)
        except (ValueError, TypeError):
            print("Invalid saved training state: rebuilding", flush=True)
    return state


def save_state(state):
    with tempfile.TemporaryDirectory(prefix="dome-state-") as tmp:
        worktree = str(Path(tmp) / "checkout")
        existing = git(
            "ls-remote", "--exit-code", "--heads", "origin", STATE_BRANCH,
            check=False
        ).returncode == 0
        if existing:
            git("fetch", "--no-tags", "origin", "refs/heads/" + STATE_BRANCH)
        git("worktree", "add", "--detach", worktree, "FETCH_HEAD" if existing else "HEAD")
        try:
            root = Path(worktree)
            output = root / STATE_FILE
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(json.dumps(state, indent=2, sort_keys=True) + "\n")
            git("config", "user.name", "github-actions[bot]", cwd=worktree)
            git("config", "user.email",
                "41898282+github-actions[bot]@users.noreply.github.com", cwd=worktree)
            git("add", STATE_FILE, cwd=worktree)
            committed = git("commit", "-m", "Record physical dome tuning trials",
                            cwd=worktree, check=False)
            if committed.returncode != 0 and "nothing to commit" not in committed.stdout:
                raise RuntimeError("State commit failed: " + committed.stderr)
            git("push", "origin", "HEAD:refs/heads/" + STATE_BRANCH, cwd=worktree)
        finally:
            git("worktree", "remove", "--force", worktree, check=False)


def parse_result(log, settings):
    samples = [(int(t), int(n), int(y)) for t, n, y in PROGRESS.findall(log)]
    best_sample = max(samples, key=lambda row: (row[1], row[2], -row[0]),
                      default=(0, 0, -1))
    success = PASS in log and "required tests failed" not in log
    if success:
        best_sample = (best_sample[0], TOTAL, max(best_sample[2], 0))
    stall = STALL.search(log)
    if "trainedReason=search_no_route" in log:
        diagnosis = "trained_path_search"
    elif "Deferred inaccessible grapple" in log:
        diagnosis = "grapple_access"
    elif "Waiting for safe standing space" in log:
        diagnosis = "safe_work_pose"
    elif "BUILD FAILED" in log and not samples:
        diagnosis = "harness_or_compile_failure"
    else:
        diagnosis = "other_or_incomplete"
    signature = (
        "scan=" + settings["GOLEMS_DOME_SCAN_LIMIT"]
        + " feasible=" + settings["GOLEMS_DOME_FEASIBLE_LIMIT"]
        + " sky=" + str(float(settings["GOLEMS_DOME_SKY_PENALTY"]))
        + " grapple=" + str(float(settings["GOLEMS_DOME_GRAPPLE_PENALTY"]))
        + " target=" + str(float(settings["GOLEMS_DOME_TARGET_WEIGHT"]))
    )
    # A job lacking the explicit settings marker is unverified even if it
    # printed counts; don't credit tuning that might not have been applied.
    verified = bool(samples) and (MARKER + " " + signature) in log
    return {
        "verified": verified,
        "complete": success and verified,
        "real_blocks": best_sample[1] if verified else 0,
        "active_y": best_sample[2] if verified else -1,
        "peak_tick": best_sample[0] if verified else 0,
        "stall_tick": int(stall.group(1)) if stall else None,
        "diagnosis": diagnosis,
    }


def quality(trial):
    return (int(trial["complete"]), trial["real_blocks"],
            trial["active_y"], -trial["peak_tick"])


def choose_candidate(best, previous, trials):
    seen = {tuple(sorted(t["settings"].items())) for t in trials
            if t.get("verified")}
    order = list(CHOICES)
    if previous and previous["diagnosis"] == "trained_path_search":
        order = ["GOLEMS_DOME_SCAN_LIMIT", "GOLEMS_DOME_FEASIBLE_LIMIT",
                 "GOLEMS_DOME_TARGET_WEIGHT", "GOLEMS_DOME_SKY_PENALTY",
                 "GOLEMS_DOME_GRAPPLE_PENALTY"]
    elif previous and previous["diagnosis"] == "grapple_access":
        order = ["GOLEMS_DOME_GRAPPLE_PENALTY",
                 "GOLEMS_DOME_TARGET_WEIGHT", "GOLEMS_DOME_SCAN_LIMIT",
                 "GOLEMS_DOME_FEASIBLE_LIMIT", "GOLEMS_DOME_SKY_PENALTY"]
    for key in order:
        for value in CHOICES[key]:
            candidate = dict(best)
            candidate[key] = value
            if candidate != best and tuple(sorted(candidate.items())) not in seen:
                return candidate
    return None


def reset_generated_test_world():
    """Isolate runs: a previous candidate must never leave physical blocks
    in a later candidate's world or inflate the measured block count.
    Only the generated CI GameTest world's folder is removed.
    """
    run_dir = Path("build/run/gameTest")
    properties = run_dir / "server.properties"
    level_name = "world"
    if properties.is_file():
        for line in properties.read_text(errors="replace").splitlines():
            if line.startswith("level-name="):
                level_name = line.split("=", 1)[1].strip()
                break
    if not level_name or Path(level_name).name != level_name or level_name in (".", ".."):
        raise RuntimeError("Unsafe generated GameTest level-name: " + repr(level_name))
    for candidate in (level_name, level_name + "_nether", level_name + "_the_end"):
        folder = run_dir / candidate
        if folder.is_symlink():
            raise RuntimeError("Refusing to remove linked generated GameTest world")
        if folder.is_dir():
            shutil.rmtree(folder)
            print("Reset previous generated GameTest world:", folder, flush=True)


def run_trial(settings, report, timeout):
    reset_generated_test_world()
    env = dict(os.environ)
    env.update(settings)
    env["GOLEMS_DOME_TRAINING"] = "1"
    started = time.monotonic()
    with report.open("w", encoding="utf-8") as out:
        out.write("Physical GameTest settings: " + json.dumps(settings) + "\n")
        out.flush()
        try:
            process = subprocess.run(
                ["gradle", "--no-daemon", "runGameTest"],
                env=env, stdout=out, stderr=subprocess.STDOUT,
                timeout=timeout, check=False,
            )
            code = process.returncode
        except subprocess.TimeoutExpired:
            out.write("\n[DOME TRAINING] Trial timed out\n")
            code = 124
    log = report.read_text(encoding="utf-8", errors="replace")
    result = parse_result(log, settings)
    result.update({
        "settings": settings,
        "return_code": code,
        "duration_seconds": round(time.monotonic() - started, 1),
        "timestamp": dt.datetime.now(dt.timezone.utc).isoformat(),
        "log": str(report),
    })
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cycles", type=int, default=5)
    parser.add_argument("--max-minutes", type=int, default=42)
    args = parser.parse_args()
    if not (1 <= args.cycles <= 10 and 5 <= args.max_minutes <= 50):
        parser.error("Invalid bounded training duration or cycle count")
    source_sha = git("rev-parse", "HEAD").stdout.strip()
    state = load_state(source_sha)
    out = Path("build/dome-autotune")
    out.mkdir(parents=True, exist_ok=True)
    deadline = time.monotonic() + args.max_minutes * 60
    champion = (dict(state["best"]["settings"]) if state.get("best")
                else dict(DEFAULT))
    previous = state["trials"][-1] if state["trials"] else None
    completed = []
    for cycle in range(args.cycles):
        remaining = deadline - time.monotonic()
        if remaining < 120:
            print("Stopping: insufficient time for a verified GameTest", flush=True)
            break
        if cycle == 0:
            config = champion
        else:
            config = choose_candidate(champion, previous, state["trials"])
            if config is None:
                print("All single-parameter neighbors tested; no new candidate", flush=True)
                break
        trial = run_trial(config, out / f"cycle-{cycle + 1}.log",
                          timeout=min(600, int(remaining - 30)))
        trial["cycle"] = cycle + 1
        trial["source_sha"] = source_sha
        state["trials"].append(trial)
        completed.append(trial)
        previous = trial
        print(f"Cycle {cycle + 1}: verified={trial['verified']} "
              f"blocks={trial['real_blocks']}/{TOTAL} y={trial['active_y']} "
              f"diagnosis={trial['diagnosis']} rc={trial['return_code']}",
              flush=True)
        if not trial["verified"]:
            print("Stopping: no verified physical dome telemetry", flush=True)
            break
        if trial["complete"] or (state.get("best") is None or
                                 quality(trial) > quality(state["best"])):
            state["best"] = dict(trial)
            champion = dict(config)
        if trial["complete"]:
            print("FULL DOME COMPLETION CONFIRMED: stopping tuning", flush=True)
            break
    state["trials"] = state["trials"][-250:]
    state["last_session"] = dt.datetime.now(dt.timezone.utc).isoformat()
    state["last_session_cycles"] = len(completed)
    state["state_branch"] = STATE_BRANCH
    (out / "summary.json").write_text(json.dumps(state, indent=2) + "\n")
    if completed:
        save_state(state)
    summary = [
        "## Physical Dome Autotune",
        f"- Source branch SHA: `{source_sha}`",
        f"- Completed real GameTest trials: **{len(completed)}**",
    ]
    if state.get("best"):
        best = state["best"]
        summary += [
            f"- Best verified physical result: **{best['real_blocks']}/{TOTAL}** "
            f"(Y={best['active_y']})",
            f"- Best bounded heuristic settings: `{json.dumps(best['settings'], sort_keys=True)}`",
        ]
    summary += ["- Never merges or edits main gameplay code automatically.",
                "- Detailed verified trial logs are in the workflow artifact.",
                "- Persistent state branch: " + STATE_BRANCH]
    report = "\n".join(summary) + "\n"
    print(report, flush=True)
    step_summary = os.getenv("GITHUB_STEP_SUMMARY")
    if step_summary:
        with open(step_summary, "a", encoding="utf-8") as stream:
            stream.write(report)
    if not completed or not completed[0]["verified"]:
        raise SystemExit("No successful instrumented physical GameTest trial")


if __name__ == "__main__":
    main()
