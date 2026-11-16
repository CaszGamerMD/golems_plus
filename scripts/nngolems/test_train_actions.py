import unittest
from train_actions import labeled, evaluate, check_split, scores


class FlightPredictionTests(unittest.TestCase):
    def fixture(self, start):
        return labeled([dict(run=str(run),outcome='ARRIVED' if i%2 else 'BLOCKED',
                             features=[float(i%2)]+[0.]*6)
                        for run in range(start,start+3) for i in range(40)])

    def test_fresh_runs_and_model(self):
        train, held = self.fixture(0), self.fixture(3)
        model, result = evaluate(train,held)
        self.assertEqual(model['target'],'accepted_flight_arrives')
        self.assertEqual(len(model['w'][0]),7)
        self.assertGreater(result['held_out']['neural']['balanced_accuracy'],.95)
        self.assertEqual(result['held_out']['always_arrive']['failure_recall'],0)
        with self.assertRaises(ValueError):
            check_split(train,train)

    def test_censored_excluded_and_failure_metrics(self):
        self.assertEqual(labeled([dict(outcome='CANCELLED')]),[])
        r=scores([dict(moved=0),dict(moved=1)],[.9,.9],.5)
        self.assertEqual(r['missed_failures'],1)
        self.assertEqual(r['balanced_accuracy'],.5)
