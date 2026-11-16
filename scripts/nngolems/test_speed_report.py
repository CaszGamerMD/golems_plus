import tempfile
import unittest
from pathlib import Path
from speed_report import parse,summarize,MARKER


class SpeedTests(unittest.TestCase):
    def test_complete_and_stall(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/'server.log'
            line='course=home outcome=COMPLETE correct=620 total=620 elapsedTicks=12000 elapsedMs=20000 unsupported=0 crewAlive=4 crewUnclaimed=4'
            p.write_text(MARKER+line)
            r=parse(p)
            self.assertEqual(r['game_seconds'],600)
            self.assertEqual(r['blocks_per_game_minute'],62)
            p.write_text(MARKER+line.replace('COMPLETE','NO_WORK_PROGRESS').replace('correct=620','correct=100'))
            stall=parse(p)
            self.assertIsNone(stall['blocks_per_game_minute'])
            self.assertFalse(summarize([r,stall])['eligible_for_speed_comparison'])
            p.write_text(MARKER+line.replace('correct=620','correct=619'))
            with self.assertRaises(ValueError):
                parse(p)
