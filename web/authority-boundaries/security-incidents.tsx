'use client';

import { useState } from 'react';
import casebook from '@/public/authority-boundaries/casebook.json';
import evidence from '@/public/authority-boundaries/evidence.json';
import { runScenario, scenarioDefinitions } from '@/public/authority-boundaries/models.mjs';
import { AuthorityTopology, SigningBoundarySequence } from '@/components/security-incident-diagrams';
import { SecurityIncidentDeepDive } from '@/components/security-incident-deep-dive';

const panel='rounded-xl border border-emerald-900/15 bg-white/80 p-4';
const code='mt-3 max-h-96 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-slate-950 p-4 text-xs leading-6 text-slate-100';
const source=(id:string)=>casebook.sources.find(s=>s.id===id)||casebook.sources[0];
const javaExcerpt=`// 先验证业务域、收款人、链、金额、有效期、策略和会话代次\nvalidate(intent, serverNow);\nbyte[] hash = SHA256(canonicalEncode(intent));\nif (!constantTimeEqual(hash, approval.intentHash())) reject("INTENT_MISMATCH");\nif (!verifyApprovalSignature(hash, approval.signature())) reject("INVALID_APPROVAL");\n// 原子检查防重与累计预算，匹配后才允许签名\n// 生产需持久化并跨实例原子预占，不能只靠JVM锁或Map`;

/** 事实、建议和实验分层；只用本地模型回放，不连接真实钱包、交易所或签名器。 */
export function SecurityIncidents(){
  const [selected,setSelected]=useState('vendor-domain');
  const replay=runScenario(selected);
  const current=scenarioDefinitions.find(item=>item.id===selected)!;
  return <section id="authority-boundaries" className="scroll-mt-24 rounded-2xl border border-emerald-800/20 bg-[#f2f7f2] p-5 text-slate-800 md:p-7">
    <header>
      <p className="font-mono text-xs tracking-widest text-emerald-800">SECURITY INCIDENTS · 2026-10-30 · 业务与权限边界</p>
      <h2 className="mt-3 max-w-4xl text-2xl font-semibold leading-tight md:text-4xl">{casebook.headline}</h2>
      <p className="mt-4 max-w-4xl text-sm leading-7 text-slate-600">{casebook.plainSummary}</p>
      <nav className="mt-5 flex flex-wrap gap-3 text-sm text-emerald-900" aria-label="安全事件阅读路径">
        <a href="#authority-facts">事实与时间线</a><a href="#authority-architecture">防御架构与机制</a><a href="#authority-signing-detail">详细签名时序</a><a href="#authority-7702-detail">7702状态图</a><a href="#authority-response">止血与恢复</a><a href="#authority-lab">场景与测试</a><a href="/authority-boundaries/authority-boundaries-20261030.md">完整手册</a>
      </nav>
    </header>

    <div className="mt-6 grid gap-4 lg:grid-cols-2">
      <article className={panel}><p className="text-xs font-medium text-amber-800">Polymarket · 页面供应链</p><h3 className="mt-2 text-xl font-semibold">测页面的工具，不该碰交易权限</h3><p className="mt-3 text-sm leading-7">官方确认第三方受损，并临时限制交易和中继。研究方描述监控集成复用已登录页面的交易能力。这里不是“用户都点了钓鱼链接”，也不能假设每位访客都成功被盗。</p><a className="mt-3 inline-block text-sm text-emerald-800" href={source('poly-status').url} target="_blank" rel="noreferrer">官方状态与响应 ↗</a></article>
      <article className={panel}><p className="text-xs font-medium text-amber-800">Binance · 运维到签名边界</p><h3 className="mt-2 text-xl font-semibold">没有发现私钥泄露，不代表没有越权签名</h3><p className="mt-3 text-sm leading-7">官方初步披露疑似运维电脑受损后进入服务容器，并称未发现私钥泄露证据。研究方的7702链路解释与最终根因不是同一层事实。总影响约63亿美元、冻结约30亿美元均为当时官方估算。</p><a className="mt-3 inline-block text-sm text-emerald-800" href={source('binance').url} target="_blank" rel="noreferrer">官方初步公告 ↗</a></article>
    </div>
    <aside className="mt-4 rounded-xl border border-amber-600/25 bg-amber-50 p-4 text-sm leading-7"><strong>先纠正两个口径：</strong>币安公告后续修订注明入金正常、暂停提现；冻结不是追回，不应把“总影响−冻结”当最终损失。本文截至{casebook.asOf}，不认定两起事件存在关联，也不推定攻击组织归属。</aside>

    <div className="mt-5 grid gap-3 md:grid-cols-2">{casebook.business.map(item=><article className={panel} key={item.title}><h3 className="font-semibold">{item.title}</h3><p className="mt-2 text-sm text-slate-600">{item.problem}</p><p className="mt-2 text-sm leading-7">{item.action}</p></article>)}</div>

    <details id="authority-facts" className="mt-6 scroll-mt-24 border-t border-emerald-900/15 pt-3">
      <summary className="cursor-pointer py-3 text-lg font-semibold">01 / 发生了什么：事实等级、信息更新与时间线</summary>
      <div className="mt-3 space-y-3">{casebook.facts.map((fact,index)=><article key={index} className={panel}><p className="text-xs font-medium text-emerald-800">{fact.event} · {fact.level}</p><p className="mt-2 text-sm leading-7">{fact.statement}</p><a className="mt-2 inline-block text-xs text-emerald-800" href={source(fact.source).url} target="_blank" rel="noreferrer">核对来源：{source(fact.source).title} ↗</a></article>)}</div>
      <h4 className="mt-5 font-semibold">同一时区看时间：以下均为北京时间（UTC+8）</h4>
      <ol className="mt-3 space-y-3">{casebook.timeline.map(item=><li className="border-l-2 border-emerald-700/30 pl-4" key={item.time}><p className="text-sm font-medium">{item.time} · {item.event} · {item.level}</p><p className="mt-1 text-sm text-slate-600">{item.detail}</p></li>)}</ol>
    </details>

    <details id="authority-architecture" className="mt-5 scroll-mt-24 border-t border-emerald-900/15 pt-3">
      <summary className="cursor-pointer py-3 text-lg font-semibold">02 / 技术机制：信任域、持久授权与最终签名字节</summary>
      <p className="my-3 text-sm text-slate-600">下图是FinCore建议的防御拓扑，不是两家平台已经公开的真实内部拓扑。关键是：页面和运维身份不能绕过独立的业务意图与签名策略。</p>
      <AuthorityTopology/>
      <h4 className="mt-5 font-semibold">一笔提现怎样过边界</h4><SigningBoundarySequence/>
      <div className="mt-5 grid gap-3 lg:grid-cols-2">{casebook.controls.map(item=><article className={panel} key={item.layer}><h4 className="font-semibold">{item.layer}</h4><p className="mt-2 text-sm text-rose-800">风险：{item.risk}</p><p className="mt-2 text-sm leading-7">落地：{item.implementation}</p><p className="mt-2 text-xs leading-6 text-slate-500">不能保证：{item.limit}</p></article>)}</div>
      <article className="mt-5 rounded-xl border border-sky-800/20 bg-sky-50 p-4"><h4 className="font-semibold">7702的三个关键点</h4><ul className="mt-2 list-disc space-y-2 pl-5 text-sm leading-7"><li>EOA可以登记持久代码委托；授权列表有独立的签名域、链和nonce，不是普通零金额转账。</li><li>列表中无效项跳过，生效项会递增nonce。猜中nonce仍不能绕过有效签名；“50项”不能证明私钥从哪里来。</li><li>外层执行失败不回滚已处理的委托。退出网站或只撤销ERC-20 allowance不等于清除7702授权；疑似密钥失守还须重建可信密钥根。</li></ul><a className="mt-3 inline-block text-xs text-emerald-800" href={source('eip').url} target="_blank" rel="noreferrer">EIP-7702规范 ↗</a></article>
      <h4 className="mt-5 font-semibold">真正的代码点：先核对，再签名</h4><pre className={code}>{javaExcerpt}</pre><p className="mt-2 text-xs leading-6 text-slate-500">上段是阅读用伪代码；可编译的真实实现见下载包SigningIntentGuard.java，使用临时Ed25519密钥验证审批绑定，不是以太坊签名器。</p>
      <SecurityIncidentDeepDive/>
    </details>

    <details id="authority-response" className="mt-5 scroll-mt-24 border-t border-emerald-900/15 pt-3">
      <summary className="cursor-pointer py-3 text-lg font-semibold">03 / 怎么避免、止血、修复，何时才能恢复</summary>
      <div className="mt-3 grid gap-3 lg:grid-cols-2">{casebook.runbook.map(item=><article className={panel} key={item.phase}><h4 className="font-semibold">{item.phase}</h4><p className="mt-1 text-xs text-emerald-800">负责人：{item.owner}</p><ol className="mt-3 list-decimal space-y-2 pl-5 text-sm leading-7">{item.actions.map(action=><li key={action}>{action}</li>)}</ol></article>)}</div>
      <p className="mt-4 text-sm leading-7"><strong>恢复的门槛：</strong>旧权限撤销、身份凭据轮换、环境可信、资产与负债核对、限额与熔断可用。先小额金丝雀再分批恢复；删脚本、重启容器、链上交易失败，都不是单独足够的恢复证据。</p>
    </details>

    <details id="authority-lab" className="mt-5 scroll-mt-24 border-t border-emerald-900/15 pt-3">
      <summary className="cursor-pointer py-3 text-lg font-semibold">04 / 可操作回放与真实防御测试记录</summary>
      <p className="mt-2 text-sm text-slate-600">切换下面八种合成场景，查看每一步判断。它们不加载真实攻击脚本、不调用钱包、不广播交易，也不证明真实平台已修好。</p>
      <div className="mt-4 grid gap-2 sm:grid-cols-2 xl:grid-cols-4">{scenarioDefinitions.map(item=><button key={item.id} type="button" aria-pressed={selected===item.id} onClick={()=>setSelected(item.id)} className={`rounded-lg border p-3 text-left text-sm ${selected===item.id?'border-emerald-800 bg-emerald-100':'border-slate-200 bg-white'}`}><span className="text-xs text-emerald-800">{item.group} · {item.result}</span><span className="mt-1 block">{item.title}</span></button>)}</div>
      <div className="mt-4 rounded-xl bg-white p-4" aria-live="polite"><h4 className="font-semibold">{current.title}</h4><p className="mt-2 text-sm text-slate-600">{current.description}</p><ol className="mt-3 space-y-2">{replay.timeline.map((step,index)=><li className="border-l-2 border-emerald-800/25 pl-3 text-sm" key={index}><strong>{step.step}</strong><p className="mt-1 text-slate-600">{step.fact}</p></li>)}</ol><details className="mt-3"><summary className="cursor-pointer text-sm">查看模型输出</summary><pre className={code}>{JSON.stringify(replay.detail,null,2)}</pre></details></div>
      <div className="mt-5 grid gap-3 sm:grid-cols-3">{[[`${evidence.node.tests} 项`,'Node规则/摘要/恢复测试'],[`${evidence.java.passed} 项`,'Java真实验签与并发断言'],[`${evidence.totalDurationMs} ms`,'本次本地验证耗时，非性能基准']].map(([value,label])=><div key={label} className={panel}><p className="text-xl font-semibold">{value}</p><p className="mt-1 text-xs text-slate-500">{label}</p></div>)}</div>
      <p className="mt-3 text-sm leading-7">并发实验：3个请求各7500最小单位，累计上限20000；实际仅{evidence.java.concurrentAccepted}个签名通过、{evidence.java.concurrentRejected}个被拒绝。临时密钥不出实验进程，没有真实钱包或资金。</p>
      <details className="mt-3"><summary className="cursor-pointer text-sm">源码摘要与验证范围</summary><pre className={code}>{JSON.stringify({verifiedAt:evidence.verifiedAt,sourceSha256:evidence.sourceSha256,scope:evidence.scope},null,2)}</pre></details>
      <p className="mt-3 text-xs leading-6 text-slate-500">待真实环境验收：浏览器CSP/SRI/CORS、旧会话清理、签名服务独立身份、以太坊交易解码、多链撤销及跨实例资金预算。测试通过不等于生产安全认证。</p>
    </details>

    <details className="mt-5 border-t border-emerald-900/15 pt-3"><summary className="cursor-pointer py-3 font-semibold">资料来源与核对边界</summary><ul className="mt-3 space-y-3 text-sm">{casebook.sources.map(item=><li key={item.id}><a className="font-medium text-emerald-800" href={item.url} target="_blank" rel="noreferrer">{item.title} ↗</a><p className="mt-1 text-xs text-slate-500">{item.kind} · {item.verified}</p></li>)}</ul></details>
    <footer id="authority-handbook" className="mt-5 flex scroll-mt-24 flex-wrap gap-3 text-sm"><a className="rounded-lg border border-emerald-800/20 bg-white px-4 py-2 text-emerald-900" href="/authority-boundaries/authority-boundaries-20261030.md">完整业务与技术手册</a><a className="rounded-lg border border-emerald-800/20 bg-white px-4 py-2 text-emerald-900" href="/authority-boundaries/source.zip">防御源码与测试下载</a><a className="rounded-lg border border-emerald-800/20 bg-white px-4 py-2 text-emerald-900" href="/authority-boundaries/evidence.json">真实验证记录 JSON</a></footer>
  </section>;
}
