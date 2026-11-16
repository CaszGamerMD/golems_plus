import unittest
from cross_build import TRAIN,HELD,experiment,partition,compact_placements


class CrossBuildTests(unittest.TestCase):
    def test_course_split_and_missing_class(self):
        data={c:[dict(run=c,features=[float(i%2)],moved=i%2)
                 for i in range(20)] for c in TRAIN+HELD}
        data['farm']=[]
        data['chibi']=[dict(run='chibi',features=[1.],moved=1)]
        report,model=experiment(data,['signal'],20)
        self.assertEqual(report['by_course']['farm']['samples'],0)
        self.assertIsNone(report['by_course']['chibi']['neural']['failure_recall'])
        self.assertGreater(report['held_out']['neural']['balanced_accuracy'],.9)
        data['froglights'][0]['run']='home'
        with self.assertRaises(ValueError):
            partition(data)

    def test_repeated_placement_context(self):
        r=dict(run='r',worker='w',desired='a',existing='b',support='c',
               category='ORDINARY',features=[0.],outcome='COMPLETED',moved=1)
        self.assertEqual(len(compact_placements([r,dict(r,id=2)])),1)
        self.assertEqual(len(compact_placements([r,dict(r,outcome='WAIT_CLEARANCE',moved=0)])),2)
