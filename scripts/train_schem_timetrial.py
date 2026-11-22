#!/usr/bin/env python3
"""Generation-by-generation 360-tick survival navigation time trial.

Input is converted from the actual Sponge .schem: EVERY green wool spawn
has a real candidate assigned, and ANY red wool is a finish. All bots
are reset after the fixed 360 game-tick deadline. Selection/crossover/
mutation repeats until ALL bots in ONE generation make it, or a stated
resource cap is reached. No scaffolds, breaking, flying, teleports or
speed bonuses.

CURRENT ENGINE: headless course simulation. It is not yet a Minecraft
entity GameTest. Output always declares simulated_only=true.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import random
import statistics
import time
from dataclasses import asdict
from pathlib import Path

from builder_nav_evolution import Course, GENES, Genome, simulate
from schem_course_import import convert, load_input

TICKS_PER_GENERATION = 360
SEED_SCALE = 10007


def build_spawn_courses(document: dict) -> list[Course]:
    rows = document["rows"]
    greens = sorted(tuple(p) for p in document["spawn_tiles"])
    red = {tuple(p) for p in document["finish_tiles"]}
    if not greens or not red:
        raise ValueError("A valid time trial requires green and red wool")
    if len(greens) > 256:
        raise ValueError("Too many independent bot spawn positions")
    arenas = []
    for position in greens:
        grid = []
        for z, line in enumerate(rows):
            grid.append("".join(
                "S" if (x, z) == position else
                "G" if (x, z) in red else
                "." if char == "S" else char
                for x, char in enumerate(line)
            ))
        course = Course.from_dict({
            "name": f"{document['name']}-start-{position[0]}-{position[1]}",
            "rows": grid,
        })
        if course.oracle_distance(course.start) is None:
            raise ValueError(f"No walkable route from green wool at {position}")
        arenas.append(course)
    return arenas


def score_run(run, course: Course) -> float:
    # Adjust for different shortest walk lengths at the twenty spawn
    # positions; do not favor a bot simply because its start is nearer.
    shortest = course.oracle_distance(course.start)
    if shortest is None:
        raise ValueError("Unreachable green start")
    return run.fitness + (shortest - 50) * 16 if run.finished else run.fitness


def train_time_trial(source: dict, *, generations: int = 250,
                     timeout_ticks: int = TICKS_PER_GENERATION,
                     seed: int = 42,
                     wall_seconds: float = 270.0,
                     keep_replays: bool = True,
                     print_generation: bool = True) -> dict:
    if timeout_ticks != TICKS_PER_GENERATION:
        raise ValueError("Time trial is fixed to 18s = 360 Minecraft ticks")
    if not 1 <= generations <= 100000:
        raise ValueError("Invalid generation limit")
    arenas = build_spawn_courses(source)
    pop_size = len(arenas)
    rng = random.Random(seed)
    genomes = [Genome.random(rng) for _ in arenas]
    # Island champions preserve good solutions for each green start,
    # while less successful starts inherit DNA from successful bots.
    best_per_spawn = [None for _ in arenas]
    best_score = [-float("inf") for _ in arenas]
    history = []
    winning = []
    deadline = time.monotonic() + wall_seconds if wall_seconds > 0 else None
    stopped_for_budget = False
    best_count = 0
    first_score = -float("inf")
    first_genomes = None
    first_runs = None

    for generation in range(generations):
        if deadline is not None and time.monotonic() >= deadline:
            stopped_for_budget = True
            break
        runs = []
        ranks = []
        records = []
        for index, (course, genome) in enumerate(zip(arenas, genomes)):
            bot_seed = seed + index * SEED_SCALE
            outcome = simulate(course, genome, timeout=timeout_ticks,
                               seed=bot_seed)
            if outcome.ticks > timeout_ticks:
                raise AssertionError("Bot exceeded the 360-tick kill deadline")
            run_score = score_run(outcome, course)
            runs.append(outcome)
            ranks.append(run_score)
            records.append({
                "bot": index,
                "start": list(course.start),
                "finished": outcome.finished,
                "arrival_ticks": outcome.ticks if outcome.finished else None,
                "removed_at_ticks": TICKS_PER_GENERATION,
                "gene": asdict(genome),
                "route": list(outcome.path) if keep_replays else [],
                "progress": outcome.progress,
                "collisions": outcome.collisions,
            })
            if run_score > best_score[index]:
                best_per_spawn[index] = genome
                best_score[index] = run_score
        finished = sum(1 for run in runs if run.finished)
        best_count = max(best_count, finished)
        max_ticks = max((run.ticks for run in runs if run.finished),
                        default=None)
        # Every generation contains exactly all of the green wool
        # starting cells. No success based on "best of generation".
        complete = finished == pop_size and all(
            run.ticks <= TICKS_PER_GENERATION for run in runs)
        row = {
            "generation": generation,
            "population": pop_size,
            "finished_by_18s": finished,
            "failed_and_reset": pop_size - finished,
            "deadline_ticks": TICKS_PER_GENERATION,
            "slowest_success_ticks": max_ticks or "",
            "mean_fitness": round(statistics.mean(ranks), 2),
            "entire_generation_passed": complete,
        }
        history.append(row)
        if print_generation:
            print(json.dumps(row), flush=True)
        if finished > (sum(r.finished for r in first_runs) if first_runs
                       else -1):
            first_genomes = list(genomes)
            first_runs = list(runs)
        if complete:
            winning = records
            break

        # Keep a top global breeding pool and the best policy recorded
        # for each spawn. True elite preservation means a lane that
        # already succeeds is NOT deliberately made worse next generation.
        pool = sorted(range(pop_size), key=lambda n: ranks[n],
                      reverse=True)
        best = [genomes[i] for i in pool[:max(4, pop_size // 4)]]
        inherited = [best_per_spawn[i] for i in pool
                     if best_per_spawn[i] is not None]
        next_generation = []
        for index in range(pop_size):
            champion = best_per_spawn[index]
            if champion is not None and best_score[index] >= 100000:
                # This spawn is solved; it continues to contribute its
                # navigation settings to the global gene pool.
                next_generation.append(champion)
                continue
            if rng.random() < 0.12:
                next_generation.append(Genome.random(rng))
                continue
            parents = best + inherited[:max(4, pop_size // 4)]
            mother = rng.choice(parents)
            father = rng.choice(parents)
            child = mother.crossover(father, rng).mutate(
                rng, strength=rng.choice((.07, .15, .28, .48)))
            next_generation.append(child)
        genomes = next_generation

    elapsed_generations = len(history)
    winner = bool(winning)
    best_generation = max(history,
                          key=lambda d: (d["finished_by_18s"],
                                         d["mean_fitness"])) if history else None
    return {
        "format": "golems-plus-schem-time-trial-evolution-v1",
        "simulated_only": True,
        "minecraft_entity_game_test_passed": False,
        "course_sha256": source["source_sha256"],
        "course_dimensions": source["schematic_dimensions"],
        "collision_box": [0.65, 0.65, 1.6],
        "speed_cap_m_per_s": 4.0,
        "forbidden_actions": ["break", "place", "scaffold", "fly", "teleport"],
        "start_tile": "minecraft:green_wool",
        "finish_tile": "minecraft:red_wool",
        "generation_duration_ticks": TICKS_PER_GENERATION,
        "generation_duration_seconds": 18,
        "bots_per_generation": pop_size,
        "starts_covered": [list(arena.start) for arena in arenas],
        "accepted": winner,
        "generations_run": elapsed_generations,
        "reached_generation_limit": not winner and not stopped_for_budget,
        "reached_compute_budget": stopped_for_budget,
        "max_generations": generations,
        "best_finished_count": best_count,
        "best_generation": best_generation,
        "history": history,
        "winning_generation": winning,
        "best_attempt": [{
            "start": list(arena.start),
            "genome": asdict(genome),
            "finished": outcome.finished,
            "ticks": outcome.ticks,
            "path": list(outcome.path) if keep_replays else [],
        } for arena, genome, outcome in zip(arenas, first_genomes or [],
                                            first_runs or [])],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("schematic", type=Path,
                        help="Sponge .schem or its lossless .base64 file")
    parser.add_argument("--generations", type=int, default=250,
                        help="Safety cap. No result is called a win if unmet.")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--wall-seconds", type=float, default=270.0,
                        help="CI execution budget in real CPU/wall seconds")
    parser.add_argument("--out", type=Path,
                        default=Path("build/timetrial-training"))
    args = parser.parse_args()
    source = convert(load_input(args.schematic), "timetrial")
    result = train_time_trial(source, generations=args.generations,
                              seed=args.seed, wall_seconds=args.wall_seconds)
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out/"results.json").write_text(json.dumps(result, indent=2) + "\n",
                                         encoding="utf-8")
    with (args.out/"history.csv").open("w", newline="",
                                       encoding="utf-8") as file:
        if result["history"]:
            writer = csv.DictWriter(file,
                                    fieldnames=result["history"][0].keys())
            writer.writeheader()
            writer.writerows(result["history"])
    print(f"RESULT: {result['best_finished_count']}/"
          f"{result['bots_per_generation']} finished by 18s; "
          f"whole_generation_passed={result['accepted']}, "
          f"generations={result['generations_run']}. "
          f"This is a HEADLESS simulator, not yet Minecraft GameTest.")
    if not result["accepted"]:
        print("Training cap/budget exhausted without an entire passing "
              "generation. No false acceptance.")


if __name__ == "__main__":
    main()
