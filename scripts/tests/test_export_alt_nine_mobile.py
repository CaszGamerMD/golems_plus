"""Fail CI if the phone viewer loses/forges real Builder Golem recordings."""
import base64
import gzip
import json
import struct
import tempfile
import unittest
from pathlib import Path
from scripts.export_alt_nine_mobile import export, FORMAT, COURSE


class MobilePhysicalReplayViewerTests(unittest.TestCase):
    def test_newest_three_tests_and_reload_feed_preserve_physical_frames(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            traces = root / "live"
            traces.mkdir()
            template = root / "template.html"
            template.write_text("<script>let META=__MOBILE_META__;let PAYLOAD=__MOBILE_PAYLOAD__;</script>")
            for run in (7001, 7002, 7003, 7004):
                for trial in (1, 2, 3):
                    for generation in (0, 1):
                        completed = run * 10 + trial * 2 + generation
                        bots = []
                        for i in range(9):
                            bots.append({
                                "slot": i, "startX": 24, "startZ": i,
                                "arrivedTicks": 220 if i < 5 else -1,
                                "plannedRoute": "W7W", "doorTicks": i == 0,
                                "distanceMeters": 2.0,
                                "localEpisodes": [dict(action="STABLE_MERGE", cleared=True)],
                                "positions": [[0, 24500, 1500, "w"],
                                              [1, 24300, 1600, "p"]],
                            })
                        record = {
                            "format": FORMAT, "course": COURSE,
                            "physicalEntities": True,
                            "completedGeneration": completed,
                            "generation": generation,
                            "trial": f"{run}-{trial}", "finished": 5, "bots": bots
                        }
                        record["experiment"] = dict(arm="challenger", repeat=1,
                                                    testedRule="STABLE_MERGE", rememberedRules=2)
                        (traces / f"trial-{run}-{trial}-generation-{completed}.json").write_text(json.dumps(record))
            output = root / "viewer.html"
            feed = root / "mobile-data.json"
            data = export(traces, template, output, feed, batches=3)
            self.assertEqual(18, len(data["meta"]))
            self.assertEqual("7002-1", data["meta"][0][1])
            self.assertEqual("7004-3", data["meta"][-1][1])
            self.assertNotIn("7001-1", output.read_text())
            self.assertEqual(data, json.loads(feed.read_text()))
            self.assertEqual(5, data["meta"][-1][4])
            self.assertEqual("challenger", data["meta"][-1][7]["arm"])
            self.assertEqual([1, 1, ["STABLE_MERGE"]], data["meta"][-1][6][0][12])
            original = gzip.decompress(base64.b64decode(data["payload"][-1]))
            self.assertEqual(2, struct.unpack_from("<H", original, 0)[0])
            self.assertEqual((2450, 150, ord("w")),
                             struct.unpack_from("<hhB", original, 2))
            self.assertEqual((2430, 160, ord("p")),
                             struct.unpack_from("<hhB", original, 7))

    def test_rejects_unrecorded_or_fake_trajectories(self):
        with tempfile.TemporaryDirectory() as tmp:
            from scripts.export_alt_nine_mobile import encode_trace
            record = {"format": FORMAT, "course": COURSE, "physicalEntities": False, "bots": []}
            with self.assertRaises(ValueError):
                encode_trace(record)
            record["physicalEntities"] = True
            record["bots"] = [{"slot": i, "positions": []} for i in range(9)]
            with self.assertRaises(ValueError):
                encode_trace(record)


if __name__ == "__main__":
    unittest.main()
