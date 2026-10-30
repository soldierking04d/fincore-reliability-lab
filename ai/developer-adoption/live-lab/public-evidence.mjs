import { readFile } from 'node:fs/promises';
import { isDeepStrictEqual } from 'node:util';
import { sha, trustedInputs } from './verifier.mjs';
import { parseUsage } from './model.mjs';

export const publicRunIds = ['738f469e-3df1-477f-8f7f-68b5559ebcbe','48fad3ef-99eb-4057-8a4c-eabc125c774a','90377a5b-f098-402f-8edd-d6a84833c10a'];

/** 从白名单字段重建公开证据。绝不展开原始报告：备注、评审意见、本机路径和环境不进入公开产物。 */
export function projectReceipt(record, trusted) {
  if (!record || !['codex','reference','incomplete'].includes(record.mode)
    || !['passed','failed','accepted'].includes(record.status)) throw Error('记录尚未完成验证');
  if (sha(record.sourceBefore) !== trusted.hashes.before || record.candidate?.sha256 !== sha(record.candidate.source)
    || record.verification?.sourceHash !== record.candidate.sha256) throw Error('源码摘要不一致');
  const cleanVerification = (verification, sourceHash) => {
    if (!verification?.compiled || verification.sourceHash !== sourceHash
      || !isDeepStrictEqual(verification.trustedHashes,trusted.hashes) || verification.results?.length !== 12) throw Error('测试证据或来源不完整');
    const results=verification.results.map((row,index)=>{
      const fixture=trusted.cases[index];
      if (row.id !== fixture.id || !isDeepStrictEqual(row.input,fixture.input) || !isDeepStrictEqual(row.expected,fixture.expected)
        || row.pass !== isDeepStrictEqual(row.actual,fixture.expected) || !Number.isSafeInteger(row.durationMs) || row.durationMs < 0) throw Error('测试逐项结果不一致');
      // 实际结果只允许公开探针合同字段；不能借字段嵌套带入私有数据。
      const keys=row.actual?.kind==='ok'?['kind','page','size','offset','query']:['kind','type','message'];
      if (Object.keys(row.actual||{}).sort().join(',') !== keys.sort().join(',')) throw Error('实际结果含非合同字段');
      const actual=Object.fromEntries(keys.map(k=>[k,row.actual[k]]));
      return {id:fixture.id,name:fixture.name,input:fixture.input,expected:fixture.expected,actual,pass:row.pass,durationMs:row.durationMs};
    });
    const passed=results.filter(x=>x.pass).length;
    if (verification.passed !== passed || verification.total !== 12 || verification.status !== (passed===12?'passed':'failed')) throw Error('汇总与实际结果不一致');
    return {status:verification.status,passed,total:12,compilationMs:verification.compilationMs,sourceHash,results};
  };
  const before=cleanVerification(record.baseline,trusted.hashes.before);
  const after=cleanVerification(record.verification,record.candidate.sha256);
  const stages=record.stages.map(stage=>{
    if (!['baseline','generate','verify'].includes(stage.name) || stage.status!=='completed'
      || !Number.isSafeInteger(stage.durationMs) || stage.durationMs<0) throw Error('阶段计时不完整');
    return {name:stage.name,startedAt:stage.startedAt,durationMs:stage.durationMs};
  });
  const calls=record.attempts.map(attempt=>{
    const u=attempt.usage;
    const usage=u?parseUsage({input_tokens:u.input,cached_input_tokens:u.cachedInput,output_tokens:u.output}):null;
    if (!usage || u.source!=='codex_cli_turn_completed' || attempt.status!=='completed') throw Error('选定调用缺少真实用量');
    return {number:attempt.number,durationMs:attempt.durationMs,usage};
  });
  if ((record.mode==='codex') !== (calls.length>0)) throw Error('执行模式与模型调用不符');
  return {
    id:record.id,mode:record.mode,createdAt:record.createdAt,
    label:record.mode==='codex'?'真实 AI 候选':record.mode==='reference'?'参考实现对照（非 AI）':'漏项候选对照（非 AI）',
    status:after.status,humanAccepted:record.status==='accepted'&&!!record.review,
    requirement:record.requirement,
    beforeSource:record.sourceBefore,candidateSource:record.candidate.source,candidateSha256:record.candidate.sha256,
    before,after,stages,activeMs:stages.reduce((sum,s)=>sum+s.durationMs,0),calls,
    tokenUsage:{input:calls.reduce((s,c)=>s+c.usage.input,0),cachedInput:calls.reduce((s,c)=>s+c.usage.cachedInput,0),output:calls.reduce((s,c)=>s+c.usage.output,0)},
    monetaryCost:{usd:null,reason:'使用订阅登录，未记录有效计费单价；Token 可核实，实际账单金额未知。'},
    scope:'固定工单查询教学案例，12 项公开合同用例；不是模型盲测、生产需求或团队提效测量。',
  };
}

export async function buildPublicEvidence() {
  const trusted=await trustedInputs();
  const runs=[];
  for(const id of publicRunIds){
    const raw=await readFile(new URL(`./.local/runs/${id}/report.json`,import.meta.url),'utf8');
    const record=JSON.parse(raw);if(record.id!==id)throw Error('运行编号不符');
    runs.push(projectReceipt(record,trusted));
  }
  return {schemaVersion:1,exportedAt:new Date().toISOString(),readOnly:true,modelCallsAvailable:false,
    trustedHashes:trusted.hashes,runs,
    disclosure:'公开的是选定运行证据，不是全部调试用量；早期失败调用可能缺少用量。无人工基线，不宣称节省工时。公网不调用模型、不编译或执行候选，不开放本地操作权限。'};
}
