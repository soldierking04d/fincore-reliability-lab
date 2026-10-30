import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import http from 'node:http';
import { Lab, validateRates } from './engine.mjs';
import { parseUsage, estimateCost, callCodex } from './model.mjs';
import { processRun } from './process.mjs';
import { sha } from './verifier.mjs';
import { makeServer, port } from './server.mjs';

test('Token 来自完整事件，缓存不重复统计，缺失不虚构零',()=>{
  assert.deepEqual(parseUsage({input_tokens:100,cached_input_tokens:40,output_tokens:20}),{input:100,cachedInput:40,output:20,source:'codex_cli_turn_completed'});
  assert.equal(parseUsage({input_tokens:10,cached_input_tokens:11,output_tokens:2}),null);
  assert.equal(parseUsage({input_tokens:10,output_tokens:2}),null);
  assert.equal(parseUsage({input_tokens:-1,cached_input_tokens:0,output_tokens:2}),null);
  const a=[{usage:parseUsage({input_tokens:100,cached_input_tokens:40,output_tokens:20})}];
  assert.equal(estimateCost(a,{input:10,cachedInput:2,output:20}).usd,0.00108);
  assert.equal(estimateCost(a,null).usd,null);
  assert.equal(estimateCost([...a,{usage:null}],{input:1,cachedInput:1,output:1}).usd,null);
  assert.equal(estimateCost([],null).usd,null);
});
test('价格校验拒绝负数、Infinity、部分字段和字符串',()=>{
  for(const rates of [{input:-1,cachedInput:0,output:1},{input:1,cachedInput:0,output:Infinity},{input:1},{input:'1',cachedInput:0,output:1}])assert.throws(()=>validateRates(rates));
  assert.equal(validateRates(null),null);
  assert.deepEqual(validateRates({input:0,cachedInput:0,output:0}),{input:0,cachedInput:0,output:0});
});
test('模型协议消费 turn.completed，保持执行工具禁用',async()=>{
  let usage;
  const runner=async(command,args,options)=>{
    assert.ok(args.includes('--ignore-user-config'));assert.ok(args.includes('shell_tool'));assert.ok(args.includes('multi_agent'));
    options.onLine(JSON.stringify({type:'item.completed',item:{type:'agent_message',text:JSON.stringify({source:'public final class TicketQuery {}',summary:'返回候选',risks:[]})}}));
    options.onLine(JSON.stringify({type:'turn.completed',usage:{input_tokens:20,cached_input_tokens:5,output_tokens:10}}));
    return {code:0};
  };
  const answer=await callCodex({runner,onUsage:u=>usage=u,onProgress:()=>{}});
  assert.equal(usage.input,20);assert.equal(usage.cachedInput,5);assert.match(answer.source,/TicketQuery/);
  await assert.rejects(callCodex({runner:async()=>({code:1}),onUsage:()=>{},onProgress:()=>{}}),/MODEL_CALL_FAILED/);
});
test('执行器保留真实退出状态，并限制超时与输出',async()=>{
  const r=await processRun(process.execPath,['-e','console.log("actual");process.exit(3)']);
  assert.equal(r.code,3);assert.equal(r.stdout.trim(),'actual');assert.ok(r.durationMs>=0);
  await assert.rejects(processRun(process.execPath,['-e','setInterval(()=>{},1000)'],{timeout:50}),/PROCESS_TIMEOUT/);
  await assert.rejects(processRun(process.execPath,['-e','console.log("x".repeat(10000))'],{limit:100}),/OUTPUT_LIMIT/);
  const c=new AbortController();c.abort();
  await assert.rejects(processRun(process.execPath,['-e','setInterval(()=>{},1000)'],{signal:c.signal}),/CANCELLED/);
});

async function fakeLab(options={}){
  const root=await mkdtemp(join(tmpdir(),'fincore-ai-lab-test-'));
  let checks=0;
  const lab=new Lab({root,verify:async(source)=>{checks++;const old=source.includes('int offset = page * size');return {compiled:true,sourceHash:sha(source),passed:old?4:12,total:12,status:old?'failed':'passed',results:[]};},...options});
  await lab.init();return {lab,root,get checks(){return checks;}};
}
async function settle(lab,id){for(let n=0;n<100;n++){if(!lab.view(id).busy){await lab.saves.get(id);return lab.view(id);}await new Promise(r=>setTimeout(r,5));}throw Error('TEST_TIMEOUT');}

test('参考闭环：基线红→候选→显式批准→测试绿→人工验收→重启恢复',async()=>{
  const {lab,root}=await fakeLab();const created=await lab.create({mode:'reference'});let run=await settle(lab,created.id);
  assert.equal(run.status,'ready');assert.equal(run.baseline.passed,4);
  await assert.rejects(lab.action(run.id,run.version-1,'generate'),/VERSION_CONFLICT/);
  await assert.rejects(lab.action(run.id,run.version,'accept',{note:'提前验收'}),/REVIEW_REQUIRED/);
  await lab.action(run.id,run.version,'generate');run=await settle(lab,run.id);
  assert.equal(run.status,'review');assert.equal(run.attempts.length,0);assert.equal(run.candidate.origin,'repository_fixture');
  await assert.rejects(lab.action(run.id,run.version,'verify',{approved:false}),/EXECUTION_APPROVAL_REQUIRED/);
  await lab.action(run.id,run.version,'verify',{approved:true});run=await settle(lab,run.id);
  assert.equal(run.status,'passed');
  await assert.rejects(lab.action(run.id,run.version,'accept',{note:''}),/REVIEW_REQUIRED/);
  await lab.action(run.id,run.version,'accept',{note:'已检查边界，接受此教学改动。'});
  assert.equal(lab.view(run.id).status,'accepted');
  const restored=new Lab({root});await restored.init();assert.equal(restored.view(run.id).status,'accepted');
  assert.equal(JSON.parse(await readFile(join(root,run.id,'report.json'),'utf8')).review.note,'已检查边界，接受此教学改动。');
});

test('模型用量即时记录，失败不自动重试或切换参考，未知成本保持未知',async()=>{
  let calls=0;
  const {lab}=await fakeLab({generate:async({onUsage})=>{calls++;onUsage(parseUsage({input_tokens:200,cached_input_tokens:0,output_tokens:10}));throw Error('MODEL_JSON_INVALID');}});
  let r=await lab.create({mode:'codex'});r=await settle(lab,r.id);await lab.action(r.id,r.version,'generate');r=await settle(lab,r.id);
  assert.equal(r.status,'error');assert.equal(calls,1);assert.equal(r.candidate,null);assert.equal(r.usage.input,200);assert.equal(r.costs.usd,null);
});

test('串行执行、取消、模型两次调用上限',async()=>{
  const {lab}=await fakeLab({generate:async({onUsage})=>{onUsage(parseUsage({input_tokens:10,cached_input_tokens:0,output_tokens:5}));return{source:'public final class TicketQuery {}',summary:'测试返回',risks:[]};},
    verify:async source=>{await new Promise(r=>setTimeout(r,20));return {compiled:true,sourceHash:sha(source),passed:4,total:12,status:'failed',results:[]};}});
  let r=await lab.create({mode:'codex'});
  await assert.rejects(lab.create({mode:'reference'}),/LAB_BUSY/);r=await settle(lab,r.id);
  for(let n=0;n<2;n++){await lab.action(r.id,r.version,'generate');r=await settle(lab,r.id);await lab.action(r.id,r.version,'verify',{approved:true});r=await settle(lab,r.id);assert.equal(r.status,'failed');}
  await assert.rejects(lab.action(r.id,r.version,'generate'),/MODEL_CALL_LIMIT/);assert.equal(r.usage.calls,2);
});

test('HTTP 仅同源可读、写令牌、方法和私有路径保护',async()=>{
  const {lab}=await fakeLab();const server=makeServer(lab);await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const request=(path,{headers={},method='GET',body}={})=>new Promise((resolve,reject)=>{
    const req=http.request({host:'127.0.0.1',port:server.address().port,path,method,headers:{Host:`127.0.0.1:${port}`,...headers}},res=>{let data='';res.on('data',b=>data+=b);res.on('end',()=>resolve({status:res.statusCode,body:data,headers:res.headers}));});req.on('error',reject);req.end(body);
  });
  try{
    assert.equal((await request('/api/config',{headers:{Host:'evil.example'}})).status,403);
    assert.equal((await request('/api/config',{headers:{Origin:'https://evil.example'}})).status,403);
    assert.equal((await request('/api/config',{headers:{'Sec-Fetch-Site':'cross-site'}})).status,403);
    assert.equal((await request('/.local/runs')).status,404);assert.equal((await request('/server.mjs')).status,404);
    assert.equal((await request('/api/runs',{method:'POST',body:'{}'})).status,403);
    const conf=await request('/api/config');const {token}=JSON.parse(conf.body);assert.equal(conf.headers['cache-control'],'no-store');
    assert.equal((await request('/api/runs',{method:'DELETE'})).status,405);
    const result=await request('/api/runs',{method:'POST',headers:{'Content-Type':'application/json','X-Lab-Token':token},body:JSON.stringify({mode:'reference'})});
    assert.equal(result.status,202);await settle(lab,JSON.parse(result.body).id);
    assert.equal((await request('/api/runs',{method:'POST',headers:{'Content-Type':'application/json','X-Lab-Token':token},body:'{bad'})).status,400);
    assert.equal((await request('/')).status,200);
  }finally{await new Promise(r=>server.close(r));}
});
