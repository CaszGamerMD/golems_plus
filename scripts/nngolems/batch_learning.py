"""Multi-build physical data collector and cross-build shadow-only trainer."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import random
import re

from train import Network, metrics, read_rows
from actions import read_actions
from placements import read_placements

BUILDS = ("dragon", "dome", "farm")
REASONS = ("missing physical support", "search_no_route", "search_partial",
           "no confirmed reachable work stance/exit", "no_candidates")
MARKERS = {
    "[NN FRONTIER SNAPSHOT]": "frontier",
    "[NN FRONTIER STAGE STOP]": "stage_stop",
    "[NN ROOF GATE]": "roof_gate",
    "[NN ROOF PROBE]": "roof_probe",
    "[NN ROOF APPROVED]": "roof_approved",
    "[CREW RETREAT START]": "crew_retreat",
    "[NN PROJECT YIELD]": "project_switch",
    "[NN_CURRICULUM_WORKER]": "worker_final",
    "[NN DOME WORKER]": "worker_final",
}


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def write_rows(path, rows, prefix=""):
    with path.open("w") as out:
        for row in rows:
            out.write(prefix + json.dumps(row, sort_keys=True, separators=(",", ":")) + "\n")


def marked(lines, marker):
    rows = []
    for line in lines:
        if marker in line:
            rows.append(json.loads(line.split(marker, 1)[1]))
    return rows


def physical_result(lines, course):
    marker = "[NN DOME RESULT] " if course == "dome" else "[NN_SPEED_RESULT] "
    found = [line.split(marker, 1)[1] for line in lines if marker in line]
    if len(found) != 1:
        raise ValueError("Expected one terminal physical result: " + course)
    fields = dict(re.findall(r"([A-Za-z][A-Za-z0-9]*)=([A-Za-z0-9_.+-]+)", found[0]))
    if course != "dome" and fields.get("course") != course:
        raise ValueError("Wrong course in terminal result")
    correct, total = int(fields["correct"]), int(fields["total"])
    if not 0 <= correct <= total or not total:
        raise ValueError("Invalid exact-world correctness")
    return dict(outcome=fields["outcome"], correct=correct, total=total,
                correct_fraction=correct / total,
                elapsed_ticks=int(fields.get("elapsedTicks", fields.get("ticks", 0))))


def diagnostics(lines, course, trial):
    rows = []
    for line in lines:
        matched = next(((key, label) for key, label in MARKERS.items() if key in line), None)
        if matched:
            key, label = matched
            detail = line.split(key, 1)[1].strip()
            rows.append(dict(course=course, trial=trial, category=label,
                             reasons=[r for r in REASONS if r in detail],
                             detail=detail[:2200], diagnostic_only=True))
    return rows


def collect(log, output, course, trial, crew, commit, attempt, policy, exit_code):
    if course not in BUILDS or not 1 <= trial <= 3 or crew < 1:
        raise ValueError("Invalid batch case")
    output.mkdir(parents=True, exist_ok=True)
    raw = log.read_bytes()
    lines = raw.decode("utf-8", errors="replace").splitlines()
    manifest = dict(schema=1, course=course, trial=trial, crew=crew,
                    commit=commit, attempt=attempt, policy=policy,
                    gradle_exit=exit_code, log_sha256=hashlib.sha256(raw).hexdigest(),
                    model_controls_gameplay=False)
    try:
        result = physical_result(lines, course)
        movements = read_rows([log])
        actions = read_actions([log])
        placements = read_placements([log])
        team = marked(lines, "[NNGOLEMS_TEAM] ")
        if not movements or not team or exit_code != 0:
            raise ValueError("No valid movement/team observations or GameTest failed")
        runs = {r["run"] for r in movements + actions + placements}
        if len(runs) != 1 or len({r["run"] for r in team}) != 1:
            raise ValueError("Mixed/missing server-run identity")
        if any(r["course"] != course or r["crew"] != crew
               or r["placedDelta"] != r["endCorrect"] - r["start"]["correct"]
               for r in team):
            raise ValueError("Invalid independent team progress measurements")
        diag = diagnostics(lines, course, trial)
        progress_marker = "[NN DOME PROGRESS]" if course == "dome" else "[NN_CURRICULUM_PROGRESS]"
        progress = []
        for line in lines:
            if progress_marker in line:
                fields = dict(re.findall(r"([A-Za-z][A-Za-z0-9]*)=([A-Za-z0-9_.+-]+)",
                                         line.split(progress_marker, 1)[1]))
                if "correct" in fields and "total" in fields:
                    progress.append(dict(tick=int(fields.get("tick", 0)),
                                         correct=int(fields["correct"]),
                                         total=int(fields["total"]),
                                         layer=fields.get("layer", "unknown")))
        write_rows(output / "samples.log", movements, "[NNGOLEMS] ")
        write_rows(output / "actions.log", actions, "[NNGOLEMS_ACTION] ")
        write_rows(output / "placements.log", placements, "[NNGOLEMS_PLACEMENT] ")
        write_rows(output / "team.jsonl", team)
        write_rows(output / "diagnostics.jsonl", diag)
        write_rows(output / "progress.jsonl", progress)
        manifest.update(result, valid=True, run=next(iter(runs)),
                        team_run=team[0]["run"],
                        movement_samples=len(movements),
                        movement_stationary=sum(not r["moved"] for r in movements),
                        flight_actions=len(actions), placements=len(placements),
                        team_windows=len(team),
                        diagnostics=dict(Counter(d["category"] for d in diag)),
                        failure_reasons=dict(Counter(x for d in diag for x in d["reasons"])))
    except Exception as exc:
        manifest.update(valid=False, error=str(exc))
        write_json(output / "manifest.json", manifest)
        raise
    write_json(output / "manifest.json", manifest)
    return manifest


def sample_per_run(rows, cap, seed):
    rng = random.Random(seed)
    pools = [[r for r in rows if r["moved"] == label] for label in (0, 1)]
    for pool in pools:
        pool.sort(key=lambda r: (r["run"], r["worker"], r["tick"]))
        rng.shuffle(pool)
    return pools[0][:cap // 2] + pools[1][:cap // 2]


def train_shadow(entries, output):
    train = read_rows([p / "samples.log" for p, m in entries if m["course"] in ("dragon", "dome")])
    held = read_rows([p / "samples.log" for p, m in entries if m["course"] == "farm"])
    train_ids, held_ids = {r["run"] for r in train}, {r["run"] for r in held}
    if train_ids & held_ids:
        raise ValueError("Training/held-out server ID overlap")
    report = dict(training_builds=["dragon", "dome"], held_out_build="farm",
                  training_runs=sorted(train_ids), held_out_runs=sorted(held_ids),
                  label="moved_at_least_0.25m_in_20_ticks", shadow_only=True)
    if (len(train_ids) < 3 or len(held_ids) < 3
            or {r["moved"] for r in train} != {0, 1}
            or {r["moved"] for r in held} != {0, 1}
            or {r["schema"] for r in train + held} != {3}):
        report["status"] = "INSUFFICIENT_CROSS_BUILD_DATA"
        write_json(output / "movement-model-report.json", report)
        return report
    capped = []
    for i, run_id in enumerate(sorted(train_ids)):
        capped.extend(sample_per_run([r for r in train if r["run"] == run_id],
                                     600, 17 + i))
    if {r["moved"] for r in capped} != {0, 1}:
        report["status"] = "TRAINING_CLASSES_MISSING"
        write_json(output / "movement-model-report.json", report)
        return report
    model = Network(seed=17, hidden=8, schema=3)
    model.fit(capped, epochs=20, rate=.015)
    majority = int(sum(r["moved"] for r in capped) * 2 >= len(capped))
    report.update(status="SHADOW_ONLY_TRAINED", training_samples=len(capped),
                  held_out=metrics(model, held), training=metrics(model, capped),
                  held_out_majority_baseline=sum(r["moved"] == majority for r in held) / len(held),
                  by_held_out_run={run: metrics(model, [r for r in held if r["run"] == run])
                                   for run in sorted(held_ids)},
                  warning="Movement prediction does not prove build success; model cannot steer.")
    write_json(output / "movement-model.json", model.export())
    write_json(output / "movement-model-report.json", report)
    return report


def aggregate(root, output, commit, expected):
    output.mkdir(parents=True, exist_ok=True)
    entries = []
    for path in sorted(root.glob("nn-learning-*/manifest.json")):
        m = json.loads(path.read_text())
        if m["commit"] != commit or m["course"] not in BUILDS:
            raise ValueError("Mixed revision or build")
        entries.append((path.parent, m))
    ids = [(m["course"], m["trial"]) for _, m in entries]
    if len(set(ids)) != len(ids):
        raise ValueError("Duplicate trials")
    by_build = {}
    for course in BUILDS:
        cases = [m for _, m in entries if m["course"] == course and m.get("valid")]
        scores = [m["correct"] for m in cases]
        by_build[course] = dict(valid_runs=len(cases), correct=scores,
                                full_completions=sum(m["outcome"] == "COMPLETE" for m in cases),
                                mean_correct=sum(scores) / len(scores) if scores else None,
                                best=max(scores) if scores else None,
                                worst=min(scores) if scores else None)
    summary = dict(schema=1, commit=commit, expected=expected,
                   received=len(entries),
                   valid=sum(m.get("valid", False) for _, m in entries),
                   builds=by_build, models_never_deployed=True)
    write_json(output / "summary.json", summary)
    report = ["# NNGolems multi-build training batch", "",
              "Commit: " + commit, "",
              "| Build | Valid runs | Exact correct blocks per run | Completed |",
              "|---|---:|---|---:|"]
    for course in BUILDS:
        case = by_build[course]
        report.append("| " + course + " | " + str(case["valid_runs"]) + " | "
                      + ", ".join(map(str, case["correct"])) + " | "
                      + str(case["full_completions"]) + " |")
    report.extend(["", "Train on Dragon+Dome; Farm Demo is unseen-build evaluation.",
                   "Counts reflect world state, not the builder ledger.", ""])
    if len(entries) != expected or summary["valid"] != expected:
        report.append("INCOMPLETE: missing or invalid trials; model not trained.")
        (output / "report.md").write_text("\n".join(report) + "\n")
        raise ValueError("Incomplete batch; see summary.json")
    model = train_shadow(entries, output)
    summary["movement_model_status"] = model["status"]
    write_json(output / "summary.json", summary)
    for filename in ("samples.log", "actions.log", "placements.log",
                     "team.jsonl", "diagnostics.jsonl", "progress.jsonl"):
        with (output / filename).open("w") as destination:
            for folder, m in entries:
                src = folder / filename
                if not src.exists():
                    raise ValueError("Missing " + str(src))
                if filename.endswith(".log"):
                    destination.write(src.read_text())
                else:
                    for line in src.read_text().splitlines():
                        if line:
                            destination.write(json.dumps(dict(
                                course=m["course"], trial=m["trial"],
                                held_out=m["course"] == "farm",
                                commit=m["commit"], data=json.loads(line)),
                                separators=(",", ":")) + "\n")
    report.append("Movement network: " + model["status"]
                  + " (shadow-only). Open movement-model-report.json for Farm evaluation.")
    (output / "report.md").write_text("\n".join(report) + "\n")
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    a = sub.add_parser("collect")
    a.add_argument("log", type=Path)
    a.add_argument("output", type=Path)
    a.add_argument("--course", choices=BUILDS, required=True)
    a.add_argument("--trial", type=int, required=True)
    a.add_argument("--crew", type=int, required=True)
    a.add_argument("--commit", required=True)
    a.add_argument("--attempt", required=True)
    a.add_argument("--policy", required=True)
    a.add_argument("--exit-status", type=int, required=True)
    b = sub.add_parser("aggregate")
    b.add_argument("root", type=Path)
    b.add_argument("output", type=Path)
    b.add_argument("--commit", required=True)
    b.add_argument("--expected", type=int, default=9)
    args = parser.parse_args()
    if args.command == "collect":
        print(json.dumps(collect(args.log, args.output, args.course,
                                 args.trial, args.crew, args.commit, args.attempt,
                                 args.policy, args.exit_status), indent=2))
    else:
        print(json.dumps(aggregate(args.root, args.output, args.commit,
                                   args.expected), indent=2))


if __name__ == "__main__":
    main()
