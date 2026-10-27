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
    def test_individual_faster_or_farther_progress_survives_merge(self):
        older=checkpoint(45)
        newer=checkpoint(48)
        older["personalScores"]=[100]*10
        newer["personalScores"]=[100]*10
        older["personalScores"][2]=900
        newer["personalScores"][4]=1200
        oldmember=dict(slot=2,fitness=900,arrivedTick=-1,progress=30,
                       generation=45,genes=genome(2))
        newmember=dict(slot=4,fitness=1200,arrivedTick=230,progress=45,
                       generation=48,genes=genome(4))
        older["knowledgePool"]=[oldmember]
        newer["knowledgePool"]=[newmember]
        m.validate_checkpoint(older)
        m.validate_checkpoint(newer)
        merged=m.merge_checkpoint(older,newer,None)
        self.assertEqual([k["slot"] for k in merged["knowledgePool"]],[4,2,0])
        self.assertEqual(merged["knowledgePool"][0]["arrivedTick"],230)
        self.assertEqual(merged["personalScores"][2],900)
        self.assertEqual(merged["personalScores"][4],1200)
        self.assertEqual(len(merged["nextPopulation"]),10)

    def test_pool_does_not_discard_faster_old_golem_on_losing_trial(self):
        older=checkpoint(52)
        newer=checkpoint(54)
        older["personalScores"][3]=120000
        older["knowledgePool"]=[
            dict(slot=3,fitness=120000,arrivedTick=220,progress=45,
                 generation=52,genes=genome(3))]
        newer["personalScores"][3]=98000
        newer["knowledgePool"]=[
            dict(slot=3,fitness=98000,arrivedTick=250,progress=40,
                 generation=54,genes=genome(9))]
        merged=m.merge_checkpoint(older,newer,None)
        best=merged["knowledgePool"][0]
        self.assertEqual((best["slot"],best["fitness"]), (3,120000))
        self.assertEqual(best["genes"],genome(3))

    def test_reject_fake_pooled_success_and_duplicate_slot(self):
        checkpoint_data=checkpoint(40)
        record=dict(slot=0,fitness=100000,arrivedTick=270,
                    progress=20,generation=40,genes=genome(0))
        checkpoint_data["knowledgePool"]=[record]
        with self.assertRaises(ValueError):
            m.validate_checkpoint(checkpoint_data)
        record["arrivedTick"]=240
        checkpoint_data["knowledgePool"]=[record,record]
        with self.assertRaises(ValueError):
            m.validate_checkpoint(checkpoint_data)

    def test_invalid_or_partial_winning_population_cannot_be_published(self):
        bad=checkpoint(40,successes=39)
        bad["lastSuccessfulArrivalTicks"][0]=261
        with self.assertRaises(ValueError):m.validate_checkpoint(bad)
        partial=checkpoint(42)
        partial["lastSuccessfulGeneration"]=40
        with self.assertRaises(ValueError):m.validate_checkpoint(partial)

if __name__=="__main__":
    unittest.main()
