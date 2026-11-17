"""Controlled Matrix collector regression tests (synthetic rows are NOT scored)."""
import unittest
from collect import resolve_terminal_results


def event(outcome, tick=38970, elapsed=299943, correct=4787):
    return (f"outcome={outcome} layer=8 elapsedMs={elapsed} tick={tick} "
            f"correct={correct} total=15376 best=4787 unsupported=2")


class ControlledCollectorTests(unittest.TestCase):
    def test_one_terminal_result(self):
        value = event("TIME_LIMIT")
        self.assertEqual(resolve_terminal_results([value]), (value, []))

    def test_stall_then_callback_timeout_preserves_stall(self):
        stall = event("ALL_BUILDERS_STALLED")
        late = event("TIME_LIMIT", tick=38971, elapsed=300658)
        self.assertEqual(resolve_terminal_results([stall, late]), (stall, [late]))

    def test_conflicting_or_far_apart_results_rejected(self):
        original = event("ALL_BUILDERS_STALLED")
        for later in (event("TIME_LIMIT", tick=38971, elapsed=300658, correct=4788),
                      event("TIME_LIMIT", tick=38990, elapsed=310000),
                      event("COMPLETE", tick=38971, elapsed=300658)):
            with self.assertRaises(ValueError):
                resolve_terminal_results([original, later])

    def test_missing_or_three_results_rejected(self):
        with self.assertRaises(ValueError):
            resolve_terminal_results([])
        with self.assertRaises(ValueError):
            resolve_terminal_results([event("TIME_LIMIT")] * 3)


if __name__ == "__main__":
    unittest.main()
