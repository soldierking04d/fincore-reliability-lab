import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {runScenario,scenarioDefinitions} from '../public/authority-boundaries/models.mjs';
import {portfolioTopics} from '../lib/portfolio-topics.ts';
import {authoritySequences,authoritySigningStates} from '../lib/authority-technical.ts';

const root=new URL('../public/authority-boundaries/',import.meta.url);
const read=(file:string)=>readFileSync(new URL(file,root),'utf8');
const evidence=JSON.parse(read('evidence.json'));
const casebook=JSON.parse(read('casebook.json'));

test('下载源码逐文件匹配实际验证记录，八种回放可重算',()=>{
  assert.equal(evidence.passed,true);
  assert.equal(evidence.node.tests,11);
  assert.equal(evidence.java.passed,16);
  assert.equal(evidence.java.concurrentAccepted,2);
  assert.equal(evidence.java.concurrentRejected,1);
  assert.equal(evidence.java.walletConnected,false);
  assert.equal(evidence.java.chainTransactionBroadcast,false);
  for(const [file,hash] of Object.entries(evidence.sourceSha256)){
    assert.equal(createHash('sha256').update(readFileSync(new URL(file,root))).digest('hex'),hash,file);
  }
  assert.deepEqual(evidence.scenarios,scenarioDefinitions.map(item=>runScenario(item.id)));
});

test('官方初步披露、研究分析和未知结论不能合并为定论',()=>{
  assert(casebook.facts.some((f:{statement:string})=>f.statement.includes('入金正常')));
  assert(casebook.facts.some((f:{statement:string})=>f.statement.includes('未发现私钥泄露证据')));
  assert(casebook.facts.some((f:{level:string})=>f.level.includes('未独立链上重放')));
  assert(casebook.facts.some((f:{level:string})=>f.level==='尚未证实'));
  const ids=new Set(casebook.sources.map((s:{id:string})=>s.id));
  for(const row of [...casebook.facts,...casebook.timeline])assert(ids.has(row.source));
  assert.match(read('authority-boundaries-20261030.md'),/冻结不等于追回|冻结.*不是.*追回|冻结.*不.*可用/);
});

test('综述入口与细节锚点连通，交互不开放真实钱包或远程执行',()=>{
  assert(portfolioTopics.some(topic=>topic.href==='#authority-boundaries'));
  const component=readFileSync(new URL('../components/security-incidents.tsx',import.meta.url),'utf8');
  const page=readFileSync(new URL('../app/page.tsx',import.meta.url),'utf8');
  assert.match(page,/<SecurityIncidents\s*\/>/);
  for(const id of ['authority-boundaries','authority-facts','authority-architecture','authority-response','authority-lab','authority-handbook'])assert(component.includes(`id="${id}"`),id);
  assert.match(component,/onClick=\{\(\)=>setSelected\(item.id\)\}/);
  assert.doesNotMatch(component,/fetch\(|dangerouslySetInnerHTML|signAndSendTransaction|wallet\.connect|window\.ethereum/);
  assert.match(component,/真实平台已修好|生产安全认证/);
});

test('详细时序消息的参与方和验收范围完整，未决任务不自动释放',()=>{
  assert.deepEqual(authoritySequences.map(s=>s.id),['browser','ops','signing','recovery']);
  for(const sequence of authoritySequences){
    assert.equal(sequence.actors.length,sequence.roles.length);
    assert(sequence.messages.length>=10);
    assert(sequence.checks.every(check=>check.implementation&&check.evidence&&check.failure));
    for(const message of sequence.messages){
      for(const index of [message.from,message.to])assert(Number.isInteger(index)&&index>=0&&index<sequence.actors.length);
      assert(message.label&&message.note);
    }
  }
  for(const state of ['SIGN_RESULT_UNKNOWN','BROADCAST_UNKNOWN']){
    const row=authoritySigningStates.find(s=>s.state===state)!;
    assert(row);
    assert.match(row.release,/不能.*释放|保持预占/);
  }
});

test('展示的真实代码逐段来自已验证文件，手册与下载代码范围一致',()=>{
  const excerpts=JSON.parse(read('source-excerpts.json'));
  const java=read('SigningIntentGuard.java');
  assert.equal(excerpts.sha256,evidence.sourceSha256['SigningIntentGuard.java']);
  assert.equal(excerpts.excerpts.length,3);
  for(const item of excerpts.excerpts)assert(java.includes(item.code),item.title);
  const component=readFileSync(new URL('../components/security-incident-deep-dive.tsx',import.meta.url),'utf8');
  assert.match(component,/SecurityIncidentDeepDive/);
  assert.match(component,/结构或数值编码越界可使整个交易无效/);
  assert.match(component,/不模拟外层交易发送方递增/);
  assert.doesNotMatch(component,/dangerouslySetInnerHTML|fetch\(|signAndSendTransaction/);
  const handbook=read('authority-boundaries-20261030.md');
  assert.match(handbook,/## 14\. 恢复协作/);
  assert.match(handbook,/不长持资金行锁/);
});
