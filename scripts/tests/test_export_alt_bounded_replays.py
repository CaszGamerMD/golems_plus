import json
from pathlib import Path
import tempfile
import unittest
from scripts.export_alt_evolution_replays import export, FORMAT, COURSE
from scripts.merge_alt_evolution_state import merge_history, FORMAT as STATE_FORMAT


class BoundedAltArchive(unittest.TestCase):
    def test_bounds_inline_bundles_and_preserves_every_raw_trace(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            html = root / 'viewer.html'
            html.write_text('<!-- LIVE_GENERATIONS_BEGIN --><!-- LIVE_GENERATIONS_END -->')
            for run in (9, 10, 11, 12):
                for session in (1, 2, 3):
                    row = dict(format=FORMAT, course=COURSE, physicalEntities=True,
                               trial=f'{run}-{session}', completedGeneration=run,
                               bots=[dict(slot=i, positions=[[0, 1000, 2000, 'w']])
                                     for i in range(10)])
                    (root / f'trial-{run}-{session}-generation-{run}.json').write_text(json.dumps(row))
            export(root, html, '', batches=2)
            data = json.loads(html.read_text().split('type="application/json">')[1].split('</script>')[0])
            self.assertEqual({x['trial'].split('-')[0] for x in data['tests']}, {'11', '12'})
            self.assertEqual(len(list(root.glob('trial-*.json'))), 12)

    def test_recovers_unpublished_history_once_even_when_both_inputs_share_directory(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            rows = [dict(format=STATE_FORMAT, course=COURSE, physicalEntities=True,
                         completedGenerations=g) for g in (1, 2)]
            (root / 'history.jsonl').write_text(json.dumps(rows[1]) + '\n')
            (root / 'history-recovered-run.jsonl').write_text(json.dumps(rows[0]) + '\n')
            result = [json.loads(line) for line in merge_history(root / 'history.jsonl', root / 'history.jsonl').splitlines()]
            self.assertEqual([r['completedGenerations'] for r in result], [1, 2])


if __name__ == '__main__':
    unittest.main()
