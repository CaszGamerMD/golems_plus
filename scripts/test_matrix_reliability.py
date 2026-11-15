"""Regression checks for observed Matrix progress comparison."""
import unittest
from matrix_reliability import progress, compare_progress

def timeline(*points):
    return progress("\n".join(
        f"[MATRIX FULL PROGRESS] tick={tick} matchingSourceBlocks={count}/15376 "
        f"best={count} cursor={count}/15376 currentLayer=3 noWorkTicks=0 crewAlive=4"
        for tick, count in points))

class TestProgress(unittest.TestCase):
    def test_sustained(self):
        a=timeline((100,100),(200,300),(300,450))
        b=timeline((100,100),(200,150),(300,200))
        p=compare_progress({"1":a,"2":b,"3":a})
        self.assertEqual(p["firstObservedDifference"]["tick"],200)
        self.assertEqual(p["sustainedLagByTrial"]["2"]["confirmedTick"],300)
        self.assertEqual(p["sustainedLagByTrial"]["2"]["gap"],250)
        self.assertEqual(set(p["sustainedLagByTrial"]),{"2"})
    def test_transient(self):
        a=timeline((100,100),(200,300),(300,450))
        b=timeline((100,100),(200,150),(300,450))
        self.assertFalse(compare_progress({"1":a,"2":b,"3":a})["sustainedLagByTrial"])
    def test_missing(self):
        a=timeline((100,100))
        self.assertEqual(compare_progress({"1":a,"2":{},"3":a})["commonCheckpoints"],0)
    def test_unequal_ticks(self):
        a=timeline((100,100),(200,300))
        b=timeline((100,100),(201,150))
        p=compare_progress({"1":a,"2":b,"3":a})
        self.assertEqual(p["commonCheckpoints"],1)
        self.assertIsNone(p["firstObservedDifference"])
    def test_identical(self):
        a=timeline((100,100),(200,300))
        p=compare_progress({"1":a,"2":a,"3":a})
        self.assertIsNone(p["firstObservedDifference"])
        self.assertFalse(p["sustainedLagByTrial"])

if __name__=="__main__":
    unittest.main()
