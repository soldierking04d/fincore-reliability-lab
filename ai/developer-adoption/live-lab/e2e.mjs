import assert from 'node:assert/strict';
const base='http://127.0.0.1:4400';
const config=await fetch(base+'/api/config').then(r=>r.json());
const headers={'Content-Type':'application/json','X-Lab-Token':config.token};
async function post(path,body){const r=await fetch(base+path,{method:'POST',headers,body:JSON.stringify(body)});const d=await r.json();assert.ok(r.ok,JSON.stringify(d));return d;}
async function wait(id){for(let i=0;i<120;i++){const d=await fetch(base+'/api/runs/'+id).then(r=>r.json());if(!d.busy)return d;await new Promise(r=>setTimeout(r,250));}throw Error('E2E_TIMEOUT');}
const summaries=[];
for(const mode of ['reference','incomplete']){
  let run=await post('/api/runs',{mode});run=await wait(run.id);assert.equal(run.status,'ready');assert.equal(run.baseline.passed,4);
  await post(`/api/runs/${run.id}/generate`,{version:run.version});run=await wait(run.id);assert.equal(run.status,'review');
  const denied=await fetch(base+`/api/runs/${run.id}/verify`,{method:'POST',headers,body:JSON.stringify({version:run.version,approved:false})});assert.equal(denied.status,409);
  await post(`/api/runs/${run.id}/verify`,{version:run.version,approved:true});run=await wait(run.id);
  assert.equal(run.status,mode==='reference'?'passed':'failed');assert.equal(run.verification.passed,mode==='reference'?12:5);assert.equal(run.usage.calls,0);
  summaries.push({id:run.id,mode,status:run.status,before:run.baseline.passed,after:run.verification.passed,activeMs:run.activeMs,modelCalls:run.usage.calls});
}
console.log(JSON.stringify({status:'passed',summaries}));
