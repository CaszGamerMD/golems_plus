import unittest
from collections import defaultdict
from symmetry import rotate,orbit,invariant_score,balanced_examples,NAMES
from train import Network

class SymmetryTests(unittest.TestCase):
    def test_rotates_scene_and_returns_after_four_turns(self):
        x=[float(i)/24 for i in range(24)]
        y=rotate(x)
        self.assertEqual((y[0],y[2]),(-x[2],x[0]))
        for i in (7,13):
            self.assertEqual([y[i+j] for j in (0,1,4,5)],[x[i+j] for j in (5,4,0,1)])
        self.assertEqual(rotate(orbit(x)[-1]),x)
        for i in (1,3,4,5,6,9,10,15,16,19,20,21,22,23):self.assertEqual(y[i],x[i])

    def test_predictions_invariant_without_training(self):
        net=Network(features=NAMES)
        x=[i/24 for i in range(24)]
        self.assertEqual(len({invariant_score(net,v) for v in orbit(x)}),1)

    def test_balancing_and_no_outcome_invention(self):
        rows=[dict(run='a',moved=1,features=[0.]*24) for _ in range(10)]
        rows += [dict(run='b',moved=1,features=[0.]*24),dict(run='a',moved=0,features=[0.]*24)]
        augmented=balanced_examples(rows)
        mass=defaultdict(float)
        for r in augmented:mass[r['run'],r['moved']]+=r['weight']
        self.assertAlmostEqual(mass['a',1],3)
        self.assertAlmostEqual(mass['b',1],3)
        self.assertAlmostEqual(mass['a',0],6)
        self.assertEqual(len(augmented),48)
        with self.assertRaises(ValueError):balanced_examples(rows[:-1])
