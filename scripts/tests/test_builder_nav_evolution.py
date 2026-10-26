"""Headless trainer tests. Not a claim about live Minecraft GameTests."""
import importlib.util
import random
import sys
import unittest
from pathlib import Path

source = Path(__file__).resolve().parents[1] / "builder_nav_evolution.py"
spec = importlib.util.spec_from_file_location("builder_nav_evolution", source)
trainer = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = trainer
spec.loader.exec_module(trainer)

ROOM = ["#########", "#S..D..G#", "#########"]


def course(rows=ROOM, **extra):
    return trainer.Course.from_dict({"name": "test-room", "rows": rows, **extra})


def profile(default, special):
    result = [[default] * 9 for _ in range(3)]
    for (x, z), value in special.items():
        result[z][x] = value
    return result


class NavigationTrainerTests(unittest.TestCase):
    def test_physical_speed_four_meters_per_second(self):
        self.assertEqual(trainer.METERS_PER_SECOND, 4.0)
        self.assertEqual(trainer.TICKS_PER_SECOND, 20)
        self.assertEqual(trainer.TICKS_PER_BLOCK, 5)
        self.assertEqual(trainer.BODY_WIDTH_BLOCKS, .65)
        self.assertEqual(trainer.BODY_HEIGHT_BLOCKS, 1.6)

    def test_size_gates_are_actual_collision_rules(self):
        legal = course(widths=profile(1, {(4, 1): .65}),
                       clearances=profile(3, {(4, 1): 1.6}))
        self.assertTrue(legal.legal_step((3, 1), (4, 1)))
        too_narrow = course(widths=profile(1, {(4, 1): .649}))
        self.assertFalse(too_narrow.legal_step((3, 1), (4, 1)))
        too_low = course(clearances=profile(3, {(4, 1): 1.599}))
        self.assertFalse(too_low.legal_step((3, 1), (4, 1)))

    def test_cardinal_only_never_breaks_or_flies(self):
        c = course()
        self.assertFalse(c.legal_step((2, 1), (4, 1)))
        self.assertFalse(c.legal_step((1, 1), (2, 2)))
        self.assertFalse(c.legal_step((1, 1), (1, 0)))
        self.assertFalse(c.legal_step((1, 1), (-1, 1)))

    def test_closed_door_can_open_with_real_tick_penalty(self):
        c = course()
        g = trainer.Genome.random(random.Random(8))
        result = trainer.simulate(c, g, timeout=200, seed=8)
        self.assertTrue(result.finished)
        self.assertEqual(result.doors_opened, 1)
        self.assertGreaterEqual(result.ticks, 6 * trainer.TICKS_PER_BLOCK
                                + trainer.DOOR_OPEN_TICKS)
        self.assertEqual(result.path[-1][1:], c.goal)
        for (_, x1, z1), (_, x2, z2) in zip(result.path, result.path[1:]):
            self.assertTrue(c.legal_step((x1, z1), (x2, z2)))

    def test_raised_cell_requires_jump_and_headroom(self):
        rows = ["#########", "#S..1..G#", "#########"]
        c = course(rows)
        self.assertTrue(c.legal_step((3, 1), (4, 1)))
        less_headroom = course(rows, clearances=profile(3, {(3, 1): 1.9}))
        self.assertFalse(less_headroom.legal_step((3, 1), (4, 1)))
        self.assertTrue(c.legal_step((4, 1), (5, 1)))

    def test_invalid_course_and_unreachable_finish(self):
        with self.assertRaises(ValueError):
            course(["S.G", "##"])
        blocked = course(widths=profile(1, {(4, 1): .5}))
        with self.assertRaisesRegex(ValueError, "no legal ground route"):
            trainer.simulate(blocked,
                             trainer.Genome.random(random.Random(1)),
                             timeout=200)

    def test_mutation_preserves_gene_bounds(self):
        genome = trainer.Genome.random(random.Random(2))
        rng = random.Random(1)
        for _ in range(100):
            genome = genome.mutate(rng)
            for gene, (lo, hi) in trainer.GENES.items():
                self.assertLessEqual(lo, getattr(genome, gene))
                self.assertLessEqual(getattr(genome, gene), hi)

    def test_training_is_seed_reproducible(self):
        c = course()
        first = trainer.train([c], population=8, generations=3,
                              timeout=100, target=55, seed=4,
                              elites=2, seeds_per_course=2)
        second = trainer.train([c], population=8, generations=3,
                               timeout=100, target=55, seed=4,
                               elites=2, seeds_per_course=2)
        self.assertEqual(first, second)
        self.assertGreaterEqual(len(first[1]), 1)
        self.assertTrue(all(run.finished for run in first[2]))


if __name__ == "__main__":
    unittest.main()
