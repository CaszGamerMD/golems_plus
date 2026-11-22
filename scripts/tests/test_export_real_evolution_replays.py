import json
from pathlib import Path
import tempfile
import unittest
from scripts.export_real_evolution_replays import export, FORMAT, COURSE


class ExportPhysicalRoutesTests(unittest.TestCase):
    def test_all_three_physical_generations_and_actual_positions(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            html=root/"viewer.html"
            html.write_text("pre\n<!-- LIVE_GENERATIONS_BEGIN -->old<!-- LIVE_GENERATIONS_END -->\npost")
            for trial in (1, 2, 3):
                for generation in range(2):
                    payload={
                        "format":FORMAT,"course":COURSE,"physicalEntities":True,
                        "trial":f"demo-{trial}","generation":generation,
                        "completedGeneration":generation+1,
                        "finished":10 if generation==1 else 4,
                        "bots":[{"slot":i,"positions":[[0,24000,1000,"w"],[1,23900,1100,"w"]]}
                                for i in range(10)]}
                    (root/f"trial-demo-{trial}-generation-{generation}.json").write_text(json.dumps(payload))
            export(root,html,"demo-")
            source=html.read_text()
            data=json.loads(source.split('type="application/json">')[1].split('</script>')[0])
            self.assertEqual(3,len(data["tests"]))
            for test in data["tests"]:
                self.assertEqual([4,10],[g["finished"] for g in test["generations"]])
                self.assertEqual(10,len(test["generations"][0]["bots"]))
            self.assertIn('"positions"',source)

    def test_reject_missing_physical_entities(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);p=root/"viewer.html"
            p.write_text("<!-- LIVE_GENERATIONS_BEGIN --><!-- LIVE_GENERATIONS_END -->")
            (root/"trial-demo-1-generation-1.json").write_text(json.dumps({
                "format":FORMAT,"course":COURSE,"physicalEntities":False,
                "trial":"demo-1","bots":[]}))
            with self.assertRaises(ValueError):export(root,p,"demo-")
