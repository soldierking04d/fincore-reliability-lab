import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createWorkbenchServer } from '../src/server.mjs';

const wallet = 'jw9Ur36hkXnXWPBycpdKF17ys6NXJdGQdBStnCDhUnC';
async function fixture(t, options = {}) {
  const server = createWorkbenchServer({preview: async input => ({...input,status:'SIMULATION_PASSED',executionAllowed:false}), cooldownMs:0,...options});
  await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const origin = `http://127.0.0.1:${server.address().port}`;
  const config = await fetch(origin+'/api/config');
  const cookie = config.headers.get('set-cookie').split(';')[0];
  const body = await config.json();
  const headers = {'Content-Type':'application/json',Origin:origin,Cookie:cookie,'X-CSRF-Token':body.csrfToken};
  return {server,origin,headers,config,body,post:(data={wallet,side:'BUY',amountAtomic:'100000'}, override={}) => fetch(origin+'/api/preview',{method:'POST',headers:{...headers,...override},body:JSON.stringify(data)})};
}
test('配置隔离、Cookie和响应安全头',async t=>{
  const f=await fixture(t); assert.equal(f.body.executionAllowed,false); assert.equal(f.body.walletEnabled,true);
  assert.match(f.config.headers.get('set-cookie'),/HttpOnly; SameSite=Strict/);
  assert.match(f.config.headers.get('content-security-policy'),/frame-ancestors 'none'/);
  assert.equal(f.config.headers.get('cache-control'),'no-store');
  assert.equal((await f.post()).status,200);
});
test('未建立会话、跨站、错误CSRF均拒绝',async t=>{
  const f=await fixture(t);
  for(const headers of [{Cookie:''},{Origin:'https://evil.example'},{'X-CSRF-Token':'bad'},{'Sec-Fetch-Site':'cross-site'}]) assert.equal((await f.post(undefined,headers)).status,403);
});
test('错误地址、精度、额外字段和超限均不进入预检',async t=>{
  let calls=0; const f=await fixture(t,{preview:async()=>{calls++;return {};}});
  for(const data of [{wallet:'0x123',side:'BUY',amountAtomic:'1'},{wallet,side:'BUY',amountAtomic:'100001'},{wallet,side:'BUY',amountAtomic:100},{wallet,side:'SELL',amountAtomic:'1.1'},{wallet,side:'SELL',amountAtomic:'0'},{wallet,side:'BUY',amountAtomic:'1',rpc:'https://evil.example'}]) assert.equal((await f.post(data)).status,400);
  assert.equal(calls,0);
});
test('写入接口不存在；固定静态映射不暴露源码或上级文件',async t=>{
  const f=await fixture(t);
  for(const path of ['/api/send','/api/sign','/src/server.mjs','/.env','/../package.json','/api/rpc']) assert.equal((await fetch(f.origin+path)).status,404);
});
test('全局只容纳一个预检；并发请求立即429不排队',async t=>{
  let finish,started;const ready=new Promise(resolve=>started=resolve);
  const f=await fixture(t,{preview:async()=>{started(); await new Promise(resolve=>finish=resolve); return {status:'BLOCKED',executionAllowed:false};}});
  const first=f.post(); await ready;
  assert.equal((await f.post()).status,429); finish();assert.equal((await first).status,200);
});
test('过大正文、错误内容类型、重复JSON字段拒绝',async t=>{
  const f=await fixture(t);
  assert.equal((await f.post(undefined,{'Content-Type':'text/plain'})).status,415);
  assert.equal((await fetch(f.origin+'/api/preview',{method:'POST',headers:f.headers,body:' '.repeat(4097)})).status,413);
  assert.equal((await fetch(f.origin+'/api/preview',{method:'POST',headers:f.headers,body:`{"wallet":"${wallet}","side":"BUY","side":"SELL","amountAtomic":"1"}`})).status,400);
});
test('不向浏览器泄露内部错误，也不把executionAllowed放开',async t=>{
  const f=await fixture(t,{preview:async()=>{throw new Error('private-host-secret')}});
  const res=await f.post(); assert.equal(res.status,502);assert.doesNotMatch(await res.text(),/private-host-secret/);
});
test('错误Host和已过期Cookie不能触发预检',async t=>{
  let now=10000;const f=await fixture(t,{now:()=>now,sessionTtlMs:1000});
  // fetch会规范化Host，使用原生HTTP真正发出错误Host，而不是测到被客户端改回的正常请求。
  const status=await new Promise((resolve,reject)=>{
    const req=http.request(f.origin+'/api/config',{headers:{Host:'evil.example'}},res=>{res.resume();resolve(res.statusCode);});
    req.on('error',reject);req.end();
  });
  assert.equal(status,403);
  now+=1001;assert.equal((await f.post()).status,403);
});
test('返回结果强制执行关闭，客户端提供RPC参数也不能改变端点',async t=>{
  const f=await fixture(t,{preview:async()=>({status:'SIMULATION_PASSED',executionAllowed:true})});
  assert.equal((await (await f.post()).json()).executionAllowed,false);
});
