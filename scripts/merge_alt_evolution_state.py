#!/usr/bin/env python3
"""Merge real-Minecraft-entity genetic training into durable GitHub state.

Usage: python3 scripts/merge_real_evolution_state.py <incoming-dir> <repository-state-dir>
Only ever compare scores from the same real-entity course and game rules.
Run after GitHub fetch on current remote branch tip, never force-push.
"""
import argparse
import json
import math
from pathlib import Path

FORMAT = "golems-plus-physical-genes-v1"
COURSE = "timetrial-alt-vanilla-10-builders-260-ticks-v2"
GENES = 12
POPULATION = 10


def read(path):
    if not path.is_file():
        return None
    result = json.loads(path.read_text(encoding="utf8"))
    if (result.get("format") != FORMAT
            or result.get("course") != COURSE
            or result.get("physicalEntities") is not True
            or result.get("bots") != POPULATION
            or result.get("deadlineTicks") != 260):
        raise ValueError(f"Incompatible or unverified Minecraft state: {path}")
    return result


def genetic_population(pop):
    if not isinstance(pop, list) or len(pop) != POPULATION:
        raise ValueError("Real golem population must have 10 chromosomes")
    for gene in pop:
        if (not isinstance(gene, list) or len(gene) != GENES
                or any(not isinstance(n, (float, int))
                       or not math.isfinite(n) for n in gene)):
            raise ValueError("Invalid physical genome")


def validate_champion(c):
    if c is None:
        return
    genetic_population(c["winningGenomes"])
    times = c["physicalArrivalRecords"]
    if sorted(x["slot"] for x in times) != list(range(POPULATION)):
        raise ValueError("Champion requires 10 unique real Builder Golems")
    ticks = [x["ticks"] for x in times]
    if (min(ticks) < 0 or max(ticks) > 260
            or max(ticks) != c["slowestTicks"]
            or abs(sum(ticks) / POPULATION - c["meanTicks"]) > 0.001):
        raise ValueError("Champion result is not a true 10/10 under 260 ticks")


def validate_checkpoint(c):
    if c is None:
        return
    genetic_population(c["nextPopulation"])
    keys = ("lastSuccessfulPopulation", "lastSuccessfulArrivalTicks",
            "lastSuccessfulGeneration", "lastSuccessfulSlowestTicks")
    if any(k in c for k in keys):
        if not all(k in c for k in keys):
            raise ValueError("Partial verified whole-crew winning checkpoint")
        genetic_population(c["lastSuccessfulPopulation"])
        ticks = c["lastSuccessfulArrivalTicks"]
        if (not isinstance(ticks, list) or len(ticks) != POPULATION
                or any(not isinstance(t, int) or isinstance(t, bool)
                       or t < 0 or t > 260 for t in ticks)
                or max(ticks) != c["lastSuccessfulSlowestTicks"]
                or not 1 <= c["lastSuccessfulGeneration"] <= c["completedGenerations"]):
            raise ValueError("Winning team must have 10 timely real arrivals")
    if not 0 <= c["bestFinishedEver"] <= POPULATION:
        raise ValueError("Bad best-finished count")
    if c["completedGenerations"] < 0:
        raise ValueError("Negative generation count")
    if (len(c["personalBests"]) != POPULATION
            or len(c["personalScores"]) != POPULATION):
        raise ValueError("Missing lane-wise learned progress")
    for lane in c["personalBests"]:
        if lane is not None:
            if len(lane) != GENES:
                raise ValueError("Bad lane champion")
    if any(not math.isfinite(v) for v in c["personalScores"]):
        raise ValueError("Invalid physical fitness score")
    pool = c.get("knowledgePool", [])
    if not isinstance(pool, list) or len(pool) > 3:
        raise ValueError("Knowledge pool must contain at most 3 real workers")
    slots = set()
    for member in pool:
        slot, score = member.get("slot"), member.get("fitness")
        if (not isinstance(slot, int) or isinstance(slot, bool)
                or not 0 <= slot < POPULATION or slot in slots
                or not isinstance(score, (float, int))
                or not math.isfinite(score)
                or not isinstance(member.get("arrivedTick"), int)
                or not -1 <= member["arrivedTick"] <= 260
                or not isinstance(member.get("progress"), (float, int))
                or not math.isfinite(member["progress"])
                or not isinstance(member.get("generation"), int)
                or not 0 <= member["generation"] <= c["completedGenerations"]):
            raise ValueError("Invalid saved individual knowledge")
        slots.add(slot)
        genome = member.get("genes")
        if (not isinstance(genome, list) or len(genome) != GENES
                or any(not isinstance(n, (int, float))
                       or not math.isfinite(n) for n in genome)):
            raise ValueError("Invalid knowledge-pool chromosome")


def champion_key(c):
    return (c["slowestTicks"], c["meanTicks"]) if c else (math.inf, math.inf)


def merge_checkpoint(old, new, protected):
    if old is None:
        return new
    if new is None:
        return old
    # Prefer the latest physical chromosome offspring. If concurrent
    # server tests complete the same generation, prefer the more
    # successful trial rather than the last writer.
    def merit(c):
        return (c["completedGenerations"],
                c["lastCompleted"],
                c["lastMeanFitness"])
    winning = new if merit(new) > merit(old) else old
    merged = dict(winning)
    merged["completedGenerations"] = max(
        new["completedGenerations"], old["completedGenerations"])
    merged["bestFinishedEver"] = max(
        new["bestFinishedEver"], old["bestFinishedEver"])
    personal, scores = [], []
    for index in range(POPULATION):
        choice = max((new, old), key=lambda c:
                     c["personalScores"][index])
        personal.append(choice["personalBests"][index])
        scores.append(choice["personalScores"][index])
    merged["personalBests"], merged["personalScores"] = personal, scores
    # Both checkouts can improve DIFFERENT golems. Union independent
    # physical evidence by worker slot and preserve the global top THREE
    # even if either checkout came from a failed 10/10 generation.
    candidates = {}
    for source in (old, new):
        for member in source.get("knowledgePool", []):
            slot = member["slot"]
            if (slot not in candidates or
                    member["fitness"] > candidates[slot]["fitness"]):
                candidates[slot] = member
    # Legacy physical checkpoints already retain independent personal
    # best genes. Migrate them without inventing a completed arrival.
    for slot, genes in enumerate(personal):
        if genes is None:
            continue
        score = scores[slot]
        if slot not in candidates or score > candidates[slot]["fitness"]:
            candidates[slot] = {
                "slot": slot, "fitness": score, "genes": genes,
                "arrivedTick": -1, "progress": 0,
                "generation": merged["completedGenerations"],
            }
    merged["knowledgePool"] = sorted(
        candidates.values(), key=lambda m: (-m["fitness"], m["slot"]))[:3]
    # Independently preserve the MOST RECENT successful 10/10 team.
    # A later failed checkout can never erase its full ten genes, and
    # the faster historical best remains separately in champion.json.
    proven = [c for c in (old, new) if "lastSuccessfulPopulation" in c]
    if proven:
        latest = max(proven, key=lambda c: (
            c["lastSuccessfulGeneration"], -c["lastSuccessfulSlowestTicks"]))
        for field in ("lastSuccessfulPopulation",
                      "lastSuccessfulArrivalTicks",
                      "lastSuccessfulGeneration",
                      "lastSuccessfulSlowestTicks"):
            merged[field] = latest[field]
    edges = {}
    for c in (old, new):
        for edge in c.get("blockedEdges", []):
            key = (edge["x"], edge["z"], edge["toX"], edge["toZ"])
            edges[key] = max(edges.get(key, 0), edge["failures"])
    merged["blockedEdges"] = [
        dict(zip(("x", "z", "toX", "toZ"), key), failures=value)
        for key, value in sorted(edges.items())
    ]
    if protected:
        merged["bestFinishedEver"] = POPULATION
    return merged


def merge_history(old_path, incoming_path):
    rows, seen = [], set()
    for path in (old_path, incoming_path):
        if not path.is_file():
            continue
        for line in path.read_text(encoding="utf8").splitlines():
            if not line.strip():
                continue
            record = json.loads(line)
            if (record.get("format") != FORMAT
                    or record.get("course") != COURSE
                    or record.get("physicalEntities") is not True):
                raise ValueError(f"Nonphysical evolution history: {path}")
            compact = json.dumps(record, separators=(",", ":"), sort_keys=True)
            if compact not in seen:
                rows.append(compact)
                seen.add(compact)
    return "\n".join(rows) + ("\n" if rows else "")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("incoming", type=Path)
    parser.add_argument("target", type=Path)
    args = parser.parse_args()
    if not (args.incoming / "checkpoint.json").is_file():
        raise FileNotFoundError("No new real-entity checkpoint; refusing to overwrite old state")
    old_c = read(args.target / "champion.json")
    new_c = read(args.incoming / "champion.json")
    old_p = read(args.target / "checkpoint.json")
    new_p = read(args.incoming / "checkpoint.json")
    validate_champion(old_c)
    validate_champion(new_c)
    validate_checkpoint(old_p)
    validate_checkpoint(new_p)
    best = min((x for x in (old_c, new_c) if x is not None),
               key=champion_key, default=None)
    merged = merge_checkpoint(old_p, new_p, best)
    history = merge_history(args.target / "history.jsonl",
                            args.incoming / "history.jsonl")
    args.target.mkdir(parents=True, exist_ok=True)
    (args.target / "checkpoint.json").write_text(
        json.dumps(merged, indent=2) + "\n", encoding="utf8")
    if best:
        (args.target / "champion.json").write_text(
            json.dumps(best, indent=2) + "\n", encoding="utf8")
    (args.target / "history.jsonl").write_text(history, encoding="utf8")
    print("Preserved physical learning: generations=",
          merged["completedGenerations"],
          "bestChampionSlowestTicks=",
          best["slowestTicks"] if best else "none",
          "historyRecords=", len(history.splitlines()))


if __name__ == "__main__":
    main()
