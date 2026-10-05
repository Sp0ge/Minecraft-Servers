import unittest,tempfile,subprocess,os,tarfile,hashlib
from pathlib import Path
class BackupTests(unittest.TestCase):
 def test_offline_reset_keeps_verified_backup_and_plugins(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp)/'survival';backup=Path(tmp)/'backups';world=root/'world';world.mkdir(parents=True)
   (world/'level.dat').write_bytes(b'world-before-wipe');(world/'playerdata').mkdir();(world/'playerdata/u.dat').write_bytes(b'inventory-before-wipe')
   (root/'plugins').mkdir();(root/'plugins/keep.txt').write_text('keep')
   env=dict(os.environ,SURVIVAL_PATH=str(root),BACKUPS_PATH=str(backup))
   p=subprocess.run(['python',str(Path(__file__).resolve().parents[1]/'maintenance.py')],env=env,capture_output=True,text=True)
   self.assertEqual(p.returncode,0,p.stderr);self.assertFalse(world.exists());self.assertTrue((root/'plugins/keep.txt').exists())
   archive=next(backup.glob('*.tar.gz'))
   with tarfile.open(archive) as tar:self.assertEqual(tar.extractfile('world/playerdata/u.dat').read(),b'inventory-before-wipe')
   self.assertIn(hashlib.sha256(archive.read_bytes()).hexdigest(),archive.with_suffix('.sha256').read_text())
 def test_no_world_refuses_wipe(self):
  with tempfile.TemporaryDirectory() as tmp:
   env=dict(os.environ,SURVIVAL_PATH=tmp,BACKUPS_PATH=tmp+'/backups')
   p=subprocess.run(['python',str(Path(__file__).resolve().parents[1]/'maintenance.py')],env=env,capture_output=True,text=True)
   self.assertNotEqual(p.returncode,0)
if __name__=='__main__':unittest.main()
