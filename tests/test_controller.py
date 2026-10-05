import unittest, tempfile, os, importlib.util, sys, datetime, concurrent.futures
from unittest.mock import Mock, patch
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
class ControllerTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory()
  self.env=patch.dict(os.environ,{'SECRETS_DIR':self.temp.name+'/secrets','STATE_DIR':self.temp.name+'/state'});self.env.start()
  with patch('docker.from_env',return_value=Mock()):
   spec=importlib.util.spec_from_file_location('controller_test',ROOT/'controller.py');self.c=importlib.util.module_from_spec(spec);spec.loader.exec_module(self.c)
  self.c.resources=lambda:True;self.c.WORKER.shutdown(wait=False);self.c.WORKER=Mock();self.c.MAINTENANCE_WORKER.shutdown(wait=False);self.c.MAINTENANCE_WORKER=Mock()
 def tearDown(self):self.env.stop();self.temp.cleanup()
 def test_concurrent_reservations_never_exceed_sixteen(self):
  self.c.ARENAS['pillars_1']['state']='WAITING'
  with concurrent.futures.ThreadPoolExecutor(max_workers=30) as pool:
   replies=list(pool.map(lambda n:self.c.select('pillars_1',str(n)),range(100)))
  self.assertEqual(sum(r['status']=='ready' for r in replies),16)
  self.assertEqual(len(self.c.ARENAS['pillars_1']['reservations']),16)
 def test_duplicate_request_does_not_take_two_seats(self):
  a=self.c.ARENAS['pillars_1'];a['state']='WAITING'
  for _ in range(30):self.c.select('pillars_1','same-player')
  self.assertEqual(len(a['reservations']),1)
 def test_match_and_reset_do_not_accept_players(self):
  for state in ('IN_GAME','RESETTING'):
   self.c.ARENAS['pillars_1']['state']=state
   self.assertNotEqual(self.c.select('pillars','p')['status'],'ready')
   if state=='RESETTING':self.assertNotEqual(self.c.select('pillars_1','p')['status'],'ready')
 def test_explicit_match_join_allowed_as_spectator(self):
  self.c.ARENAS['pillars_1']['state']='IN_GAME'
  self.assertEqual(self.c.select('pillars_1','spectator')['status'],'ready')
 def test_only_one_spawn_for_simultaneous_requests(self):
  for _ in range(20):self.c.select('pillars','p')
  self.c.WORKER.submit.assert_called_once()
 def test_cpu_memory_shortage_refuses_launch(self):
  self.c.resources=lambda:False
  self.assertEqual(self.c.select('pillars','p')['status'],'failed')
  self.c.WORKER.submit.assert_not_called()
 def test_warm_arena_resets_only_after_visit(self):
  arena={'players':[],'reservations':{},'visited':False,'ready_at':1}
  self.assertIsNone(self.c.empty_action('pillars_1',arena,1000))
  arena['visited']=True;self.assertEqual(self.c.empty_action('pillars_1',arena,1000),'reset')
 def test_demand_arena_retires_after_last_exit_or_unused_grace(self):
  arena={'players':[],'reservations':{},'visited':False,'ready_at':100}
  self.assertIsNone(self.c.empty_action('pillars_2',arena,120))
  self.assertEqual(self.c.empty_action('pillars_2',arena,131),'retire')
  arena['visited']=True;self.assertEqual(self.c.empty_action('pillars_2',arena,101),'retire')
 def test_inflight_reservation_prevents_empty_reset(self):
  arena={'players':[],'reservations':{'player':200},'visited':True,'ready_at':1}
  for name in ('pillars_1','pillars_2'):self.assertIsNone(self.c.empty_action(name,arena,100))
 def test_calendar_months_and_warning_times(self):
  self.assertEqual(self.c.add_months(datetime.date(2027,11,30),3),datetime.date(2028,2,29))
  self.c.maintenance_state={'next_wipe':'2027-01-05','warnings':[],'last_restart':None};self.c.api=Mock(return_value={})
  for hour,minute in [(4,40),(4,50),(4,55)]:
   now=datetime.datetime(2026,10,6,hour,minute,tzinfo=self.c.TZ)
   self.c.schedule(now);self.c.schedule(now)
  self.assertEqual(self.c.api.call_count,3)
  now=datetime.datetime(2026,10,6,5,0,tzinfo=self.c.TZ);self.c.schedule(now);self.c.schedule(now)
  self.c.MAINTENANCE_WORKER.submit.assert_called_once_with(self.c.maintain,False)
if __name__=='__main__':unittest.main()
