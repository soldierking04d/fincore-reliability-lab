import test from 'node:test';
import assert from 'node:assert/strict';
import {
  conditions, estimateValue, examples, nextStep, recordFeedback, runExample,
  startSession, validateDraft,
} from './company-workflow.ts';
import type { Condition, Session, UseCase } from './company-workflow.ts';

// Node 24：node --test ai/company-workflow.test.ts
// 测试固定合成样本与工作流规则，不调用模型或外部服务。
const useCases:UseCase[]=['support','knowledge','requirements'];
const expected:Record<Condition,string>={
  normal:'REVIEW_READY',missing:'NO_SOURCE',outdated:'STALE_SOURCE',invented:'CLAIM_REJECTED',
};
const clone=<T>(value:T):T=>structuredClone(value);
const ready=(id:UseCase='support'):Session=>{
  const s=runExample(id,'normal'); assert.equal(s.stage,'ready'); return s;
};
const near=(actual:number,expectedValue:number):void=>{
  assert.ok(Math.abs(actual-expectedValue)<1e-8,`${actual} != ${expectedValue}`);
};

for(const id of useCases) for(const condition of Object.keys(expected) as Condition[]) {
  void test(`${id}/${condition}: ${expected[condition]}`,()=>{
    const s=runExample(id,condition);
    assert.equal(s.stage,condition==='normal'?'ready':'blocked');
    assert.equal(s.code,expected[condition]);
    assert.equal(s.feedback,null);
    if(condition==='normal') {
      assert.equal(validateDraft(s),null);
      assert.equal(s.candidate.length,3);
      for(const point of s.candidate) {
        const source=s.sources.find(d=>d.id===point.sourceId);
        assert.ok(source?.current);
        assert.ok(source.points.some(p=>p.id===point.pointId&&p.text===point.text));
      }
    } else if(condition==='missing'||condition==='outdated') {
      assert.deepEqual(s.candidate,[],'Unavailable sources must stop generation');
    } else {
      assert.equal(validateDraft(s),'CLAIM_REJECTED');
      assert.equal(recordFeedback(s,'adopted').code,'REVIEW_REQUIRED');
    }
    assert.deepEqual(runExample(id,condition),s,'The same fixture must be reproducible');
  });
}

void test('catalog is exactly three everyday examples/four conditions and retains explicit unknowns',()=>{
  assert.deepEqual(examples.map(e=>e.id).sort(),[...useCases].sort());
  assert.deepEqual(conditions.map(c=>c.id).sort(),Object.keys(expected).sort());
  for(const id of useCases) {
    const example=examples.find(e=>e.id===id)!;
    const before=clone(example.unknowns);
    assert.ok(before.length>0);
    assert.ok(example.benefit.length>0&&example.downside.length>0);
    recordFeedback(ready(id),'adopted');
    assert.deepEqual(example.unknowns,before,'Adoption does not resolve unknown facts');
  }
  assert.match(examples.find(e=>e.id==='support')!.unknowns.join(' '),/不能判断.*不能保证/);
  assert.match(examples.find(e=>e.id==='knowledge')!.unknowns.join(' '),/不能承诺开通时间/);
  assert.match(examples.find(e=>e.id==='requirements')!.unknowns.join(' '),/待产品确认/);
  assert.match(examples.find(e=>e.id==='requirements')!.unknowns.join(' '),/候选不等于已执行测试/);
});

void test('step progression is explicit, non-mutating, and retrieval cannot mutate the shared catalog',()=>{
  let s=startSession();
  for(const stage of ['retrieved','drafted','ready']) {
    const before=clone(s);
    const next=nextStep(s);
    assert.deepEqual(s,before);
    assert.equal(next.stage,stage);
    s=next;
  }
  assert.equal(nextStep(s),s,'Ready is not automatic adoption');
  const retrieved=nextStep(startSession());
  retrieved.sources[0].points[0].text='LOCAL MUTATION';
  assert.notEqual(examples[0].sources[0].points[0].text,'LOCAL MUTATION');
  assert.notEqual(nextStep(startSession()).sources[0].points[0].text,'LOCAL MUTATION');
});

void test('source identity, version, validity and point content are revalidated before adoption',()=>{
  const mutations:[string,(s:Session)=>void][]=[
    ['source id',s=>{s.sources[0].id='FORGED';}],
    ['source title',s=>{s.sources[0].title='FORGED TITLE';}],
    ['version',s=>{s.sources[0].version='unverified-v9';}],
    ['current flag',s=>{s.sources[0].current=false;}],
    ['source removal',s=>{s.sources.pop();}],
    ['source addition',s=>{s.sources.push(clone(s.sources[0]));}],
    ['point removal',s=>{s.sources[0].points.pop();}],
    ['source text and matching candidate',s=>{
      s.sources[0].points[0].text='Guaranteed in 10 seconds';
      s.candidate[0].text='Guaranteed in 10 seconds';
    }],
  ];
  for(const [name,mutate] of mutations) {
    const s=ready(); mutate(s);
    assert.equal(validateDraft(s),'SOURCE_INVALID',name);
    const feedback=recordFeedback(s,'adopted');
    assert.equal(feedback.stage,'blocked',name);
    assert.equal(feedback.code,'REVIEW_REQUIRED',name);
    assert.equal(feedback.feedback,null,name);
  }
});

void test('candidate additions, omissions, duplicates, invented references and altered text are rejected',()=>{
  const mutations:[string,(s:Session)=>void][]=[
    ['extra field',s=>{Object.assign(s.candidate[0],{extra:'unverified assertion'});}],
    ['omitted point',s=>{s.candidate.pop();}],
    ['extra point',s=>{s.candidate.push(clone(s.candidate[0]));}],
    ['duplicate point',s=>{s.candidate[2]=clone(s.candidate[0]);}],
    ['unknown source',s=>{s.candidate[0].sourceId='UNKNOWN';}],
    ['unknown point',s=>{s.candidate[0].pointId='guarantee';}],
    ['wrong source for known point',s=>{s.candidate[0].sourceId=s.sources[1].id;}],
    ['changed text',s=>{s.candidate[0].text='Guaranteed in 10 seconds';}],
    ['comma reference',s=>{s.candidate[0].sourceId='S1,S2';}],
  ];
  for(const [name,mutate] of mutations) {
    const s=ready(); mutate(s);
    assert.equal(validateDraft(s),'CLAIM_REJECTED',name);
    assert.equal(recordFeedback(s,'adopted').feedback,null,name);
  }
  const reordered=ready(); reordered.candidate.reverse();
  assert.equal(validateDraft(reordered),null,'Exact supported points may appear in a different order');
});

void test('feedback cannot skip retrieval, draft validation or a blocked result',()=>{
  const input=startSession();
  const retrieved=nextStep(input);
  const drafted=nextStep(retrieved);
  for(const state of [input,retrieved,drafted,runExample('support','missing'),runExample('support','invented')]) {
    for(const choice of ['adopted','edited'] as const) {
      const result=recordFeedback(state,choice,'Reviewed and corrected');
      assert.equal(result.stage,'blocked');
      assert.equal(result.code,'REVIEW_REQUIRED');
      assert.equal(result.feedback,null);
    }
  }
  const prematureManual=recordFeedback(input,'manual');
  assert.equal(prematureManual.feedback,null);
  const adopted=recordFeedback(ready(),'adopted');
  assert.equal(adopted.stage,'feedback');
  assert.equal(adopted.code,'ADOPTED');
  assert.equal(adopted.feedback,'adopted');
});

void test('edited adoption requires a nonblank reason, records it and bounds its length',()=>{
  for(const blank of ['',' ','\n\t']) {
    const s=ready(); const result=recordFeedback(s,'edited',blank);
    assert.equal(result.stage,'ready');
    assert.equal(result.feedback,null);
    assert.deepEqual(result.trace,s.trace,'An incomplete choice is not recorded as feedback');
  }
  const edited=recordFeedback(ready(),'edited','  Shortened the wording; kept supported facts.  ');
  assert.equal(edited.code,'EDITED');
  assert.equal(edited.feedback,'edited');
  assert.equal(edited.editNote,'Shortened the wording; kept supported facts.');
  assert.equal(recordFeedback(ready(),'edited','x'.repeat(600)).editNote.length,500);
});

void test('manual outcomes include failed runs and repeated feedback cannot duplicate or overwrite them',()=>{
  for(const initial of [ready(),runExample('support','missing'),runExample('knowledge','outdated'),runExample('requirements','invented')]) {
    const result=recordFeedback(initial,'manual');
    assert.equal(result.stage,'feedback');
    assert.equal(result.code,'MANUAL');
    assert.equal(result.feedback,'manual');
    assert.equal(result.trace.length,initial.trace.length+1);
    for(const second of ['manual','adopted','edited'] as const) {
      assert.equal(recordFeedback(result,second,'second choice'),result);
    }
  }
  for(const first of ['adopted','edited'] as const) {
    const result=recordFeedback(ready(),first,'Shortened wording');
    const before=clone(result);
    assert.equal(recordFeedback(result,'manual'),result);
    assert.deepEqual(result,before);
  }
});

void test('positive and negative business-value assumptions reproduce 3062 and -556 without external prices',()=>{
  const positive=estimateValue(70,90);
  assert.equal(positive.used,840);
  near(positive.hours,55.3);
  near(positive.value,5530);
  near(positive.variable,168);
  assert.equal(positive.operations,1300);
  assert.equal(positive.setup,1000);
  assert.equal(positive.net,3062);
  const negative=estimateValue(40,60);
  assert.equal(negative.used,480);
  near(negative.hours,18.4);
  near(negative.value,1840);
  near(negative.variable,96);
  assert.equal(negative.net,-556);
  assert.deepEqual(estimateValue(),positive);
  for(let i=0;i<5;i++) assert.deepEqual(estimateValue(40,60),negative);
});

void test('cost assumptions independently recompute review/rework, maintenance and setup for boundary samples',()=>{
  for(const adoption of [0,40,70,100]) for(const usable of [0,60,90,100]) {
    const result=estimateValue(adoption,usable);
    const used=12*adoption;
    const assistedMinutes=(3.5*usable+9*(100-usable))/100;
    const hours=used*(8-assistedMinutes)/60;
    const variable=used*20/100;
    const operations=500+8*100;
    const setup=12000/12;
    assert.equal(result.used,used);
    near(result.hours,hours);
    near(result.value,hours*100);
    near(result.variable,variable);
    assert.equal(result.net,Math.round(hours*100-variable-operations-setup));
  }
  assert.equal(estimateValue(0,90).net,-2300,'No adoption still incurs maintenance and setup');
  assert.ok(estimateValue(70,0).hours<0,'Failed use may cost more staff time than the original process');
});
