import json
import tempfile
import unittest
from pathlib import Path
from train import Network, read_rows, split_runs, metrics


class TrainingTests(unittest.TestCase):
    @staticmethod
    def fixture():
        # Synthetic data checks the learning code, NOT Minecraft performance.
        rows = []
        for run in range(3):
            for i in range(80):
                moved = i % 2
                rows.append(dict(schema=2, run=str(run), worker='worker', tick=i*20,
                                 controller='WALK',
                                 features=[.2*moved, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1] + [0.]*10,
                                 moved=moved, distance=float(moved)))
        return rows

    def test_learns_and_exports_same_predictions(self):
        train, held = split_runs(self.fixture())
        net = Network()
        net.fit(train)
        self.assertGreater(metrics(net, held)['accuracy'], .95)
        self.assertLess(metrics(net, held)['log_loss'], .25)
        saved = json.loads(json.dumps(net.export()))
        restored = Network()
        for key in ('w', 'b', 'v', 'c'):
            setattr(restored, key, saved[key])
        self.assertEqual(net.forward(held[0]['features']), restored.forward(held[0]['features']))

    def test_run_split_is_disjoint_and_reproducible(self):
        a, b = split_runs(self.fixture())
        self.assertFalse({r['run'] for r in a} & {r['run'] for r in b})
        self.assertEqual((a, b), split_runs(self.fixture()))
        with self.assertRaises(ValueError):
            split_runs([r for r in a if r['run'] == a[0]['run']])

    def test_parser_deduplicates_and_rejects_bad_labels(self):
        row = self.fixture()[0]
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)/'server.log'
            line = '[Server thread/INFO] [NNGOLEMS] '+json.dumps(row)+'\n'
            path.write_text('unrelated line\n'+line+line)
            self.assertEqual(read_rows([path]), [row])
            row['moved'] = 1
            path.write_text('[NNGOLEMS] '+json.dumps(row))
            with self.assertRaises(ValueError):
                read_rows([path])

    def test_schema_three_roundtrip_and_mixed_schema_rejected(self):
        rows = [dict(r, schema=3, features=r['features'] + [0.]*9)
                for r in self.fixture()]
        train, held = split_runs(rows)
        net = Network(schema=3)
        net.fit(train)
        self.assertGreater(metrics(net, held)['accuracy'], .95)
        self.assertEqual(len(net.export()['features']), 30)
        self.assertEqual(net.export()['schema'], 3)
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp)/'rows.log'
            p.write_text('[NNGOLEMS] '+json.dumps(rows[0])+'\n')
            self.assertEqual(read_rows([p]), [rows[0]])
            with p.open('a') as out:
                out.write('[NNGOLEMS] '+json.dumps(self.fixture()[1])+'\n')
            with self.assertRaises(ValueError):
                read_rows([p])

    def test_single_class_evaluation_rejected(self):
        with self.assertRaises(ValueError):
            split_runs([r for r in self.fixture() if r['moved']])


if __name__ == '__main__':
    unittest.main()
