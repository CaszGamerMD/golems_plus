import json
import tempfile
import unittest
from pathlib import Path
from actions import read_actions, MARKER, report
from knowledge import experiment


class KnowledgeTests(unittest.TestCase):
    def test_enrichment_and_grouped_evaluation(self):
        rows=[]
        for run in range(3):
            for i in range(20):
                moved=i%2
                rows.append(dict(schema=1,run=str(run),worker='w',id=i,tick=0,endTick=10,
                    action='FLIGHT',features=[0.]*7,knowledge=[float(moved)]*16+[0.],
                    outcome='ARRIVED' if moved else 'BLOCKED',
                    blockage='NONE' if moved else 'BLOCK',
                    remainingDistance=0 if moved else 1))
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/'actions.log'
            p.write_text(''.join(MARKER+json.dumps(r)+'\n' for r in rows))
            read=read_actions([p])
            self.assertEqual(report(read)['blockage_causes'],{'BLOCK':30})
            result=experiment(read)
            self.assertEqual(len(result['folds']),3)
            self.assertTrue(all(f['environment']['balanced_accuracy']>.95 for f in result['folds']))
            rows[0]['knowledge'][0]=2
            p.write_text(MARKER+json.dumps(rows[0]))
            with self.assertRaises(ValueError):
                read_actions([p])
