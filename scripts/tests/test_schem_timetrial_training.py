"""Uploaded user's real schem and 18-second all-generation acceptance tests."""
import importlib.util
import sys
import unittest
from pathlib import Path

scripts = Path(__file__).resolve().parents[1]
for stem in ("builder_nav_evolution", "schem_course_import",
             "train_schem_timetrial"):
    path = scripts / (stem + ".py")
    spec = importlib.util.spec_from_file_location(stem, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[stem] = mod
    spec.loader.exec_module(mod)

import builder_nav_evolution as engine
import schem_course_import as importer
import train_schem_timetrial as trial

FIXTURE = scripts.parent / "training/courses/timetrial.schem.base64"


class SchematicTimeTrialTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.doc = importer.convert(importer.load_input(FIXTURE))

    def test_real_user_uploaded_course_is_27_by_12_by_6(self):
        doc = self.doc
        self.assertEqual(doc["schematic_dimensions"], [27, 6, 12])
        self.assertEqual(
            doc["source_sha256"],
            "3c5da8fc97f8f8cf244e8f76f40ed37958d572e363d6c441106de4c7fd32591e")
        self.assertEqual(len(doc["spawn_tiles"]), 20)
        self.assertEqual(len(doc["finish_tiles"]), 20)
        self.assertEqual(len(doc["door_tiles"]), 2)
        self.assertEqual(len(doc["slab_cells_too_short_for_1_6_block_bot"]), 4)
        self.assertEqual(len(doc["walkable_half_slab_steps"]), 3)
        self.assertTrue(all(doc["rows"][z][x] == "S"
                            for x, z in doc["spawn_tiles"]))
        self.assertTrue(all(doc["rows"][z][x] == "G"
                            for x, z in doc["finish_tiles"]))

    def test_every_green_spawn_has_actual_reachable_red_finish(self):
        courses = trial.build_spawn_courses(self.doc)
        self.assertEqual(len(courses), 20)
        distances = [course.oracle_distance(course.start)
                     for course in courses]
        self.assertEqual(min(distances), 50)
        self.assertEqual(max(distances), 59)
        self.assertTrue(all(len(course.goals) == 20 for course in courses))
        self.assertTrue(all(d * engine.TICKS_PER_BLOCK <= 360
                            for d in distances))

    def test_slab_collision_in_user_course(self):
        self.assertEqual(self.doc["rows"][5][10], "^")
        self.assertEqual(self.doc["rows"][6][10], "^")
        self.assertEqual(self.doc["rows"][7][10], "^")
        self.assertEqual(self.doc["rows"][2][19], "#")
        self.assertEqual(self.doc["rows"][5][3], "D")
        self.assertEqual(self.doc["rows"][6][3], "D")

    def test_generation_is_20_bot_all_pass_not_champion_shortcut(self):
        # Only one generation here. A fast bot must NEVER count as a
        # winner unless all 20 green spawns reach the red by 360 ticks.
        result = trial.train_time_trial(self.doc, generations=1,
                                        wall_seconds=20, seed=42,
                                        keep_replays=False,
                                        print_generation=False)
        self.assertEqual(result["bots_per_generation"], 20)
        self.assertEqual(result["generation_duration_ticks"], 360)
        self.assertEqual(len(result["starts_covered"]), 20)
        self.assertEqual(result["generations_run"], 1)
        self.assertEqual(len(result["history"]), 1)
        row = result["history"][0]
        self.assertEqual(row["finished_by_18s"]
                         + row["failed_and_reset"], 20)
        self.assertEqual(result["accepted"],
                         row["finished_by_18s"] == 20)
        self.assertTrue(result["simulated_only"])
        self.assertFalse(result["minecraft_entity_game_test_passed"])
        self.assertEqual(result["collision_box"], [0.65, 0.65, 1.6])
        self.assertEqual(result["speed_cap_m_per_s"], 4.0)
        self.assertEqual(result["forbidden_actions"],
                         ["break", "place", "scaffold", "fly", "teleport"])


if __name__ == "__main__":
    unittest.main()
