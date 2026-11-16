import json
import tempfile
import unittest
from pathlib import Path
from actions import read_actions, report, MARKER


class ActionTests(unittest.TestCase):
    def test_outcomes_and_censoring(self):
        rows = [dict(schema=1, run='r', worker='w', id=i, tick=10,
                     endTick=20, action='FLIGHT', features=[0.]*7,
                     outcome=outcome, remainingDistance=0 if outcome=='ARRIVED' else 1)
                for i, outcome in enumerate(['ARRIVED','BLOCKED','NO_DISPLACEMENT','CANCELLED'])]
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp)/'actions.log'
            p.write_text(''.join(MARKER+json.dumps(r)+'\n' for r in rows+rows))
            result = report(read_actions([p]))['overall']
            self.assertEqual(result['recorded'], 4)
            self.assertEqual(result['completed'], 3)
            self.assertEqual(result['censored'], 1)
            self.assertAlmostEqual(result['arrival_rate'], 1/3)
            rows[0]['remainingDistance'] = 2
            p.write_text(MARKER+json.dumps(rows[0]))
            with self.assertRaises(ValueError):
                read_actions([p])
