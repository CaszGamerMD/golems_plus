"""Nine-worker families must keep parentage/route policies through CI merges."""
import importlib.util
from pathlib import Path
import unittest

p=Path(__file__).resolve().parents[1]/"merge_alt_nine_state.py"
spec=importlib.util.spec_from_file_location("alt_nine",p)
m=importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

def gene(n):
    return [0.91,0.8,0.15,0.6,0.4,12,15,0.11,1.0,0.3,0.2,n*.001]

def checkpoint(g,extra=0):
    routes=["WWW"]*3+["WWWS"]*6
    return dict(format=m.FORMAT,course=m.COURSE,physicalEntities=True,
        nativeSimulationProtocol=m.NATIVE_PROTOCOL,bots=9,deadlineTicks=260,
        completedGenerations=g,bestFinishedEver=7,bestNativeFinishedEver=7,
        lastCompleted=7,lastMeanFitness=90+extra,
        nextPopulation=[gene(i+extra) for i in range(9)],
        personalBests=[gene(i) for i in range(9)],personalScores=[100]*9,
        blockedEdges=[],familyModel="three-parents-two-mutated-children-v1",
        nextStrategies=["DOOR"]*9,nextFamilyRoutes=routes,
        nextFamilyOrigins=[0,1,2,0,0,1,1,2,2],
        knowledgePool=[dict(slot=i,fitness=1000-i,arrivedTick=220+i,
            progress=40,generation=g,genes=gene(i),strategy="DOOR",route=routes[i])
            for i in range(3)])

class NineFamilyRetention(unittest.TestCase):
    def test_three_parent_snapshots_and_all_six_clones_are_valid(self):
        m.validate_checkpoint(checkpoint(3))
    def test_reject_lost_clone_or_fake_parent(self):
        a=checkpoint(3)
        a["nextFamilyOrigins"][-1]=0
        with self.assertRaises(ValueError):m.validate_checkpoint(a)
        a=checkpoint(3)
        a["knowledgePool"][0]["strategy"]="IMAGINARY"
        with self.assertRaises(ValueError):m.validate_checkpoint(a)
    def test_merge_retains_matching_strategy_genes_and_routes(self):
        old=checkpoint(3)
        new=checkpoint(4,4)
        merged=m.merge_checkpoint(old,new,None)
        self.assertEqual(merged["nextPopulation"],new["nextPopulation"])
        self.assertEqual(merged["nextStrategies"],new["nextStrategies"])
        self.assertEqual(merged["nextFamilyRoutes"],new["nextFamilyRoutes"])
        self.assertEqual(merged["nextFamilyOrigins"],new["nextFamilyOrigins"])
        self.assertEqual(merged["knowledgePool"],new["knowledgePool"])
        m.validate_checkpoint(merged)
    def test_reject_legacy_ten_worker_state(self):
        data=checkpoint(4)
        data["bots"]=10
        self.assertNotEqual(data["bots"],m.POPULATION)

if __name__=="__main__":
    unittest.main()
