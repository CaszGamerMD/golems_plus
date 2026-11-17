"""Schema/invariant tests for the multi-build learning batch."""
import unittest
from batch_learning import physical_result, diagnostics, sample_per_run, BUILDS


class BatchLearningTest(unittest.TestCase):
    def test_all_builds_supported(self):
        self.assertEqual(BUILDS, ("dragon", "dome", "farm"))

    def test_physical_dome_result(self):
        line = ("[NN DOME RESULT] mode=AUTO8 outcome=NO_WORK_PROGRESS "
                "correct=1970 best=1970 total=3493 ticks=8112 distance=123.4")
        r = physical_result([line], "dome")
        self.assertEqual(r["correct"], 1970)
        self.assertEqual(r["total"], 3493)
        self.assertEqual(r["elapsed_ticks"], 8112)

    def test_physical_farm_result(self):
        line = ("[NN_SPEED_RESULT] course=farm outcome=NO_WORK_PROGRESS "
                "correct=1970 total=13143 elapsedTicks=12000")
        self.assertEqual(physical_result([line], "farm")["correct"], 1970)
        with self.assertRaises(ValueError):
            physical_result([line], "dragon")

    def test_reject_ambiguous_or_absent_terminal_result(self):
        line = "[NN_SPEED_RESULT] course=dragon outcome=COMPLETE correct=20 total=20"
        for lines in ([], [line, line]):
            with self.assertRaises(ValueError):
                physical_result(lines, "dragon")
        with self.assertRaises(ValueError):
            physical_result(["[NN_SPEED_RESULT] course=farm outcome=COMPLETE "
                             "correct=21 total=20"], "farm")

    def test_failure_diagnostics_are_not_task_success_labels(self):
        rows = diagnostics(
            ["[NN FRONTIER SNAPSHOT] reason=missing physical support "
             "trainedReason=search_no_route",
             "[CREW RETREAT START] exhausted"], "farm", 2)
        self.assertEqual(len(rows), 2)
        self.assertTrue(rows[0]["diagnostic_only"])
        self.assertIn("search_no_route", rows[0]["reasons"])
        self.assertEqual(rows[1]["category"], "crew_retreat")

    def test_training_cap_balances_classes_and_is_deterministic(self):
        rows = [dict(moved=i % 2, run="a", worker="builder",
                     tick=i) for i in range(50)]
        a = sample_per_run(rows, 20, 17)
        b = sample_per_run(rows, 20, 17)
        self.assertEqual(a, b)
        self.assertEqual(len(a), 20)
        self.assertEqual(sum(r["moved"] for r in a), 10)


if __name__ == "__main__":
    unittest.main()
