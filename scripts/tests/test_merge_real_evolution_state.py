"""Persistence/merge regression tests (not simulated navigation trials)."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

source = Path(__file__).resolve().parents[1] / "merge_real_evolution_state.py"
spec = importlib.util.spec_from_file_location("merge_real_evolution_state", source)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def genome(number):
    return [0.95, 0.9, 0.15, 0.8, 0.4, 12, 15, 0.1, 0.5,
            0.3, 0.2, float(number) * 0.001]


def header():
    return {"format": m.FORMAT, "course": m.COURSE,
            "physicalEntities": True, "bots": 10, "deadlineTicks": 360}


def champion(slowest, mean):
    times = [slowest] * 10
    # Keep the arithmetic exactly consistent with scoring.
    times[0] = int(round(mean * 10 - slowest * 9))
    assert min(times) >= 0 and max(times) == slowest
    return {**header(), "slowestTicks": slowest, "meanTicks": sum(times)/10,
            "winningGenomes": [genome(i) for i in range(10)],
            "physicalArrivalRecords": [
                {"slot": i, "ticks": t, "plannedRoute": "N"}
                for i, t in enumerate(times)]}


def checkpoint(generation, finished=7, score=100):
    return {**header(), "completedGenerations": generation,
            "bestFinishedEver": finished, "lastCompleted": finished,
            "lastMeanFitness": score, "nextPopulation":
            [genome(i) for i in range(10)],
            "personalBests": [genome(i) for i in range(10)],
            "personalScores": [score] * 10,
            "blockedEdges": [{"x": 1, "z": 2, "toX": 3, "toZ": 4,
                              "failures": generation}]}


class MergeTests(unittest.TestCase):
    def test_faster_earlier_champion_survives_later_bad_run(self):
        old = champion(298, 275)
        incoming = champion(342, 288)
        m.validate_champion(old)
        m.validate_champion(incoming)
        self.assertIs(min((old, incoming), key=m.champion_key), old)

    def test_rejects_late_or_simulated_winner(self):
        with self.assertRaises(ValueError):
            m.validate_champion(champion(361, 330))
        fake = champion(298, 275)
        fake["physicalEntities"] = False
        with tempfile.TemporaryDirectory() as td:
            path = Path(td)/"champion.json"
            path.write_text(json.dumps(fake))
            with self.assertRaises(ValueError):
                m.read(path)

    def test_merge_keeps_best_lane_and_new_breeding_population(self):
        old = checkpoint(6, finished=10, score=120)
        new = checkpoint(7, finished=8, score=80)
        new["personalScores"][4] = 150
        newer = m.merge_checkpoint(old, new, champion(298, 275))
        self.assertEqual(newer["completedGenerations"], 7)
        self.assertEqual(newer["bestFinishedEver"], 10)
        self.assertEqual(newer["personalScores"][4], 150)
        self.assertEqual(newer["personalScores"][0], 120)
        self.assertEqual(newer["blockedEdges"][0]["failures"], 7)

    def test_merges_two_histories_without_erasing_generation(self):
        with tempfile.TemporaryDirectory() as td:
            base = Path(td)
            a = base / "old.jsonl"
            b = base / "new.jsonl"
            a.write_text(json.dumps(checkpoint(1)) + "\n" +
                         json.dumps(checkpoint(2)) + "\n")
            b.write_text(json.dumps(checkpoint(2)) + "\n" +
                         json.dumps(checkpoint(3)) + "\n")
            lines = m.merge_history(a, b).splitlines()
            self.assertEqual(len(lines), 3)
            self.assertEqual([json.loads(x)["completedGenerations"]
                              for x in lines], [1, 2, 3])

    def test_restart_from_failed_run_keeps_mutated_offspring(self):
        failed = checkpoint(24, finished=4, score=80)
        m.validate_checkpoint(failed)
        self.assertEqual(len(failed["nextPopulation"]), 10)
        self.assertEqual(failed["completedGenerations"], 24)


if __name__ == "__main__":
    unittest.main()
