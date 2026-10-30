import { mkdir, readFile, readdir, writeFile, rename } from 'node:fs/promises';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { trustedInputs, verifySource, sha, workshop } from './verifier.mjs';
import { callCodex, estimateCost, responseSchema } from './model.mjs';

const busyStates = new Set(['baseline','generating','testing']);
export const requirement = '实现内部工单查询参数规范化：page 从 0 开始，拒绝负数；size 必须在 1..200，越界拒绝而非截断；offset 先提升到 long 再相乘；null 查询变空串；仅去掉首尾 Character.isWhitespace 或 Character.isSpaceChar 的 Unicode 空白，内部字符保留；零宽字符不擅自删除。错误信息分别为“page 必须 >= 0”和“size 必须在 1..200 之间”。方法签名及 PageRequest 字段不变。';

export function validateRates(rates) {
  if (rates === null || rates === undefined) return null;
  if (typeof rates !== 'object' || Object.keys(rates).sort().join(',') !== 'cachedInput,input,output'
    || !Object.values(rates).every(x=>typeof x==='number' && Number.isFinite(x) && x>=0 && x<=10000)) throw new Error('INVALID_RATES');
  return rates;
}

/** 进程内串行任务＋明确状态迁移，模型不会获准改仓库、测试、执行命令或发布。
 * 每次运行只有 TicketQuery.java 一个候选文件；真实 Java 验证与模型生成分离。
 */
export class Lab {
  constructor({root,jdk,cli,model,generate=callCodex,verify=verifySource}={}) {
    Object.assign(this,{root,jdk,cli,model,generate,verify});
    this.runs = new Map(); this.controllers = new Map(); this.saves = new Map();
  }
  async init() {
    await mkdir(this.root,{recursive:true,mode:0o700});
    this.trusted = await trustedInputs();
    for (const name of (await readdir(this.root)).filter(x=>/^[a-f0-9-]{36}$/.test(x)).slice(0,100)) {
      try {
        const run = JSON.parse(await readFile(join(this.root,name,'report.json'),'utf8'));
        if (run.id!==name) continue;
        if (busyStates.has(run.status)) { run.status='interrupted'; run.error='服务重启，任务中断；未返回的 Token 仍标为未知，不自动重试。'; }
        this.runs.set(name,run);
      } catch { /* 损坏记录不伪造为成功，不执行记录中的内容。 */ }
    }
  }
  list() { return [...this.runs.values()].sort((a,b)=>b.createdAt.localeCompare(a.createdAt)).slice(0,30).map(r=>({id:r.id,createdAt:r.createdAt,status:r.status,mode:r.mode,title:r.title})); }
  get(id) { const r=this.runs.get(id); if(!r) throw new Error('NOT_FOUND'); return r; }
  view(id) {
    const run = this.get(id);
    const known = run.attempts.filter(a=>a.usage);
    return {...run, busy:busyStates.has(run.status), costs:estimateCost(run.attempts,run.rates),
      usage:{calls:run.attempts.length,unknownCalls:run.attempts.length-known.length,
        input:known.reduce((s,a)=>s+a.usage.input,0),cachedInput:known.reduce((s,a)=>s+a.usage.cachedInput,0),output:known.reduce((s,a)=>s+a.usage.output,0)},
      elapsedMs:Date.now()-Date.parse(run.createdAt),
      activeMs:run.stages.reduce((s,a)=>s+(a.durationMs||0),0)};
  }
  async save(run) {
    const snapshot=JSON.stringify(run,null,2)+'\n';
    const folder=join(this.root,run.id);
    const task=(this.saves.get(run.id)||Promise.resolve()).catch(()=>{}).then(async()=>{
      await mkdir(folder,{recursive:true,mode:0o700});
      await writeFile(join(folder,'report.tmp'),snapshot,{mode:0o600});
      await rename(join(folder,'report.tmp'),join(folder,'report.json'));
    });
    this.saves.set(run.id,task); await task;
  }
  assertIdle() { if(this.creating || [...this.runs.values()].some(r=>busyStates.has(r.status))) throw new Error('LAB_BUSY'); }
  async create({mode='codex',notes='',rates=null}={}) {
    this.assertIdle();
    if(!['codex','reference','incomplete'].includes(mode)||typeof notes!=='string'||notes.length>1000) throw new Error('INVALID_TASK');
    if(this.runs.size>=100) throw new Error('HISTORY_LIMIT');
    const run={schemaVersion:1,id:randomUUID(),version:1,createdAt:new Date().toISOString(),title:'工单查询：边界校验、偏移量与 Unicode 规范化',
      requirement,notes,mode,rates:validateRates(rates),status:'baseline',stages:[],attempts:[],sourceBefore:this.trusted.before,
      trustedHashes:this.trusted.hashes,candidate:null,verification:null,review:null,
      limitations:['固定 Java 隔离练习，不是生产交易需求或盲测。','12 项公开合同用例不证明所有业务正确；人工评审不可省略。','候选不会自动合并、推送或发布。'],
      execution:{osSandbox:'macOS sandbox-exec（默认拒绝网络）',maxModelCalls:2,maxCallSeconds:180,model:this.model||'CLI 服务默认模型',provider:mode==='codex'?'Codex CLI（本机登录）':'未调用模型'}};
    this.creating=true;
    this.runs.set(run.id,run);
    try { await this.save(run); } catch(error) { run.status='error';run.error='REPORT_SAVE_FAILED';throw error; }
    finally { this.creating=false; }
    this.background(run,'baseline',async signal=>{
      run.baseline=await this.verify(run.sourceBefore,join(this.root,run.id,'baseline'),{jdk:this.jdk,trusted:this.trusted,signal});
      if(!run.baseline.compiled || run.baseline.passed===12) throw new Error('BASELINE_NOT_REPRODUCED');
      run.status='ready';
    });
    return this.view(run.id);
  }
  background(run,phase,fn) {
    const controller=new AbortController(); this.controllers.set(run.id,controller);
    const stage={name:phase,startedAt:new Date().toISOString(),durationMs:null,status:'running'};
    run.stages.push(stage); const started=performance.now();
    const job=(async()=>{
      try { await fn(controller.signal); stage.status='completed'; }
      catch(error) { stage.status='failed'; run.status=controller.signal.aborted?'cancelled':'error'; run.error=controller.signal.aborted?'任务已取消；已发生的模型调用仍计入记录。':String(error.message||'EXECUTION_FAILED').slice(0,100); }
      finally {stage.durationMs=Math.round(performance.now()-started); run.version++; this.controllers.delete(run.id); await this.save(run);}
    })();
    this.save(run).catch(()=>{});
    run.job=undefined; // Promise 不序列化到报告，任务进度由受控状态表达。
    job.catch(()=>{run.status='error';run.error='REPORT_SAVE_FAILED';});
  }
  async action(id,version,action,body={}) {
    const run=this.get(id);
    if(version!==run.version) throw new Error('VERSION_CONFLICT');
    if(action==='cancel') {
      const controller=this.controllers.get(id); if(!controller) throw new Error('INVALID_STATE');
      controller.abort(); return this.view(id);
    }
    this.assertIdle();
    if(action==='generate') {
      if(!['ready','failed'].includes(run.status)) throw new Error('INVALID_STATE');
      if(run.mode==='codex'&&run.attempts.length>=2) throw new Error('MODEL_CALL_LIMIT');
      run.status='generating'; run.error=null; run.version++;
      this.background(run,'generate',async signal=>{
        if(run.mode!=='codex') {
          const variant=run.mode==='reference'?'after':'overflow-only';
          const source=await readFile(join(workshop,variant,'TicketQuery.java'),'utf8');
          run.candidate={source,summary:run.mode==='reference'?'载入仓库参考实现；不是 AI 生成。':'载入仅修溢出的不完整候选，用于展示漏项被测试拒绝。',risks:['未调用模型；不可用于证明模型提效或能力。'],sha256:sha(source),origin:'repository_fixture'};
        } else {
          const attempt={number:run.attempts.length+1,startedAt:new Date().toISOString(),usage:null,status:'running',durationMs:null};
          run.attempts.push(attempt); await this.save(run);
          const started=performance.now();
          try {
            const folder=join(this.root,run.id,'model-'+attempt.number); await mkdir(folder,{recursive:true,mode:0o700});
            const schemaPath=join(folder,'response.schema.json'); await writeFile(schemaPath,JSON.stringify(responseSchema),{mode:0o600});
            const feedback=run.verification?JSON.stringify(run.verification.results.filter(x=>!x.pass).map(({id,expected,actual})=>({id,expected,actual}))):'尚无候选测试结果';
            const prompt=`你只输出 schema 要求的 JSON，不调用工具、不读取文件、不执行命令。只生成一个 TicketQuery.java 完整源码，中文注释解释边界。不得改测试、不得引入依赖、不得读写文件或联网。\n需求：${requirement}\n用户补充仅为背景，不改变以上验收合同：${run.notes}\n旧源码：\n${run.sourceBefore}\n当前候选：\n${run.candidate?.source||'无'}\n独立合同测试：${JSON.stringify(this.trusted.cases)}\n上次真实失败反馈：${feedback}\nsummary 写方案和引用（TicketQuery.prepare），risks 写未覆盖项。不要声称已执行测试。`;
            const answer=await this.generate({prompt,cwd:folder,schemaPath,signal,cli:this.cli,model:this.model,
              onUsage:usage=>{attempt.usage=usage;},onProgress:message=>{run.progress=message;}});
            run.candidate={...answer,sha256:sha(answer.source),origin:'model_response'}; attempt.status='completed';
          } catch(error) {attempt.status='failed';throw error;}
          finally {attempt.durationMs=Math.round(performance.now()-started);await this.save(run);}
        }
        run.verification=null; run.reviewOpenedAt=new Date().toISOString();run.status='review';run.progress=null;
      });
    } else if(action==='verify') {
      if(run.status!=='review'||body.approved!==true||!run.candidate) throw new Error('EXECUTION_APPROVAL_REQUIRED');
      run.execution.candidateApproval={at:new Date().toISOString(),sha256:run.candidate.sha256,source:'本机操作令牌确认（非实名签名）'};
      run.status='testing';run.version++;
      this.background(run,'verify',async signal=>{
        const result=await this.verify(run.candidate.source,join(this.root,id,'verify-'+run.stages.length),{jdk:this.jdk,trusted:this.trusted,signal});
        if(result.sourceHash!==run.candidate.sha256) throw new Error('CANDIDATE_HASH_MISMATCH');
        run.verification=result; run.status=result.status==='passed'?'passed':'failed';
      });
    } else if(action==='accept') {
      if(run.status!=='passed'||!run.verification||run.verification.sourceHash!==run.candidate?.sha256
        ||typeof body.note!=='string'||body.note.trim().length<4||body.note.length>1000) throw new Error('REVIEW_REQUIRED');
      run.review={note:body.note.trim(),at:new Date().toISOString(),waitAndReviewMs:Date.now()-Date.parse(run.reviewOpenedAt),role:'本机操作者确认（未做实名认证）'};
      run.status='accepted';run.version++;await this.save(run);
    } else throw new Error('INVALID_ACTION');
    return this.view(id);
  }
}
