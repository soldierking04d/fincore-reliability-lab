import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { isDeepStrictEqual } from 'node:util';
import { projectReceipt } from './public-evidence.mjs';
import { trustedInputs } from './verifier.mjs';
const evidence=JSON.parse(await readFile(new URL('./public-evidence.json',import.meta.url),'utf8'));
const trusted=await trustedInputs();
function receipt(){
  const r=evidence.runs[0];
  return {id:r.id,mode:r.mode,status:r.status,createdAt:r.createdAt,requirement:r.requirement,sourceBefore:r.beforeSource,
    candidate:{source:r.candidateSource,sha256:r.candidateSha256},
    baseline:{...structuredClone(r.before),compiled:true,trustedHashes:trusted.hashes},verification:{...structuredClone(r.after),compiled:true,trustedHashes:trusted.hashes},
    stages:r.stages.map(s=>({...s,status:'completed'})),attempts:r.calls.map(c=>({...c,status:'completed'})),
    notes:'private-note-do-not-export',review:{note:'private-review-do-not-export'},execution:{cli:'/private/tool/path'},
  };
}
test('公开导出采用白名单，不携带备注、路径、私有评审和账号配置',()=>{
  const result=projectReceipt(receipt(),trusted);const text=JSON.stringify(result);
  assert.equal(result.after.passed,12);assert.equal(result.before.passed,4);assert.equal(result.humanAccepted,false);
  assert.doesNotMatch(text,/private-note|private-review|private\/tool|execution|notes/);
  assert.equal(result.tokenUsage.input,13143);assert.equal(result.tokenUsage.output,648);assert.equal(result.monetaryCost.usd,null);
});
test('摘要、单项测试、预期或用量被篡改时拒绝导出',()=>{
  const mutations=[
    r=>r.candidate.source+='tamper',r=>r.baseline.passed=12,
    r=>r.verification.results[0].pass=false,r=>r.verification.results[0].expected.offset='42',
    r=>r.attempts[0].usage=null,r=>r.mode='reference',r=>r.stages[0].durationMs=-1,
  ];
  for(const mutate of mutations){const r=receipt();mutate(r);assert.throws(()=>projectReceipt(r,trusted));}
});
test('静态证据逐项可复核，正负对照不会伪装成 AI 成绩',()=>{
  assert.equal(evidence.readOnly,true);assert.equal(evidence.modelCallsAvailable,false);
  assert.deepEqual(evidence.runs.map(r=>[r.mode,r.before.passed,r.after.passed,r.calls.length]),[['codex',4,12,1],['reference',4,12,0],['incomplete',4,5,0]]);
  for(const r of evidence.runs){
    assert.equal(r.activeMs,r.stages.reduce((sum,s)=>sum+s.durationMs,0));
    for(const phase of [r.before,r.after]){
      assert.equal(phase.results.length,12);
      for(const item of phase.results)assert.equal(item.pass,isDeepStrictEqual(item.expected,item.actual));
    }
  }
});
