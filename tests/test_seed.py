import unittest,tempfile,json,importlib.util
from unittest.mock import patch
from pathlib import Path
class SeedTests(unittest.TestCase):
 def test_restart_preserves_seed_and_wipe_changes_it_even_on_collision(self):
  path=Path('/survival-source/season.py')
  if not path.exists():self.skipTest('Mount survival source at /survival-source')
  spec=importlib.util.spec_from_file_location('season',path);s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp)
   with patch.object(s.secrets,'randbits',return_value=42):self.assertEqual(s.prepare_season(root),'42')
   (root/'world').mkdir();(root/'world/level.dat').write_bytes(b'test')
   with patch.object(s.secrets,'randbits',return_value=99):self.assertEqual(s.prepare_season(root),'42')
   (root/'world/level.dat').unlink()
   with patch.object(s.secrets,'randbits',side_effect=[42,99]):self.assertEqual(s.prepare_season(root),'99')
if __name__=='__main__':unittest.main()
