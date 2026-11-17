"""Schema/invariant tests for the multi-build learning batch."""
import unittest
import json
import tempfile
from pathlib import Path
from batch_learning import (physical_result, diagnostics, sample_per_run,
                            training_majority_baseline, parse_progress,
                            progress_diagnostics, progress_problem_code, BUILDS,
                            historical_matrix_samples, HISTORICAL_MATRIX_COMMIT,
                            collect, aggregate, train_shadow)


class BatchLearningTest(unittest.TestCase):
    def test_all_builds_supported(self):
        self.assertEqual(BUILDS, ("matrix", "dome", "farm"))

    def test_verified_archived_matrix_training_samples(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.assertEqual(historical_matrix_samples(root), ([], []))
            folder = root / "nn-trial-1"
            folder.mkdir()
            row = dict(schema=3, run="previous-matrix-server", worker="a",
                       tick=20, features=[0.] * 30, moved=0,
                       distance=0.0, controller="IDLE")
            (folder / "samples.log").write_text(
                "[NNGOLEMS] " + json.dumps(row) + "\n")
            provenance = dict(commit=HISTORICAL_MATRIX_COMMIT, trial="1",
                              schema=3, samples=1, run="previous-matrix-server",
                              environment="seed=0 crew=004d4154 old-matrix")
            (folder / "provenance.json").write_text(json.dumps(provenance))
            files, origins = historical_matrix_samples(root)
            self.assertEqual(len(files), 1)
            self.assertEqual(origins[0]["crew"], 4)
            self.assertEqual(origins[0]["samples"], 1)
            provenance["commit"] = "bad-commit"
            (folder / "provenance.json").write_text(json.dumps(provenance))
            with self.assertRaises(ValueError):
                historical_matrix_samples(root)

    def test_real_failure_observations_survive_nonzero_gametest_exit(self):
        # A known terminal build outcome with coherent recording is useful
        # even when the GameTest assertion reports failure.
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            log = root / "server.log"
            movement = dict(schema=3, run="matrix-run-1", worker="worker-a",
                            tick=20, features=[0.0] * 30, moved=0,
                            distance=0.0, controller="IDLE")
            team = dict(run="team-run-1", course="matrix", crew=8,
                        start={"correct": 123}, endCorrect=123, placedDelta=0)
            log.write_text(
                "[NN_SPEED_RESULT] course=matrix outcome=ALL_BUILDERS_STALLED "
                "correct=123 total=15376 elapsedTicks=4000\n" +
                "[NNGOLEMS] " + json.dumps(movement) + "\n" +
                "[NNGOLEMS_TEAM] " + json.dumps(team) + "\n")
            case = root / "nn-learning-matrix-1"
            manifest = collect(log, case, "matrix", 1, 8, "fixed-commit",
                               "1", "recovery-baseline", 1)
            self.assertTrue(manifest["valid"])
            self.assertFalse(manifest["game_test_passed"])
            self.assertEqual(manifest["gradle_exit"], 1)
            self.assertEqual(manifest["outcome"], "ALL_BUILDERS_STALLED")
            self.assertTrue((case / "samples.log").exists())
            output = root / "merged"
            summary = aggregate(root, output, "fixed-commit", 9)
            self.assertEqual(summary["valid"], 1)
            self.assertEqual(summary["received"], 1)
            self.assertFalse(summary["complete_dataset"])
            self.assertEqual(summary["builds"]["matrix"]["correct"], [123])
            self.assertEqual(summary["game_test_passed"], 0)
            self.assertTrue((output / "report.md").exists())
            self.assertIn("INCOMPLETE PHYSICAL BATCH",
                          (output / "report.md").read_text())

    def test_shadow_trains_without_farm_but_must_not_claim_validation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            entries = []
            for i in range(3):
                folder = root / str(i)
                folder.mkdir()
                rows = [
                    dict(schema=3, run="matrix-history-" + str(i),
                         worker="worker-a", tick=20 + j * 20,
                         features=[0.0] * 30, moved=j,
                         distance=0.0 if j == 0 else 0.5, controller="IDLE")
                    for j in range(2)]
                (folder / "samples.log").write_text(
                    "".join("[NNGOLEMS] " + json.dumps(row) + "\n" for row in rows))
                entries.append((folder, {"course": "matrix"}))
            output = root / "model"
            output.mkdir()
            result = train_shadow(entries, output)
            self.assertEqual(result["status"], "SHADOW_ONLY_TRAINED_UNEVALUATED")
            self.assertNotIn("held_out", result)
            self.assertTrue((output / "movement-model.json").exists())

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
            physical_result([line], "matrix")

    def test_matrix_eight_builder_source_score(self):
        line = ("[NN_SPEED_RESULT] course=matrix outcome=TIME_LIMIT "
                "correct=12500 total=15376 elapsedTicks=240000")
        result = physical_result([line], "matrix")
        self.assertEqual(result["total"], 15376)
        self.assertEqual(result["correct"], 12500)
        self.assertEqual(result["elapsed_ticks"], 240000)
        self.assertLess(result["correct_fraction"], 1.0)

    def test_reject_ambiguous_or_absent_terminal_result(self):
        line = "[NN_SPEED_RESULT] course=matrix outcome=COMPLETE correct=15376 total=15376"
        for lines in ([], [line, line]):
            with self.assertRaises(ValueError):
                physical_result(lines, "matrix")
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
        roof = diagnostics(["[NN ROOF GATE] worker=x reason=not_grounded"],
                           "farm", 1)[0]
        self.assertEqual(roof["reason_code"], "not_grounded")
        self.assertEqual(roof["reasons"], ["not_grounded"])

    def test_majority_baseline_uses_raw_training_prevalence(self):
        # Balanced fitting would be a tie; actual train labels are mostly 0.
        raw = [dict(moved=0)] * 9 + [dict(moved=1)]
        held = [dict(moved=0)] * 3 + [dict(moved=1)]
        self.assertEqual(training_majority_baseline(raw, held),
                         {"label": 0, "accuracy": 0.75})

    def test_training_cap_balances_classes_and_is_deterministic(self):
        rows = [dict(moved=i % 2, run="a", worker="builder",
                     tick=i) for i in range(50)]
        a = sample_per_run(rows, 20, 17)
        b = sample_per_run(rows, 20, 17)
        self.assertEqual(a, b)
        self.assertEqual(len(a), 20)
        self.assertEqual(sum(r["moved"] for r in a), 10)


    def test_progress_preserves_exact_blocker_with_spaces_and_block_states(self):
        line = ("[NN_CURRICULUM_PROGRESS] course=farm correct=297 total=13143 "
                "layer=4 tick=18000 problem=Waiting for Builders to vacate "
                "placement cell 1080, -51, 1071")
        event = parse_progress(line, "[NN_CURRICULUM_PROGRESS]")
        self.assertEqual(event["problem_code"], "crew_occupancy")
        self.assertIn("1080, -51, 1071", event["problem"])
        self.assertEqual(event["correct"], 297)
        self.assertIsNone(parse_progress("[NN_SPEED_RESULT] correct=30",
                                         "[NN_CURRICULUM_PROGRESS]"))
        self.assertEqual(progress_problem_code("Deferred blocked one-step rise BlockPos{x=1}"),
                         "vertical_access")
        self.assertEqual(progress_problem_code("FRONTIER: Y=14 pending=329 next=[state=a=b]"),
                         "blocked_frontier")

    def test_stall_windows_are_observations_not_success_labels(self):
        observations = [parse_progress(
            "[NN_CURRICULUM_PROGRESS] course=farm correct=" + str(correct)
            + " total=13143 layer=14 tick=" + str(tick) + " problem=" + problem,
            "[NN_CURRICULUM_PROGRESS]") for tick, correct, problem in [
                (400, 290, "Crew recovery: returning"),
                (800, 290, "Crew recovery: returning"),
                (1200, 297, "NN: second unloaded Builder probing distant frontier"),
                (1600, 297, "Deferred block BlockPos{x=1080} until safe stance"),
                (2000, 297, "Waiting for Builders to vacate placement cell 1080")]]
        report = progress_diagnostics(observations)
        self.assertEqual(report["progress_samples"], 5)
        self.assertEqual(report["max_no_progress_ticks"], 800)
        self.assertEqual(report["end_no_progress_ticks"], 800)
        self.assertEqual(report["terminal_problem_code"], "crew_occupancy")
        self.assertEqual(report["problem_windows"]["crew_recovery"], 2)
        empty = progress_diagnostics([])
        self.assertEqual(empty["terminal_problem_code"], "unrecorded")
        self.assertIsNone(empty["end_no_progress_ticks"])


if __name__ == "__main__":
    unittest.main()
