'use client';

import { useId, useState } from 'react';
import casebook from '@/public/runtime-security/casebook.json';
import evidence from '@/public/runtime-security/evidence.json';
import { evaluateRelease } from '@/public/runtime-security/release-gates.mjs';

/** 持久 Worker 与请求内存分属两个存活期；红色连线表示不应保留的旧引用。 */
function LifecycleTopology() {
  const arrow = `security-arrow-${useId().replace(/:/g, '')}`;
  const nodes = [[25, '反向代理', '字节 / 时间 / 并发预算'], [225, 'Web SAPI / 请求解析', '可能早于业务入口'], [425, '应用鉴权与业务', '请求级身份与状态'], [625, '清理 / 释放', '先解除引用'], [825, '下一次请求', '同一 Worker 可能复用']] as const;
  return <figure className="runtime-security-topology"><svg viewBox="0 0 1030 330" role="img" aria-label="请求生命周期拓扑：代理、请求解析、应用、清理及后续请求；持久 Worker 不得保留已释放请求的引用">
    <defs><marker id={arrow} markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0 0 L8 4 L0 8Z" fill="#4075ac"/></marker></defs>
    <rect x="210" y="30" width="810" height="285" rx="16" fill="#f4f8fe" stroke="#b6cce4"/><text x="235" y="57" fontSize="15" fill="#244769">长期存活：Web Worker / 线程池 / 进程内状态槽</text>
    {nodes.map(([x, title, detail], i) => <g key={title}><rect x={x} y="95" width="175" height="78" rx="10" fill="white" stroke="#9fb9d5"/><text x={x + 87.5} y="126" textAnchor="middle" fontSize="15" fill="#203e60">{title}</text><text x={x + 87.5} y="150" textAnchor="middle" fontSize="12" fill="#526b86">{detail}</text>{i < nodes.length - 1 && <path d={`M${x + 175} 134h25`} stroke="#4075ac" strokeWidth="2" markerEnd={`url(#${arrow})`}/>}</g>)}
    <rect x="420" y="215" width="380" height="68" rx="10" fill="#eaf6f1" stroke="#92b7a5"/><text x="610" y="240" textAnchor="middle" fontSize="15" fill="#245c46">单次请求 A 的内存 / 身份上下文</text><text x="610" y="263" textAnchor="middle" fontSize="12" fill="#496d5b">完整初始化后发布，结束前撤销外部引用</text>
    <path d="M505 173v42M710 173v42" stroke="#568e72" strokeWidth="2" strokeDasharray="5 4"/><path d="M800 249h110v-76" fill="none" stroke="#b95757" strokeWidth="2" strokeDasharray="5 4"/><text x="850" y="284" fontSize="12" fill="#a44444">危险：跨请求残留</text>
  </svg><figcaption>代理预算、原生补丁和请求状态隔离各有职责，不能用其中一层替代其他层。</figcaption></figure>;
}

const options = [['complete', '完整交接记录'], ['cli', '只拿到 CLI 版本'], ['stale', '混入旧 Worker'], ['rollback', '回退包未修补']] as const;
type Rollout = typeof options[number][0];

export function RuntimeSecurityDeepDive() {
  const [caseId, setCaseId] = useState('oom');
  const [rollout, setRollout] = useState<Rollout>('stale');
  const current = casebook.cases.find(item => item.id === caseId) ?? casebook.cases[0];
  const digest = `sha256:${'a'.repeat(64)}`;
  const records = { imageDigest: digest, generation: 'patched-1', expectedWorkers: ['web-1', 'web-2'], workers: ['web-1', 'web-2'].map(id => ({ id, sapi: 'fpm-fcgi', generation: 'patched-1', imageDigest: digest, uploadProgressEnabled: false })), patchEvidenceVerified: true, rollbackPatched: rollout !== 'rollback' };
  if (rollout === 'cli') records.workers[0].sapi = 'cli';
  if (rollout === 'stale') records.workers[1].generation = 'old';
  const gate = evaluateRelease(records);
  return <>
    <details id="runtime-security-casebook"><summary>四个案例深入拆解：触发前提、执行顺序、修改与验证</summary>
      <LifecycleTopology/>
      <div className="runtime-security-actions" aria-label="选择漏洞案例">{casebook.cases.map(item => <button key={item.id} type="button" aria-pressed={caseId === item.id} onClick={() => setCaseId(item.id)}>{item.cve}</button>)}</div>
      <article className="runtime-security-case" aria-live="polite"><p className="overview-eyebrow">{current.layer}</p><h3>{current.title}</h3><p>{current.summary}</p>
        <h4>需要同时核对的前提</h4><ol className="runtime-security-checklist">{current.preconditions.map(line => <li key={line}>{line}</li>)}</ol>
        <div className="runtime-security-table"><table><thead><tr><th>阶段</th><th>发生的事</th><th>应保护的约束</th></tr></thead><tbody>{current.steps.map(([phase, problem, invariant]) => <tr key={phase}><th scope="row">{phase}</th><td>{problem}</td><td>{invariant}</td></tr>)}</tbody></table></div>
        <div className="runtime-security-code-compare"><section><h4>错误机制 / 受影响配置</h4><pre><code>{current.before}</code></pre></section><section><h4>修正原则 / 应用配置</h4><pre><code>{current.after}</code></pre></section></div>
        <p className="runtime-security-baseline">{current.patch}</p><h4>为什么常见处理还不够</h4><p>{current.nonFix}</p><h4>怎么判断结果</h4><ul>{current.verification.map(line => <li key={line}>{line}</li>)}</ul>
        <p><a href={current.source} target="_blank" rel="noreferrer">原始公告 ↗</a> · 代码片段解释机制与配置，不是 PHP 源码补丁。完整实现见对应实验文件。</p>
      </article>
    </details>
    <details id="runtime-security-labs"><summary>四种语言的实测对照，以及解压预算实验</summary>
      <p>这些结果由本地编译或执行得到。验证器与源码均在下载包中；教学实验不等于 PHP 原生补丁验收。</p>
      <div className="runtime-security-table"><table><thead><tr><th>实验</th><th>错误实现 / 负例</th><th>修正后结果</th><th>源码</th></tr></thead><tbody>
        <tr><th scope="row">Java 线程池</th><td>失败任务后仍读到 A</td><td>成功、失败均清理，B 正常绑定</td><td><a href="/runtime-security/ThreadLocalIsolationDemo.java">Java</a></td></tr>
        <tr><th scope="row">C++ 所有权</th><td>ASan 检出 heap-use-after-free，退出 {evidence.cpp.negativeExit}</td><td>{evidence.cpp.safeChecks} 项断言通过，拒绝过期弱引用</td><td><a href="/runtime-security/request_lifetime.cpp">C++</a></td></tr>
        <tr><th scope="row">Python 协程</th><td>{evidence.python.negative.join(' / ')}</td><td>{evidence.python.fixed.join(' / ')}；异常后恢复外层值</td><td><a href="/runtime-security/context_isolation.py">Python</a></td></tr>
        <tr><th scope="row">Node.js 异步</th><td>{evidence.nodeContext.negative.join(' / ')}</td><td>{evidence.nodeContext.fixed.join(' / ')}；异常与嵌套隔离通过</td><td><a href="/runtime-security/runtime-guards.mjs">Node.js</a></td></tr>
        <tr><th scope="row">解压预算</th><td>{evidence.decoding.compressedBytes} B → {evidence.decoding.unboundedBytes / 1024} KiB</td><td>{evidence.decoding.outputLimit / 1024} KiB 上限拒绝：{evidence.decoding.boundedCode}</td><td><a href="/runtime-security/runtime-guards.test.mjs">测试</a></td></tr>
      </tbody></table></div>
      <div className="runtime-security-code-compare"><section><h4>Python：保存 token，结束时恢复</h4><pre><code>{`token = TENANT.set(tenant)\ntry:\n    yield\nfinally:\n    TENANT.reset(token)`}</code></pre></section><section><h4>Node.js：异步作用域代替全局变量</h4><pre><code>{`return context.run(\n    Object.freeze({ tenant }),\n    action\n);\n// 每条异步链读取自己的 store。`}</code></pre></section></div>
      <p>Python 实际解释器：{evidence.python.version}。C++ 使用 AddressSanitizer；Node 覆盖实际输入/输出计量、截断、取消和并发拒绝。Go / Rust 保留机制对照，本轮未执行其工具链。</p>
      <p>解压上限降低资源耗尽风险，不能修补解码器在产出字节前的原生错误；这里的 gzip 数据不代表 Brotli 漏洞测试。</p>
    </details>
    <details id="runtime-security-rollout"><summary>修复交接演练：为什么还有一个旧 Worker 就不能结束？</summary>
      <p>假设两个 Web Worker 应切换到同一制品与代际。切换示例记录观察交接规则；这些数据不是现网清单。</p>
      <div className="runtime-security-actions">{options.map(([value, label]) => <button key={value} type="button" aria-pressed={rollout === value} onClick={() => setRollout(value)}>{label}</button>)}</div>
      <div className="runtime-security-table"><table><thead><tr><th>Worker</th><th>观测来源</th><th>代际</th><th>上传进度</th></tr></thead><tbody>{records.workers.map(worker => <tr key={worker.id}><th scope="row">{worker.id}</th><td>{worker.sapi}</td><td>{worker.generation}</td><td>{worker.uploadProgressEnabled ? '开启' : '关闭'}</td></tr>)}</tbody></table></div>
      <div className="runtime-security-result" aria-live="polite"><strong>{gate.passed ? '示例记录满足交接约束' : '交接仍有待办'}</strong>{gate.errors.length ? <ul>{gate.errors.map((error, i) => <li key={i}>{error}</li>)}</ul> : <p>范围、SAPI、代际、摘要、配置及补丁/回退记录一致。</p>}</div>
      <p>这里检验记录的一致性。真实性、供应商签名、线上风险和业务验收需要实际证据，手册另有责任分工与验收矩阵。</p><a href="/runtime-security/release-gates.mjs">交接规则源码</a>
    </details>
  </>;
}
