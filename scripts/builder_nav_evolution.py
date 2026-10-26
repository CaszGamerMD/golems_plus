#!/usr/bin/env python3
"""Experimental evolutionary navigation trainer for Builder Golems.

This is a deterministic, headless COURSE SIMULATOR, not Minecraft GameTest.
The course interface can later be backed by actual Minecraft observations
and entity physics. Until then, results must not be reported as in-game runs.

Hard rules: never break/place blocks, never scaffold/fly, no diagonal motion,
and no horizontal movement faster than 4.0 Minecraft blocks per second.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import heapq
import json
import math
import random
from collections import deque
from dataclasses import asdict, dataclass
from pathlib import Path

TICKS_PER_SECOND = 20
METERS_PER_SECOND = 4.0
BODY_WIDTH_BLOCKS = 0.65
BODY_HEIGHT_BLOCKS = 1.6
HALF_WIDTH = BODY_WIDTH_BLOCKS / 2
TICKS_PER_BLOCK = math.ceil(TICKS_PER_SECOND / METERS_PER_SECOND)  # 5
DOOR_OPEN_TICKS = 8
JUMP_TICKS = 5
SIGHT_RADIUS = 4
DIRECTIONS = ((0, -1), (1, 0), (0, 1), (-1, 0))
GENES = {
    "goal_weight": (0.6, 3.0),
    "unknown_bonus": (0.0, 2.4),
    "wall_penalty": (0.0, 2.5),
    "door_penalty": (0.0, 3.0),
    "revisit_penalty": (0.0, 4.5),
    "turn_penalty": (0.0, 1.5),
    "uphill_penalty": (0.0, 3.0),
    "tie_jitter": (0.0, 0.35),
}


@dataclass(frozen=True)
class Course:
    name: str
    rows: tuple[str, ...]
    widths: tuple[tuple[float, ...], ...]
    clearances: tuple[tuple[float, ...], ...]
    start: tuple[int, int]
    goal: tuple[int, int]
    sha256: str

    @classmethod
    def from_dict(cls, data: dict) -> "Course":
        name = data.get("name", "unnamed-course")
        rows = data.get("rows")
        if (not isinstance(rows, list) or not rows
                or not all(isinstance(row, str) for row in rows)):
            raise ValueError("Course rows must be a nonempty array of strings")
        if not len(rows[0]) or any(len(row) != len(rows[0]) for row in rows):
            raise ValueError("Every course row must have equal nonzero length")
        allowed = set("#.SDG0123456789")
        if any(char not in allowed for row in rows for char in row):
            raise ValueError("Allowed course tiles: # wall, . floor, S, G, D door, 0-9 height")
        starts = [(x, z) for z, row in enumerate(rows)
                  for x, char in enumerate(row) if char == "S"]
        goals = [(x, z) for z, row in enumerate(rows)
                 for x, char in enumerate(row) if char == "G"]
        if len(starts) != 1 or len(goals) != 1:
            raise ValueError("Each course must contain exactly one S and one G")
        if len(rows) > 128 or len(rows[0]) > 128:
            raise ValueError("Initial training arena cannot exceed 128 x 128")
        def profile(key: str, default: float) -> tuple[tuple[float, ...], ...]:
            raw = data.get(key)
            if raw is None:
                return tuple(tuple(default for _ in row) for row in rows)
            if (not isinstance(raw, list) or len(raw) != len(rows)
                    or not all(isinstance(line, list)
                               and len(line) == len(rows[0]) for line in raw)):
                raise ValueError(f"{key} must match the course's rows and columns")
            result = []
            for line in raw:
                values = []
                for n in line:
                    if (isinstance(n, bool) or not isinstance(n, (int, float))
                            or not math.isfinite(n) or n <= 0):
                        raise ValueError(f"{key} requires positive finite numbers")
                    values.append(float(n))
                result.append(tuple(values))
            return tuple(result)
        widths = profile("widths", 1.0)
        clearances = profile("clearances", 3.0)
        canonical = json.dumps({"rows": rows, "widths": widths,
                                "clearances": clearances}, sort_keys=True)
        fingerprint = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
        return cls(str(name), tuple(rows), widths, clearances,
                   starts[0], goals[0], fingerprint)

    @classmethod
    def from_path(cls, path: Path) -> "Course":
        return cls.from_dict(json.loads(path.read_text(encoding="utf-8")))

    @property
    def width(self) -> int:
        return len(self.rows[0])

    @property
    def depth(self) -> int:
        return len(self.rows)

    def tile(self, point: tuple[int, int]) -> str:
        x, z = point
        if 0 <= x < self.width and 0 <= z < self.depth:
            return self.rows[z][x]
        return "#"

    def height(self, point: tuple[int, int]) -> int:
        tile = self.tile(point)
        return int(tile) if tile.isdigit() else 0

    def can_occupy(self, point: tuple[int, int]) -> bool:
        """Static full-body fit at a cell center, not just feet occupancy."""
        x, z = point
        if self.tile(point) == "#":
            return False
        return (self.widths[z][x] + 1e-8 >= BODY_WIDTH_BLOCKS
                and self.clearances[z][x] + 1e-8 >= BODY_HEIGHT_BLOCKS)

    def segment_clear(self, a: tuple[int, int],
                      b: tuple[int, int]) -> bool:
        """Sweep the 0.65-wide footprint between centers.

        This checks solid full blocks in the 2D course at 0.05-block
        intervals and declared narrow-gap/low-ceiling profiles. Minecraft
        jump arcs and partial block shapes need later live integration.
        """
        for sample in range(21):
            t = sample / 20
            x = a[0] + .5 + (b[0] - a[0]) * t
            z = a[1] + .5 + (b[1] - a[1]) * t
            left = math.floor(x - HALF_WIDTH + 1e-8)
            right = math.floor(x + HALF_WIDTH - 1e-8)
            top = math.floor(z - HALF_WIDTH + 1e-8)
            bottom = math.floor(z + HALF_WIDTH - 1e-8)
            for cx in range(left, right + 1):
                for cz in range(top, bottom + 1):
                    if not self.can_occupy((cx, cz)):
                        return False
        return True

    def legal_step(self, a: tuple[int, int], b: tuple[int, int]) -> bool:
        """Strict cardinal movement; can jump up 1, drop up to 2."""
        if abs(a[0] - b[0]) + abs(a[1] - b[1]) != 1:
            return False
        if not self.can_occupy(a) or not self.can_occupy(b):
            return False
        change = self.height(b) - self.height(a)
        if not -2 <= change <= 1:
            return False
        # A jump also requires some headroom above the departure surface.
        if change > 0 and self.clearances[a[1]][a[0]] < BODY_HEIGHT_BLOCKS + .4:
            return False
        return self.segment_clear(a, b)

    def oracle_distance(self, position: tuple[int, int]) -> int | None:
        """Score-only shortest path; never given to the simulated bot."""
        queue = deque([(position, 0)])
        seen = {position}
        while queue:
            at, distance = queue.popleft()
            if at == self.goal:
                return distance
            for dx, dz in DIRECTIONS:
                next_pos = (at[0] + dx, at[1] + dz)
                if next_pos not in seen and self.legal_step(at, next_pos):
                    seen.add(next_pos)
                    queue.append((next_pos, distance + 1))
        return None


@dataclass(frozen=True)
class Genome:
    goal_weight: float
    unknown_bonus: float
    wall_penalty: float
    door_penalty: float
    revisit_penalty: float
    turn_penalty: float
    uphill_penalty: float
    tie_jitter: float

    @classmethod
    def random(cls, rng: random.Random) -> "Genome":
        return cls(**{key: rng.uniform(*limits) for key, limits in GENES.items()})

    def mutate(self, rng: random.Random, strength: float = 0.18) -> "Genome":
        values = asdict(self)
        for key, (minimum, maximum) in GENES.items():
            if rng.random() < 0.40:
                span = maximum - minimum
                values[key] = max(minimum, min(maximum,
                        values[key] + rng.gauss(0, strength * span)))
        return Genome(**values)

    def crossover(self, other: "Genome", rng: random.Random) -> "Genome":
        return Genome(**{key: (getattr(self, key) if rng.random() < .5
                                else getattr(other, key)) for key in GENES})


@dataclass(frozen=True)
class Run:
    course: str
    finished: bool
    ticks: int
    fitness: float
    progress: float
    explored: int
    collisions: int
    doors_opened: int
    path: tuple[tuple[int, int, int], ...]  # tick, x, z


def _visible(course: Course, a: tuple[int, int], b: tuple[int, int]) -> bool:
    """Block-only ray sensing: do not grant x-ray vision through walls."""
    x0, z0 = a
    x1, z1 = b
    dx, dz = abs(x1 - x0), abs(z1 - z0)
    sx, sz = (1 if x0 < x1 else -1), (1 if z0 < z1 else -1)
    err = dx - dz
    while (x0, z0) != (x1, z1):
        if (x0, z0) != a and course.tile((x0, z0)) == "#":
            return False
        e2 = 2 * err
        if e2 > -dz:
            err -= dz
            x0 += sx
        if e2 < dx:
            err += dx
            z0 += sz
    return True


def _observe(course: Course, position: tuple[int, int],
             belief: dict[tuple[int, int], str]) -> None:
    x, z = position
    for dz in range(-SIGHT_RADIUS, SIGHT_RADIUS + 1):
        for dx in range(-SIGHT_RADIUS, SIGHT_RADIUS + 1):
            p = (x + dx, z + dz)
            if (abs(dx) + abs(dz) <= SIGHT_RADIUS
                    and 0 <= p[0] < course.width
                    and 0 <= p[1] < course.depth
                    and _visible(course, position, p)):
                belief[p] = course.tile(p)


def _sensed_height(tile: str) -> int:
    return int(tile) if tile.isdigit() else 0


def _plan(course: Course, position: tuple[int, int], genome: Genome,
          belief: dict[tuple[int, int], str],
          visits: dict[tuple[int, int], int], previous: tuple[int, int] | None,
          opened: set[tuple[int, int]],
          rng: random.Random) -> tuple[int, int] | None:
    """Weighted A* through locally observed and unknown cells.
    Only the true-world physics adapter below is allowed to move the bot.
    """
    def heuristic(point: tuple[int, int]) -> float:
        return (abs(point[0] - course.goal[0])
                + abs(point[1] - course.goal[1])) * genome.goal_weight

    serial = 0
    heap: list[tuple[float, int, tuple[int, int]]] = [
        (heuristic(position), serial, position)]
    costs = {position: 0.0}
    parent: dict[tuple[int, int], tuple[int, int]] = {}
    settled: set[tuple[int, int]] = set()
    while heap and len(settled) < course.width * course.depth:
        _, _, point = heapq.heappop(heap)
        if point in settled:
            continue
        settled.add(point)
        if point == course.goal:
            node = point
            while parent.get(node) is not None and parent[node] != position:
                node = parent[node]
            return node if node != position else None
        x, z = point
        for dx, dz in DIRECTIONS:
            neighbor = x + dx, z + dz
            if not (0 <= neighbor[0] < course.width
                    and 0 <= neighbor[1] < course.depth):
                continue
            tile = belief.get(neighbor)
            if tile == "#":
                continue
            old_tile = belief.get(point)
            if (tile is not None and old_tile is not None
                    and not -2 <= _sensed_height(tile)
                    - _sensed_height(old_tile) <= 1):
                continue
            new_cost = 1.0
            if tile is None:
                new_cost -= genome.unknown_bonus * .32
            if tile == "D" and neighbor not in opened:
                new_cost += genome.door_penalty
            if tile is not None and old_tile is not None:
                uphill = max(0, _sensed_height(tile)
                                - _sensed_height(old_tile))
                new_cost += uphill * genome.uphill_penalty
            close_to_wall = sum(1 for ax, az in DIRECTIONS
                                if belief.get((neighbor[0] + ax,
                                               neighbor[1] + az)) == "#")
            new_cost += close_to_wall * genome.wall_penalty * .24
            new_cost += min(4, visits.get(neighbor, 0)) * genome.revisit_penalty
            if point == position and previous is not None:
                if (dx, dz) != previous:
                    new_cost += genome.turn_penalty
            # Always positive; no negative-cycle shortcuts.
            new_cost = max(.15, new_cost)
            tentative = costs[point] + new_cost
            if tentative >= costs.get(neighbor, math.inf):
                continue
            parent[neighbor] = point
            costs[neighbor] = tentative
            serial += 1
            priority = tentative + heuristic(neighbor)
            priority += rng.random() * genome.tie_jitter
            heapq.heappush(heap, (priority, serial, neighbor))
    # An unknown route can lead to a recently discovered dead end. Choose
    # a visible, unvisited frontier and explore rather than stand forever.
    frontiers = [cell for cell, tile in belief.items()
                 if tile != "#" and cell != position and
                 any((cell[0] + dx, cell[1] + dz) not in belief
                     and 0 <= cell[0] + dx < course.width
                     and 0 <= cell[1] + dz < course.depth
                     for dx, dz in DIRECTIONS)]
    if not frontiers:
        return None
    closest = min(frontiers, key=lambda cell: (
        visits.get(cell, 0) * 5 + abs(cell[0] - course.goal[0])
        + abs(cell[1] - course.goal[1])))
    # Do not teleport to a frontier; plan the next safe ground movement.
    if closest == position:
        return None
    return _plan_known(position, closest, course, belief)


def _plan_known(start: tuple[int, int], goal: tuple[int, int],
                course: Course, belief: dict) -> tuple[int, int] | None:
    queue = deque([start])
    parent = {start: start}
    while queue:
        point = queue.popleft()
        if point == goal:
            while parent[point] != start:
                point = parent[point]
            return point
        for dx, dz in DIRECTIONS:
            candidate = point[0] + dx, point[1] + dz
            if (candidate not in parent and belief.get(candidate, "#") != "#"
                    and 0 <= candidate[0] < course.width
                    and 0 <= candidate[1] < course.depth):
                parent[candidate] = point
                queue.append(candidate)
    return None


def simulate(course: Course, genome: Genome, *, timeout: int,
             seed: int = 0) -> Run:
    if timeout < TICKS_PER_BLOCK:
        raise ValueError("timeout must allow at least one legal movement")
    rng = random.Random(seed)
    bot = course.start
    visited: dict[tuple[int, int], int] = {bot: 1}
    known: dict[tuple[int, int], str] = {}
    open_doors: set[tuple[int, int]] = set()
    previous_direction = None
    ticks = 0
    collisions = 0
    route = [(0, *bot)]
    initial_distance = course.oracle_distance(bot)
    if initial_distance is None:
        raise ValueError(f"Course {course.name!r} has no legal ground route")
    best_remaining = initial_distance
    while ticks < timeout and bot != course.goal:
        _observe(course, bot, known)
        move = _plan(course, bot, genome, known, visited,
                     previous_direction, open_doors, rng)
        if move is None:
            break
        # The world/physics adapter is AUTHORITATIVE. Plans cannot walk
        # through blocks, cross diagonals, ascend >1, fly or scaffold.
        if not course.legal_step(bot, move):
            collisions += 1
            known[move] = course.tile(move)
            ticks += 1
            continue
        elevation = course.height(move) - course.height(bot)
        # Limit full 3D displacement to 0.2 blocks per tick, including
        # drops: a two-block fall cannot skip the speed cap.
        needed = math.ceil(math.hypot(1.0, elevation)
                           * TICKS_PER_SECOND / METERS_PER_SECOND)
        if elevation > 0:
            needed += JUMP_TICKS
        if course.tile(move) == "D" and move not in open_doors:
            needed += DOOR_OPEN_TICKS
        if ticks + needed > timeout:
            ticks = timeout
            break
        if course.tile(move) == "D":
            open_doors.add(move)
        direction = move[0] - bot[0], move[1] - bot[1]
        previous_direction = direction
        ticks += needed
        bot = move
        visited[bot] = visited.get(bot, 0) + 1
        route.append((ticks, *bot))
        remaining = course.oracle_distance(bot)
        if remaining is not None:
            best_remaining = min(best_remaining, remaining)
    finished = bot == course.goal
    progress = 1.0 if finished else (
        (initial_distance - best_remaining) / max(1, initial_distance))
    # A completed course always wins. Partial progress rewards genuinely
    # closer shortest-path distance, not hugging an impassable wall.
    fitness = ((100000.0 + (timeout - ticks) * 4.0 - collisions * 10)
               if finished else
               (3000.0 * progress + min(200, len(visited)) * 1.5
                - collisions * 4 - ticks * .1))
    # Completion must always outrank a failed run, regardless of timeout.
    return Run(course.name, finished, ticks, round(fitness, 3),
               round(progress, 4), len(visited), collisions,
               len(open_doors), tuple(route))


def _evaluate(genome: Genome, courses: list[Course], timeout: int,
              seeds: tuple[int, ...]) -> tuple[float, list[Run]]:
    runs = [simulate(course, genome, timeout=timeout, seed=seed)
            for course in courses for seed in seeds]
    return sum(run.fitness for run in runs) / len(runs), runs


def train(courses: list[Course], *, population: int = 32,
          generations: int = 50, timeout: int = 1200, target: int = 300,
          seed: int = 42, elites: int = 4,
          seeds_per_course: int = 2) -> tuple[Genome, list[dict], list[Run]]:
    if not courses or not 4 <= population <= 256 or not 1 <= generations <= 10000:
        raise ValueError("Need courses, population 4..256, generations 1..10000")
    if not 1 <= elites < population or seeds_per_course < 1:
        raise ValueError("Invalid elites or seeds per course")
    if target < TICKS_PER_BLOCK or timeout < target:
        raise ValueError("Target must fit within the deadline")
    rng = random.Random(seed)
    genomes = [Genome.random(rng) for _ in range(population)]
    history = []
    champion = genomes[0]
    champion_runs: list[Run] = []
    best_score = -math.inf
    for generation in range(generations):
        seeds = tuple(seed + n * 31337 for n in range(seeds_per_course))
        evaluated = []
        for genome in genomes:
            score, runs = _evaluate(genome, courses, timeout, seeds)
            evaluated.append((score, genome, runs))
        evaluated.sort(key=lambda item: item[0], reverse=True)
        score, best_genome, runs = evaluated[0]
        if score > best_score:
            best_score = score
            champion = best_genome
            champion_runs = runs
        successful = sum(run.finished for run in runs)
        best_times = [run.ticks for run in runs if run.finished]
        row = {
            "generation": generation,
            "best_fitness": round(score, 3),
            "median_fitness": round(evaluated[len(evaluated)//2][0], 3),
            "completed_trials": successful,
            "total_trials": len(runs),
            "best_time_ticks": min(best_times) if best_times else "",
            "worst_success_ticks": max(best_times) if best_times else "",
            "champion_fitness": round(best_score, 3),
        }
        history.append(row)
        print(json.dumps(row), flush=True)
        if successful == len(runs) and all(run.ticks <= target for run in runs):
            champion = best_genome
            champion_runs = runs
            break
        pool = [entry[1] for entry in evaluated[:max(elites, population // 3)]]
        next_gen = [entry[1] for entry in evaluated[:elites]]
        while len(next_gen) < population:
            if rng.random() < .10:
                next_gen.append(Genome.random(rng))
            else:
                p1, p2 = rng.choice(pool), rng.choice(pool)
                next_gen.append(p1.crossover(p2, rng).mutate(rng))
        genomes = next_gen
    return champion, history, champion_runs


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("courses", nargs="+", type=Path,
                        help="Course JSON paths; all participate in training")
    parser.add_argument("--population", type=int, default=32)
    parser.add_argument("--generations", type=int, default=50)
    parser.add_argument("--timeout", type=int, default=1200,
                        help="Maximum GAME TICKS per run")
    parser.add_argument("--target", type=int, default=300,
                        help="Required GAME TICKS for all training trials")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--elites", type=int, default=4)
    parser.add_argument("--seeds-per-course", type=int, default=2)
    parser.add_argument("--out", type=Path,
                        default=Path("build/builder-training"))
    args = parser.parse_args()
    courses = [Course.from_path(path) for path in args.courses]
    champion, history, runs = train(
        courses, population=args.population,
        generations=args.generations, timeout=args.timeout,
        target=args.target, seed=args.seed, elites=args.elites,
        seeds_per_course=args.seeds_per_course)
    args.out.mkdir(parents=True, exist_ok=True)
    result = {
        "format": "golems-plus-nav-genome-v1",
        "simulated_only": True,
        "physics": {"speed_m_per_s": METERS_PER_SECOND,
                    "ticks_per_block": TICKS_PER_BLOCK,
                    "breaking": False, "scaffolding": False,
                    "flying": False, "diagonal": False,
                    "collision_width_blocks": BODY_WIDTH_BLOCKS,
                    "collision_height_blocks": BODY_HEIGHT_BLOCKS},
        "course_hashes": {c.name: c.sha256 for c in courses},
        "genome": asdict(champion),
        "runs": [{**asdict(run), "path": list(run.path)} for run in runs],
        "generations_completed": len(history),
        "target_ticks": args.target,
        "target_met": all(r.finished and r.ticks <= args.target
                          for r in runs),
    }
    (args.out/"champion.json").write_text(json.dumps(result, indent=2),
                                         encoding="utf-8")
    with (args.out/"history.csv").open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=list(history[0]))
        writer.writeheader()
        writer.writerows(history)
    print(f"Saved champion, replay paths and CSV to {args.out}")
    print(f"Target met: {result['target_met']}")


if __name__ == "__main__":
    main()
