import json
import tempfile
import unittest
from pathlib import Path
from placements import read_placements, report, MARKER


class PlacementTests(unittest.TestCase):
    def test_distinct_outcomes(self):
        rows=[dict(schema=1,run='r',worker='w',id=i,tick=i,desired='rail',
                   existing='air',support='stone',category='RAIL',features=[0.]*19,
                   outcome=o,actual='rail' if i==0 else 'air',stateMatches=i==0)
              for i,o in enumerate(['COMPLETED','WAIT_RAIL_JIG','WORLD_REJECTED'])]
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/'p.log'
            p.write_text(''.join(MARKER+json.dumps(r)+'\n' for r in rows+rows))
            result=report(read_placements([p]))
            self.assertEqual(result['overall']['observations'],3)
            self.assertEqual(result['by_category']['RAIL']['outcomes']['WAIT_RAIL_JIG'],1)
            rows[0]['stateMatches']=False
            p.write_text(MARKER+json.dumps(rows[0]))
            with self.assertRaises(ValueError):
                read_placements([p])
