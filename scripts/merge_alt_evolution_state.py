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
STRATEGY_FORMAT = "golems-context-strategies-v1"
RULE_FORMAT = "golems-local-rules-v1"


def merge_local_rule_memory(*checkpoints):
    trials = {}
    present = False
    for checkpoint in checkpoints:
        memory = (checkpoint or {}).get("localRuleMemory")
        if memory is None:
            continue
        present = True
        if memory.get("format") != RULE_FORMAT or not isinstance(memory.get("trials"), list):
            raise ValueError("Invalid local navigation rule memory")
        for trial in memory["trials"]:
            if (not isinstance(trial.get("id"), str) or not trial["id"]
                    or not isinstance(trial.get("sequence"), int) or trial["sequence"] < 0
                    or not isinstance(trial.get("block"), str) or not trial["block"]
                    or trial.get("repeat") not in (0, 1, 2)
                    or not isinstance(trial.get("challenger"), bool)
                    or not isinstance(trial.get("fingerprint"), str) or not trial["fingerprint"]
                    or not isinstance(trial.get("finished"), int) or not 0 <= trial["finished"] <= 10
                    or not isinstance(trial.get("meanArrivalTicks"), (int, float))
                    or not math.isfinite(trial["meanArrivalTicks"]) or not 0 <= trial["meanArrivalTicks"] <= 260
                    or not isinstance(trial.get("episodes"), list)):
                raise ValueError("Invalid matched physical rule trial")
            for episode in trial["episodes"]:
                if (not isinstance(episode.get("id"), str) or not episode["id"]
                        or not isinstance(episode.get("slot"), int) or not 0 <= episode["slot"] < 10
                        or not isinstance(episode.get("context"), str) or not 1 <= len(episode["context"]) <= 200
                        or episode.get("action") not in ("BASELINE", "STABLE_MERGE", "YIELD_ASIDE")
                        or not isinstance(episode.get("cleared"), bool)
                        or not isinstance(episode.get("elapsedTicks"), int) or not 1 <= episode["elapsedTicks"] <= 60
                        or not isinstance(episode.get("stationaryTicks"), int)
                        or not 0 <= episode["stationaryTicks"] <= episode["elapsedTicks"]
                        or not isinstance(episode.get("collisions"), int) or episode["collisions"] < 0):
                    raise ValueError("Invalid local obstacle episode")
            identifier = trial["id"]
            if identifier in trials and trials[identifier] != trial:
                raise ValueError("Conflicting matched physical trial: " + identifier)
            trials[identifier] = trial
    if not present:
        return None
    return dict(format=RULE_FORMAT, trials=sorted(trials.values(), key=lambda t: (t["sequence"], t["id"])))


def merge_strategy_memory(*checkpoints):
    episodes = {}
    present = False
    for checkpoint in checkpoints:
        memory = (checkpoint or {}).get("strategyMemory")
        if memory is None:
            continue
        present = True
        if memory.get("format") != STRATEGY_FORMAT or not isinstance(memory.get("observations"), list):
            raise ValueError("Invalid contextual navigation memory")
        for episode in memory["observations"]:
            identifier = episode.get("id")
            deadline = episode.get("deadlineTicks")
            if (not isinstance(identifier, str) or not identifier
                    or not isinstance(episode.get("sequence"), int) or episode["sequence"] < 0
                    or not isinstance(episode.get("context"), str)
                    or not 1 <= len(episode["context"]) <= 200
                    or episode.get("strategy") not in ("DIRECT", "SMOOTH", "DOOR")
                    or deadline != 260
                    or not isinstance(episode.get("arrivedTicks"), int)
                    or not -1 <= episode["arrivedTicks"] <= deadline
                    or not isinstance(episode.get("stationaryTicks"), int)
                    or not 0 <= episode["stationaryTicks"] <= deadline
                    or not isinstance(episode.get("collisions"), int) or episode["collisions"] < 0
                    or not isinstance(episode.get("distanceMeters"), (int, float))
                    or not math.isfinite(episode["distanceMeters"]) or episode["distanceMeters"] < 0):
                raise ValueError("Invalid physical strategy episode")
            if identifier in episodes and episodes[identifier] != episode:
                raise ValueError("Conflicting physical strategy episode: " + identifier)
            episodes[identifier] = episode
    if not present:
        return None
    return dict(format=STRATEGY_FORMAT, observations=sorted(episodes.values(),
                key=lambda e: (e["sequence"], e["id"])))


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
    merge_strategy_memory(c)
    merge_local_rule_memory(c)
    if "consistencyPopulation" in c:
        genetic_population(c["consistencyPopulation"])
    if "consistencyStrategies" in c and (len(c["consistencyStrategies"]) != POPULATION
            or any(s not in ("DIRECT", "SMOOTH", "DOOR") for s in c["consistencyStrategies"])):
        raise ValueError("Invalid frozen route policy")
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
    local_rules = merge_local_rule_memory(old, new)
    if local_rules is not None:
        merged["localRuleMemory"] = local_rules
    for field in ("consistencyPopulation", "consistencyStrategies"):
        if field not in merged:
            for source in (new, old):
                if field in source:
                    merged[field] = source[field]
                    break
    strategy_memory = merge_strategy_memory(old, new)
    if strategy_memory is not None:
        merged["strategyMemory"] = strategy_memory
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
    paths = [old_path, incoming_path]
    for parent in {old_path.parent, incoming_path.parent}:
        paths.extend(sorted(parent.glob('history-recovered-*.jsonl')))
    for path in paths:
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
    rows.sort(key=lambda line: (
        json.loads(line).get("completedGenerations", 0),
        json.loads(line).get("replayFile", ""), line))
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
