#!/usr/bin/env python3
import unittest
from dome_autotune import DEFAULT, choose_candidate, parse_result, quality

class DomeAutotuneTests(unittest.TestCase):
    def test_requires_explicit_instrumented_world_measurement(self):
        sample = "[DOME PROGRESS] tick=800 realBlocks=250/3537 activeY=0"
        self.assertFalse(parse_result(sample, DEFAULT)["verified"])
        self.assertEqual(parse_result(sample, DEFAULT)["real_blocks"], 0)

    def test_uses_real_progress_and_reports_path_failure(self):
        marker = ("[DOME TUNING] scan=32 feasible=5 sky=12.0 "
                  "grapple=24.0 target=0.65")
        sample = (marker + "\n[DOME PROGRESS] tick=800 realBlocks=250/3537 activeY=0\n"
                  "[DOME PROGRESS] tick=1200 realBlocks=300/3537 activeY=1\n"
                  "Dome stalled after 3600 ticks trainedReason=search_no_route")
        result = parse_result(sample, DEFAULT)
        self.assertTrue(result["verified"])
        self.assertEqual(result["real_blocks"], 300)
        self.assertEqual(result["active_y"], 1)
        self.assertEqual(result["diagnosis"], "trained_path_search")

    def test_prefers_verified_progress_not_unproven_completion(self):
        old = dict(complete=False, real_blocks=823, active_y=2, peak_tick=5000)
        better = dict(complete=False, real_blocks=850, active_y=2, peak_tick=6000)
        self.assertGreater(quality(better), quality(old))

    def test_candidate_is_distinct_and_avoids_retesting(self):
        first = choose_candidate(DEFAULT,
                {"diagnosis": "trained_path_search"}, [])
        self.assertEqual(first["GOLEMS_DOME_SCAN_LIMIT"], "48")
        second = choose_candidate(DEFAULT,
                {"diagnosis": "trained_path_search"},
                [{"verified": True, "settings": first}])
        self.assertNotEqual(first, second)

    def test_continues_after_single_setting_alternatives_are_exhausted(self):
        from dome_autotune import CHOICES, signature
        singles = []
        for key, values in CHOICES.items():
            for value in values:
                candidate = dict(DEFAULT)
                candidate[key] = value
                singles.append(signature(candidate))
        result = choose_candidate(DEFAULT,
                   {"diagnosis": "trained_path_search"}, [], singles)
        self.assertIsNotNone(result)
        self.assertEqual(sum(result[k] != DEFAULT[k] for k in DEFAULT), 2)

    def test_historic_signatures_survive_diagnostic_pruning(self):
        from dome_autotune import signature
        first = choose_candidate(DEFAULT, None, [])
        second = choose_candidate(DEFAULT, None, [], [signature(first)])
        self.assertIsNotNone(second)
        self.assertNotEqual(signature(first), signature(second))

if __name__ == "__main__":
    unittest.main()
