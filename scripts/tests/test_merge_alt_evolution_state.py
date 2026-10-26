"""Verify the alternate course never forgets a physically winning team."""
import importlib.util
import unittest
from pathlib import Path
source = Path(__file__).resolve().parents[1] / "merge_alt_evolution_state.py"
spec = importlib.util.spec_from_file_location("merge_alt_evolution_state", source)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

def genome(n):
    return [0.90, 0.8, 0.15, 0.6, 0.4, 12, 15, 0.11, 1.0, 0.3, 0.2, n*.001]

def checkpoint(g, successes=None):
    d = dict(format=m.FORMAT, course=m.COURSE, physicalEntities=True,
             bots=10, deadlineTicks=260, completedGenerations=g,
             bestFinishedEver=10, lastCompleted=1,
             lastMeanFitness=100, nextPopulation=[genome(i) for i in range(10)],
             personalBests=[genome(i) for i in range(10)],
             personalScores=[100]*10, blockedEdges=[])
    if successes:
        d.update(lastSuccessfulPopulation=[genome(i+successes) for i in range(10)],
                 lastSuccessfulGeneration=successes,
                 lastSuccessfulArrivalTicks=[218]*9+[246],
                 lastSuccessfulSlowestTicks=246)
    return d

class AlternateCrewRetention(unittest.TestCase):
    def test_last_perfect_cohort_survives_failing_later_generations(self):
        good=checkpoint(30,successes=30)
        failed=checkpoint(54)
        m.validate_checkpoint(good)
        m.validate_checkpoint(failed)
        result=m.merge_checkpoint(good,failed,None)
        self.assertEqual(result["completedGenerations"],54)
        self.assertEqual(result["lastSuccessfulGeneration"],30)
        self.assertEqual(result["lastSuccessfulPopulation"],
                         good["lastSuccessfulPopulation"])
        self.assertEqual(result["nextPopulation"],failed["nextPopulation"])
    def test_more_recent_slower_complete_team_remains_latest(self):
        older=checkpoint(31,successes=30)
        newer=checkpoint(40,successes=39)
        updated=m.merge_checkpoint(older,newer,None)
        self.assertEqual(updated["lastSuccessfulGeneration"],39)
        self.assertEqual(updated["lastSuccessfulPopulation"],
                         newer["lastSuccessfulPopulation"])
    def test_invalid_or_partial_winning_population_cannot_be_published(self):
        bad=checkpoint(40,successes=39)
        bad["lastSuccessfulArrivalTicks"][0]=261
        with self.assertRaises(ValueError):m.validate_checkpoint(bad)
        partial=checkpoint(42)
        partial["lastSuccessfulGeneration"]=40
        with self.assertRaises(ValueError):m.validate_checkpoint(partial)

if __name__=="__main__":
    unittest.main()
