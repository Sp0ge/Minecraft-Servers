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
  self.c.CLIENT.containers.list.return_value=[]
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
 def test_failed_containers_still_consume_arena_slots(self):
  from types import SimpleNamespace
  self.c.CLIENT.containers.list.return_value=[SimpleNamespace(name='mcservers-pillars_'+str(n)) for n in range(1,6)]
  self.assertEqual(self.c.select('pillars','p')['status'],'full')
  self.c.WORKER.submit.assert_not_called()
 def test_warm_arena_resets_only_after_visit(self):
  arena={'players':[],'reservations':{},'visited':False,'ready_at':1}
  self.assertIsNone(self.c.empty_action('pillars_1',arena,1000))
  arena['visited']=True;self.assertEqual(self.c.empty_action('pillars_1',arena,1000),'reset')
 def test_pool_arena_stays_ready_until_last_exit_then_resets(self):
  arena={'players':[],'reservations':{},'visited':False,'ready_at':100}
  self.assertIsNone(self.c.empty_action('pillars_2',arena,120))
  self.assertIsNone(self.c.empty_action('pillars_2',arena,131))
  arena['visited']=True;self.assertEqual(self.c.empty_action('pillars_2',arena,101),'reset')
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
 def test_voice_ports_are_distinct_stable_and_reclaimed(self):
  from types import SimpleNamespace
  servers=[SimpleNamespace(id=str(i),name='mcservers-'+name,labels={'mcservers.managed':'true'}) for i,name in enumerate(('lobby','survival','pvp'))]
  self.c.CLIENT.containers.get.side_effect=lambda key:next(c for c in servers if c.id==key)
  self.c.CLIENT.containers.list.side_effect=lambda **kwargs:list(servers)
  with patch.dict(os.environ,{'VOICE_PORT_MIN':'24460','VOICE_PORT_MAX':'24461'}):
   self.assertEqual(self.c.voice_port('0'),24460)
   self.assertEqual(self.c.voice_port('1'),24461)
   self.assertEqual(self.c.voice_port('0'),24460)
   with self.assertRaises(RuntimeError):self.c.voice_port('2')
   servers.pop(0)
   self.assertEqual(self.c.voice_port('2'),24460)
 def test_voice_allocator_rejects_proxy_and_reserved_public_port(self):
  from types import SimpleNamespace
  self.c.CLIENT.containers.get.return_value=SimpleNamespace(id='id',name='mcservers-proxy',labels={'mcservers.managed':'true'})
  with self.assertRaises(ValueError):self.c.voice_port('id')
  self.c.CLIENT.containers.get.return_value.name='mcservers-lobby'
  with patch.dict(os.environ,{'VOICE_PORT_MIN':'24454','VOICE_PORT_MAX':'24460'}):
   with self.assertRaises(ValueError):self.c.voice_port('id')
 def test_disconnected_http_client_does_not_trigger_second_response(self):
  handler=object.__new__(self.c.Handler)
  handler.send_response=Mock();handler.send_header=Mock();handler.end_headers=Mock()
  handler.wfile=Mock();handler.wfile.write.side_effect=BrokenPipeError()
  handler.reply(200,{'port':24460})
  handler.send_response.assert_called_once_with(200)
  self.assertTrue(handler.close_connection)
 def test_voice_allocation_does_not_wait_for_arena_state_lock(self):
  import threading
  from types import SimpleNamespace
  server=SimpleNamespace(id='id',name='mcservers-lobby',labels={'mcservers.managed':'true'})
  self.c.CLIENT.containers.get.return_value=server
  self.c.CLIENT.containers.list.return_value=[server]
  entered=threading.Event();release=threading.Event()
  def hold():
   with self.c.LOCK:entered.set();release.wait(2)
  thread=threading.Thread(target=hold);thread.start();entered.wait(1)
  try:self.assertEqual(self.c.voice_port('id'),24460)
  finally:release.set();thread.join()
 def test_daily_restart_uses_graceful_stop_and_restores_restart_policy(self):
  import rcon
  container=Mock();policy={'Name':'unless-stopped','MaximumRetryCount':0}
  container.attrs={'HostConfig':{'RestartPolicy':policy}};container.status='exited'
  self.c.CLIENT.containers.get.return_value=container
  self.c.api=Mock(side_effect=[{}, {'count':0,'seed':42}, {'role':'survival'}, {}])
  self.c.maintenance_state={'next_wipe':'2027-01-01','warnings':[]}
  with patch.object(rcon,'command') as stop:self.c.maintain(False)
  stop.assert_called_once_with('survival','stop');container.stop.assert_not_called()
  container.wait.assert_called_once_with(timeout=300);container.start.assert_called_once()
  self.assertEqual(container.update.call_args_list[-1].kwargs,{'restart_policy':policy})
  self.assertNotIn('error',self.c.maintenance_state)
if __name__=='__main__':unittest.main()
