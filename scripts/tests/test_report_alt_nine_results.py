"""Results report must use real measured generations and separate old champions."""
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from scripts.report_alt_nine_results import read_trials, report


def row(gen, finished, quick, *, stalls=0, ascents=0, doors=0, crossings=0, collisions=0, door_stalls=0, door_align=0):
    return (f"[Server thread/INFO] (Minecraft) [STDOUT]: "
            f"[TIMETRIAL REAL GENETICS] gen={gen} realBuilderGolems=9 "
            f"finish13s={finished}/9 finish11_2s={quick}/9 "
            f"doorPlans={doors} doorCrossings={crossings} collisions={collisions} "
            f"jumps=0 slabAscents={ascents} slabEntryStalls={stalls} "
            f"doorEntryAlignTicks={door_align} doorEntryStalls={door_stalls} "
            "fastestTicks=218 slowestTicks=259 stalled= #2 step=9/20")


class PhysicalResultsReporterTests(unittest.TestCase):
    def test_reads_all_three_trials_and_flags_slab_door_regression(self):
        with TemporaryDirectory() as d:
            root = Path(d)
            (root / "real-builder-trial-1.log").write_text(
                row(0, 6, 2, ascents=2, stalls=5, doors=1, collisions=30, door_stalls=3, door_align=4)+"\n"+
                row(1, 8, 4, ascents=3, stalls=8, doors=1, collisions=22)+"\n")
            (root / "real-builder-trial-2.log").write_text(
                row(0, 3, 0, ascents=0, stalls=10, doors=2, collisions=20)+"\n")
            (root / "real-builder-trial-3.log").write_text(
                row(0, 4, 1, ascents=2, stalls=2, doors=1, collisions=1)+"\n")
            trials = read_trials(root)
            before = {"completedGenerations": 1800, "strategyObservations": 90,
                      "personalScores": [5] * 9,
                      "knowledgePool": [{"slot": 1, "fitness": 9}]}
            after = {"completedGenerations": 1804, "strategyMemory": {
                     "observations": [None] * 94},
                     "personalScores": [5, 5, 5, 6, 5, 5, 5, 5, 5],
                     "knowledgePool": [{"slot": 3, "fitness": 11}]}
            md, summary = report(trials, before, after, {"slowestTicks": 236},
                                 "123", "https://github.com/example/actions/123", "evolve")
            self.assertEqual((summary["max13s"], summary["max11_2s"]), (8, 4))
            self.assertEqual((summary["completedGenerationDelta"],
                              summary["strategyEvidenceDelta"]), (4, 4))
            self.assertEqual(summary["improvedPersonalSlots"], [3])
            self.assertEqual(summary["totals"]["slabEntryStalls"], 25)
            self.assertEqual(summary["totals"]["slabAscents"], 7)
            self.assertEqual(summary["totals"]["doorPlans"], 5)
            self.assertEqual(summary["totals"]["doorEntryStalls"], 3)
            self.assertEqual(summary["totals"]["doorEntryAlignTicks"], 4)
            self.assertTrue(summary["hasDoorTelemetry"])
            self.assertIn("Door-entry hesitation observed", md)
            self.assertTrue(summary["poolChanged"])
            self.assertIn("Slab entry remains a bottleneck", md)
            self.assertIn("Door exploration does not translate", md)
            self.assertIn("Final generation regressed", md) if summary["physicalGenerations"] >= 6 else None
            self.assertIn("236 ticks", md)
            self.assertIn("historical, NOT evidence", md)
            self.assertIn("golem 3", md)

    def test_detects_absent_physical_evidence_without_claiming_success(self):
        md, result = report([], {"completedGenerations": 9, "strategyObservations": 4},
                            {"completedGenerations": 9, "strategyMemory": {
                             "observations": [None] * 4}}, {"slowestTicks": 236},
                            "late", "", "evolve")
        self.assertIsNone(result["max13s"])
        self.assertIn("No scored generations found", md)
        self.assertIn("Training retention warning", md)
        self.assertIn("Experience retention warning", md)
        self.assertNotIn("Best 13s (260 ticks): 9/9", md)

    def test_rejects_fake_or_inconsistent_physical_crew_count(self):
        with TemporaryDirectory() as d:
            path = Path(d) / "real-builder-trial-1.log"
            path.write_text(row(0, 4, 5) + "\n")
            with self.assertRaisesRegex(ValueError, "Inconsistent"):
                read_trials(Path(d))
            path.write_text(row(0, 4, 2).replace("realBuilderGolems=9",
                                                 "realBuilderGolems=8")+"\n")
            with self.assertRaisesRegex(ValueError, "Invalid physical-crew"):
                read_trials(Path(d))


if __name__ == "__main__":
    unittest.main()
