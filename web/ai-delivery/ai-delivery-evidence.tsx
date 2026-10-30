'use client';

import { useState } from 'react';
import evidence from '@/public/ai-delivery/evidence.json';

const seconds = (ms: number) => `${(ms / 1000).toFixed(2)} 秒`;
const number = (value: number) => value.toLocaleString('zh-CN');
const stages: Record<string, string> = { baseline: '旧版复现', generate: '候选生成', verify: '独立验证' };
const block = 'rounded-xl border border-emerald-900/15 bg-white/60 p-4';
const code = 'mt-3 max-h-96 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-slate-950 p-4 text-xs leading-6 text-slate-100';

/** 仅渲染审核后导出的静态证据；无模型客户端、执行权限或对本机执行器的请求。 */
export function AiDeliveryEvidence() {
  const [selected, setSelected] = useState(0);
  const [prices, setPrices] = useState(['', '', '']);
  const run = evidence.runs[selected];
  const invoked = run.calls.length > 0;
  const rates = prices.map(Number);
  const validRates = prices.every(value => value.trim() !== '') && rates.every(value => Number.isFinite(value) && value >= 0 && value <= 10000);
  const estimate = invoked && validRates ? ((run.tokenUsage.input - run.tokenUsage.cachedInput) * rates[0] + run.tokenUsage.cachedInput * rates[1] + run.tokenUsage.output * rates[2]) / 1_000_000 : null;

  return (
    <section id="ai-delivery-evidence" className="scroll-mt-24 rounded-2xl border border-emerald-700/25 bg-emerald-50/80 p-5 text-slate-800 md:p-7">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <p className="font-mono text-xs tracking-widest text-emerald-800">VERIFIED DELIVERY · 已记录的真实执行</p>
          <h3 className="mt-3 text-2xl font-semibold md:text-3xl">从需求到代码，再到测试证据</h3>
          <p className="mt-3 max-w-3xl text-sm leading-7 text-slate-600">实现一个内部工单查询需求：修复大页号溢出，补齐参数校验与 Unicode 空白处理。模型生成候选，独立 Java 进程负责验证；这里展示的是实测记录，不是正在调用 AI。</p>
        </div>
        <span className="rounded-full border border-emerald-700/20 bg-white px-3 py-1 text-xs font-medium text-emerald-800">公网只读 · 不开放模型调用</span>
      </div>

      <div className="mt-6 grid gap-3 md:grid-cols-3" aria-label="选择已记录的运行">
        {evidence.runs.map((item, index) => (
          <button type="button" key={item.id} aria-pressed={selected === index} onClick={() => setSelected(index)} className={`rounded-xl border p-4 text-left ${selected === index ? 'border-emerald-700 bg-emerald-100' : 'border-slate-200 bg-white'}`}>
            <span className="block text-sm font-semibold">{item.label}</span>
            <span className="mt-2 block text-sm text-slate-600">{item.before.passed}/12 → {item.after.passed}/12 · {item.status === 'passed' ? '测试通过' : '未满足完整需求'}</span>
          </button>
        ))}
      </div>

      <div className="mt-5 grid gap-3 sm:grid-cols-2 xl:grid-cols-4" aria-live="polite">
        {[
          ['独立测试', `${run.before.passed}/12 → ${run.after.passed}/12`],
          ['执行耗时 · 不含人工等待', seconds(run.activeMs)],
          ['输入 / 输出 Token', invoked ? `${number(run.tokenUsage.input)} / ${number(run.tokenUsage.output)}` : '未调用模型'],
          ['实际账单金额', invoked ? '未知，不虚构金额' : '无模型调用记录'],
        ].map(([label, value]) => <div key={label} className={block}><p className="text-xs text-slate-500">{label}</p><p className="mt-2 text-xl font-semibold">{value}</p></div>)}
      </div>

      <ol className="mt-5 grid gap-2 text-sm sm:grid-cols-2 xl:grid-cols-5" aria-label="需求交付链路">
        <li className={block}>01 · 固定需求与边界</li>
        <li className={block}>02 · 旧版失败已复现</li>
        <li className={block}>03 · {invoked ? '真实模型生成' : '显式参考夹具'}</li>
        <li className={block}>04 · 独立验证 {run.after.passed}/12</li>
        <li className={block}>05 · {run.humanAccepted ? '已有验收记录' : run.status === 'passed' ? '待人工验收' : '拒绝进入验收'}</li>
      </ol>

      <details className="mt-5 border-t border-emerald-900/15 pt-3">
        <summary className="cursor-pointer py-2 font-medium">展开耗时、Token 与费用口径</summary>
        <div className="mt-3 grid gap-3 md:grid-cols-3">{run.stages.map((stage, index) => <div key={`${stage.name}-${index}`} className={block}><p className="text-sm text-slate-600">{stages[stage.name]}</p><p className="mt-1 font-mono text-lg">{seconds(stage.durationMs)}</p></div>)}</div>
        <p className="mt-4 text-sm">模型调用 {run.calls.length} 次；缓存输入 {number(run.tokenUsage.cachedInput)} Token（已包含在输入中，不重复累计）。{invoked ? '用量读取自 Codex CLI 完成事件，未用字符数估算。' : '这是非 AI 对照，不能把结果归因于模型。'}</p>
        <p className="mt-2 text-sm text-slate-600">本轮使用订阅登录，没有可核实的每 Token 实付价格。下方可自行填写单价做假设折算，不代表扣款，也不包含人工或基础设施成本。</p>
        <div className="mt-4 grid gap-3 sm:grid-cols-3">{['未缓存输入', '缓存输入', '输出'].map((label, index) => <label className="text-xs text-slate-600" key={label}>{label} · USD / 百万 Token<input className="mt-2 block w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm" type="number" min="0" max="10000" step="any" value={prices[index]} onChange={event => setPrices(previous => previous.map((value, i) => i === index ? event.target.value : value))} /></label>)}</div>
        <p className="mt-3 text-sm font-medium" aria-live="polite">假设费用：{estimate === null ? '请完整填写有效单价；当前不折算' : `USD ${estimate.toFixed(6)}（估算，非账单）`}</p>
        <p className="mt-2 text-xs text-slate-500">选定的成功运行不等于全部开发消耗；早期调试失败调用可能未返回用量。本例无人工耗时基线，不能据此宣称团队提效比例。</p>
      </details>

      <details className="mt-4 border-t border-emerald-900/15 pt-3">
        <summary className="cursor-pointer py-2 font-medium">展开需求、候选代码与逐行差异</summary>
        <p className="mt-3 text-sm leading-7">{run.requirement}</p>
        <p className="mt-2 break-all font-mono text-xs text-slate-500">候选 SHA-256：{run.candidateSha256}</p>
        <div className="mt-4 grid gap-4 xl:grid-cols-2"><div><h4 className="font-medium">旧版 TicketQuery.java</h4><pre className={code}>{run.beforeSource}</pre></div><div><h4 className="font-medium">{invoked ? '模型返回的候选源码' : '对照候选源码'}</h4><pre className={code}>{run.candidateSource}</pre></div></div>
        <details className="mt-3"><summary className="cursor-pointer py-2 text-sm">查看修改内容（− 旧版 / ＋ 候选）</summary><pre className={code}>{[...run.beforeSource.split('\n').map(line => `− ${line}`), ...run.candidateSource.split('\n').map(line => `＋ ${line}`)].join('\n')}</pre></details>
      </details>

      <details className="mt-4 border-t border-emerald-900/15 pt-3">
        <summary className="cursor-pointer py-2 font-medium">展开 12 项真实测试：输入、期望、实际结果</summary>
        <p className="mt-2 text-sm text-slate-600">同一组受信探针和合同用例，分别编译旧版与候选。模型不能改测试；编译/运行异常不算预期红。异常参数、整数溢出和 Unicode 边界均可逐条核对。</p>
        <div className="mt-4 overflow-x-auto"><table className="w-full min-w-[620px] text-left text-sm"><thead><tr className="border-b border-emerald-900/15"><th className="p-3">用例</th><th className="p-3">旧版</th><th className="p-3">候选</th><th className="p-3">验证证据</th></tr></thead><tbody>{run.after.results.map((result, index) => <tr key={result.id} className="border-b border-slate-200"><td className="p-3"><span className="font-mono">{result.id}</span><p className="mt-1 text-xs text-slate-600">{result.name}</p></td><td className="p-3">{run.before.results[index].pass ? '通过' : '失败'}</td><td className={`p-3 font-medium ${result.pass ? 'text-emerald-800' : 'text-rose-700'}`}>{result.pass ? '通过' : '失败'}<p className="mt-1 text-xs font-normal">{result.durationMs} ms</p></td><td className="p-3"><details><summary className="cursor-pointer">查看实际值</summary><pre className={code}>{JSON.stringify({input:result.input,expected:result.expected,beforeActual:run.before.results[index].actual,candidateActual:result.actual},null,2)}</pre></details></td></tr>)}</tbody></table></div>
      </details>

      <div className="mt-5 flex flex-wrap gap-3 text-sm"><a className="rounded-lg border border-emerald-800/25 bg-white px-4 py-2 text-emerald-900" href="/ai-delivery/evidence.json" download>下载公开运行证据 JSON</a><a className="rounded-lg border border-emerald-800/25 bg-white px-4 py-2 text-emerald-900" href="https://github.com/soldierking04d/fincore-reliability-lab/tree/main/ai/developer-adoption/live-lab" target="_blank" rel="noreferrer">查看执行器与测试源码 ↗</a></div>
      <p className="mt-4 text-xs leading-6 text-slate-500">记录时间：{run.createdAt.replace('T',' ').replace('Z',' UTC')}。{run.scope} 网页仅切换已保存的证据，不重新生成或执行代码；本地账号、令牌、操作接口和私有报告不在公网发布。</p>
    </section>
  );
}
