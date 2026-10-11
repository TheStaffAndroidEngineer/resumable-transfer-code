"""Independent protocol histories; isolated process, temporary database, ephemeral HTTP."""
import base64
import concurrent.futures
import hashlib
import http.client
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
import uuid
from http.server import ThreadingHTTPServer
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'server'))
from base import Problem, handler
from service import Service, object_handler

class Review(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.database=Path(self.temp.name)/'state.sqlite'
  self.a=Service(self.database);self.b=Service(self.database);self.servers=[]
 def tearDown(self):
  for server,thread in self.servers:server.shutdown();server.server_close();thread.join(2)
  self.temp.cleanup()
 def body(self,action,args=None,identity=None):return {'id':identity or str(uuid.uuid4()),'action':action,'args':args or {}}
 def run_command(self,body,owner=None):return (owner or self.a).route('POST','/commands',body)[1]
 def command(self,action,args=None,owner=None):return self.run_command(self.body(action,args),owner)
 def view(self,owner=None):return (owner or self.a).route('GET','/view',{})[1]['data']
 def serve(self):
  server=ThreadingHTTPServer(('127.0.0.1',0),object_handler(self.a));thread=threading.Thread(target=lambda:server.serve_forever(poll_interval=.01),daemon=True);thread.start();self.servers.append((server,thread));return server.server_port
 def http(self,port,path,body=None):
  c=http.client.HTTPConnection('127.0.0.1',port,timeout=4)
  try:
   c.request('GET' if body is None else 'POST',path,body=None if body is None else json.dumps(body));r=c.getresponse();return r.status,json.loads(r.read())
  finally:c.close()


 def source(self):return bytes(range(256))*40
 def create(self,data=None,owner=None):
  data=data if data is not None else self.source();key=str(uuid.uuid4())
  self.command('create',{'uploadId':key,'length':len(data),'sha256':hashlib.sha256(data).hexdigest()},owner)
  return key,data
 def chunk(self,key,offset,data):return {'uploadId':key,'offset':offset,'data':base64.b64encode(data).decode(),'sha256':hashlib.sha256(data).hexdigest()}
 def probe(self,key,owner=None):return (owner or self.a).route('GET','/uploads/'+key,{})[1]['data']['upload']
 def binary(self,port,key):
  c=http.client.HTTPConnection('127.0.0.1',port,timeout=4)
  try:c.request('GET','/objects/'+key);r=c.getresponse();return r.status,dict(r.getheaders()),r.read()
  finally:c.close()
 def send_all(self,key,data):
  for offset in range(0,len(data),4096):self.command('chunk',self.chunk(key,offset,data[offset:offset+4096]))
 def test_lost_second_reply_probe_and_binary_retrieval_have_exact_source(self):
  key,data=self.create();self.command('chunk',self.chunk(key,0,data[:4096]));port=self.serve()
  second=self.body('chunk',self.chunk(key,4096,data[4096:8192]))
  self.http(port,'/__control',{'drop_reply':True})
  with self.assertRaises(http.client.RemoteDisconnected):self.http(port,'/commands',second)
  self.http(port,'/__control',{});reconstructed=Service(self.database)
  self.assertEqual(8192,self.probe(key,reconstructed)['offset']);self.assertEqual(404,self.binary(port,key)[0])
  self.run_command(second,reconstructed);self.assertEqual(8192,self.probe(key)['offset'])
  self.command('chunk',self.chunk(key,8192,data[8192:]));self.assertEqual(10240,self.probe(key)['offset'])
  self.assertEqual(404,self.binary(port,key)[0]);self.command('complete',{'uploadId':key})
  status,headers,actual=self.binary(port,key);self.assertEqual((200,data),(status,actual))
  self.assertEqual('10240',headers['Content-Length']);self.assertEqual('"'+hashlib.sha256(data).hexdigest()+'"',headers['ETag'])
 def test_probe_is_upload_scoped_after_another_creation(self):
  first,data=self.create();self.command('chunk',self.chunk(first,0,data[:4096]));second,_=self.create(b'other')
  self.assertEqual(second,self.view()['upload']['id']);self.assertEqual((first,4096),(self.probe(first)['id'],self.probe(first)['offset']))
  self.assertEqual(0,self.probe(second)['offset']);self.assertIsNone(self.probe(str(uuid.uuid4())))
 def test_exact_replay_different_command_is_safe_changed_old_bytes_refused(self):
  key,data=self.create();first=self.chunk(key,0,data[:4096]);self.command('chunk',first)
  self.command('chunk',self.chunk(key,4096,data[4096:8192]))
  self.assertEqual(8192,self.command('chunk',first)['outcome']['offset'])
  changed=bytearray(data[:4096]);changed[0]^=1
  self.assertFalse(self.command('chunk',self.chunk(key,0,bytes(changed)))['outcome']['accepted']);self.assertEqual(8192,self.probe(key)['offset'])
 def test_out_of_order_and_overrun_do_not_change_durable_offset(self):
  key,data=self.create(b'abcd');self.assertFalse(self.command('chunk',self.chunk(key,2,b'cd'))['outcome']['accepted'])
  with self.assertRaises(Problem):self.command('chunk',self.chunk(key,0,b'abcde'))
  self.assertEqual(0,self.probe(key)['offset'])
 def test_whole_digest_failure_never_publishes_despite_full_offset(self):
  key,data=self.create(b'abcd');self.command('chunk',self.chunk(key,0,b'zzzz'))
  self.assertEqual(4,self.probe(key)['offset']);self.assertFalse(self.command('complete',{'uploadId':key})['outcome']['accepted'])
  self.assertEqual('OPEN',self.probe(key)['status'])
  with self.assertRaises(Problem):self.a.object(key)
 def test_chunk_hash_size_and_types_are_checked_before_mutation(self):
  key,data=self.create();valid=self.chunk(key,0,data[:4096])
  invalid=[valid|{'sha256':'0'*64},valid|{'offset':True},self.chunk(key,0,b'x'*4097),valid|{'data':'not-base64!'}]
  for args in invalid:
   with self.subTest(args=list(args)):
    with self.assertRaises(Problem):self.command('chunk',args)
  self.assertEqual(0,self.probe(key)['offset'])
 def test_cancel_fences_late_chunks_and_completion_and_historical_receipt(self):
  key,data=self.create();first=self.body('chunk',self.chunk(key,0,data[:4096]));receipt=self.run_command(first)
  self.command('cancel',{'uploadId':key})
  self.assertEqual(receipt,self.run_command(first,Service(self.database)))
  for action,args in [('chunk',self.chunk(key,4096,data[4096:8192])),('complete',{'uploadId':key})]:self.assertFalse(self.command(action,args)['outcome']['accepted'])
  self.assertEqual(('CANCELLED',4096),(self.probe(key)['status'],self.probe(key)['offset']))
  with self.assertRaises(Problem):self.a.object(key)
 def test_complete_racing_cancel_has_one_terminal_winner(self):
  key,data=self.create(b'abcd');self.send_all(key,data)
  with concurrent.futures.ThreadPoolExecutor(2) as pool:
   outcomes=list(pool.map(lambda pair:self.command(pair[1],{'uploadId':key},pair[0])['outcome']['accepted'],[(self.a,'complete'),(self.b,'cancel')]))
  self.assertEqual([False,True],sorted(outcomes));state=self.probe(key)['status'];self.assertIn(state,('COMMITTED','CANCELLED'))
  if state=='COMMITTED':self.assertEqual(data,self.a.object(key)[0])
  else:
   with self.assertRaises(Problem):self.a.object(key)
 def test_lost_completion_recovers_committed_status_without_new_identity(self):
  key,data=self.create(b'complete');self.send_all(key,data);port=self.serve();body=self.body('complete',{'uploadId':key})
  self.http(port,'/__control',{'drop_reply':True})
  with self.assertRaises(http.client.RemoteDisconnected):self.http(port,'/commands',body)
  self.http(port,'/__control',{});restored=Service(self.database)
  self.assertEqual('COMMITTED',self.probe(key,restored)['status']);self.assertTrue(self.run_command(body,restored)['outcome']['accepted'])
  self.assertFalse(self.command('cancel',{'uploadId':key})['outcome']['accepted']);self.assertEqual(data,restored.object(key)[0])
 def test_identity_cannot_bind_different_source_and_concurrent_same_chunk_is_once(self):
  key,data=self.create();args=self.chunk(key,0,data[:4096])
  with concurrent.futures.ThreadPoolExecutor(2) as pool:outcomes=list(pool.map(lambda owner:self.command('chunk',args,owner)['outcome']['accepted'],[self.a,self.b]))
  self.assertEqual([True,True],outcomes);self.assertEqual(4096,self.probe(key)['offset'])
  with self.assertRaises(Problem):self.command('create',{'uploadId':key,'length':len(data),'sha256':'0'*64})
  self.assertEqual(hashlib.sha256(data).hexdigest(),self.probe(key)['sha256'])
 def test_limit_is_explicit_and_retained_cancelled_identity_is_not_reused(self):
  keys=[self.create(b'a')[0] for _ in range(20)]
  for key in keys:self.command('cancel',{'uploadId':key})
  body=self.body('create',{'uploadId':str(uuid.uuid4()),'length':1,'sha256':hashlib.sha256(b'a').hexdigest()})
  refusal=self.run_command(body);self.assertFalse(refusal['outcome']['accepted']);self.assertEqual(refusal,self.run_command(body,self.b))
  self.assertEqual('CANCELLED',self.probe(keys[0])['status'])

if __name__=='__main__':unittest.main()
