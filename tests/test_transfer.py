import unittest,tempfile,runpy,sys,subprocess,json,tarfile,io,hashlib
from pathlib import Path
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[1]
class TransferTests(unittest.TestCase):
 def test_running_network_blocks_export_before_creating_directory(self):
  with tempfile.TemporaryDirectory() as folder:
   output=Path(folder)/'export'
   with patch.object(sys,'argv',['volume_transfer.py','export','--directory',str(output)]),patch.object(subprocess,'check_output',return_value='running-container'):
    with self.assertRaises(SystemExit):runpy.run_path(str(ROOT/'deploy/volume_transfer.py'),run_name='__main__')
   self.assertFalse(output.exists())
 def test_archive_traversal_rejected_before_import_mutations(self):
  with tempfile.TemporaryDirectory() as folder:
   output=Path(folder);archive=output/'survival.tar.gz'
   with tarfile.open(archive,'w:gz') as t:
    member=tarfile.TarInfo('../outside');member.size=1;t.addfile(member,io.BytesIO(b'x'))
   (output/'volumes.json').write_text(json.dumps({archive.name:hashlib.sha256(archive.read_bytes()).hexdigest()}))
   with patch.object(sys,'argv',['volume_transfer.py','import','--directory',folder]),patch.object(subprocess,'check_output',return_value='') as check,patch.object(subprocess,'run') as mutate:
    with self.assertRaises(SystemExit):runpy.run_path(str(ROOT/'deploy/volume_transfer.py'),run_name='__main__')
    mutate.assert_not_called();self.assertEqual(check.call_count,9)
 def test_checksum_mismatch_rejected_before_import_mutations(self):
  with tempfile.TemporaryDirectory() as folder:
   output=Path(folder);(output/'survival.tar.gz').write_bytes(b'bad')
   (output/'volumes.json').write_text(json.dumps({'survival.tar.gz':'0'*64}))
   with patch.object(sys,'argv',['volume_transfer.py','import','--directory',folder]),patch.object(subprocess,'check_output',return_value=''),patch.object(subprocess,'run') as mutate:
    with self.assertRaises(SystemExit):runpy.run_path(str(ROOT/'deploy/volume_transfer.py'),run_name='__main__')
    mutate.assert_not_called()
