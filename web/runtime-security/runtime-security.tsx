'use client';

import { useMemo, useState } from 'react';
import { assessPhpAsset, demonstrateLifecycle, policyDate, sources } from '@/public/runtime-security/php-policy.mjs';
import evidence from '@/public/runtime-security/evidence.json';
import { RuntimeSecurityDeepDive } from '@/components/runtime-security-deep-dive';

const statusLabel = { action: '需要处理', verify: '需要核验', covered: '满足本项规则', 'not-observed': '本记录未发现路径' } as const;
const languages = [
  ['Java', '线程池复用的 ThreadLocal / MDC 可残留租户身份；JNI 和本地依赖另有原生风险。', '同步作用域 finally 清理；异步显式传递身份；验证异常后下一请求不继承上下文。'],
  ['C / C++', '半初始化公开、释放后使用、回调保存悬挂指针。', '完成构造再发布；RAII / 所有权与销毁顺序；Sanitizer 和解析器 fuzz。'],
  ['Python', 'C 扩展、借用引用与异步请求状态都有各自的生命周期。', '区分强引用和借用引用；ContextVar 在 finally reset；原生依赖单独核验。'],
  ['Node.js', '原生 addon、V8、本地解压库不由业务 JavaScript 的 GC 全面保障。', '请求范围对象 / AsyncLocalStorage；检查 addon 与 Worker 清理；限制解压输出。'],
  ['Go', 'cgo / unsafe 与普通 Go 的保证不同；复用对象可能残留业务身份。', '明确 C 内存归属；sync.Pool 对象复用前后重置；上下文显式传参。'],
  ['Rust', '安全 Rust 减少内存生命周期错误，但 FFI、unsafe 与 C 库仍有边界。', '明确回调注册/注销与对象存活期；安全封装；验证原生边界。'],
];

/** 数据检查在浏览器内运行；同一份源码供命令行与下载包验证。 */
export function RuntimeSecurity() {
  const [phpVersion, setVersion] = useState('8.4.27');
  const [platform, setPlatform] = useState('linux');
  const [distribution, setDistribution] = useState('upstream');
  const [uploadProgressEnabled, setUpload] = useState(true);
  const [workersReplaced, setWorkers] = useState(false);
  const [windowsRebuilt, setRebuilt] = useState(false);
  const [safe, setSafe] = useState(true);
  const result = useMemo(() => assessPhpAsset({ phpVersion, platform, distribution, uploadProgressEnabled, workersReplaced,
    windowsBuild: windowsRebuilt ? `${phpVersion}-1` : phpVersion, brotliLoaded: platform === 'windows' }),
    [phpVersion, platform, distribution, uploadProgressEnabled, workersReplaced, windowsRebuilt]);
  const lifecycle = demonstrateLifecycle({ safe });
  return <section id="runtime-security" className="runtime-security">
    <header><p className="overview-eyebrow">RUNTIME SECURITY · {policyDate}</p>
      <h2>PHP 漏洞暴露的是请求生命周期问题。</h2>
      <p>从运行时补丁、上传配置与依赖包开始，再看 Java 线程池和其他语言中的同类风险。先核对入口与版本，再展开代码和验收步骤。</p>
      <nav aria-label="安全专题资源"><a href="#runtime-security-casebook">逐案拆解</a><a href="#runtime-security-labs">实测对照</a><a href="/runtime-security/php-runtime-2026-10.md">完整实施手册</a><a href="/runtime-security/source.zip">源码与测试下载</a><a href="/runtime-security/evidence.json">本地验证记录</a></nav>
    </header>
    <div className="runtime-security-overview">
      <article><span>01 · 初始化失败</span><h3>CVE-2026-109618</h3><p>未完成初始化的状态在 OOM 中断后被清理流程读取。补丁在运行时，业务校验不能代替升级。</p><a href={sources[1].url} target="_blank" rel="noreferrer">官方技术依据 ↗</a></article>
      <article><span>02 · Worker 复用</span><h3>CVE-2026-109619</h3><p>请求内存结束后，Worker 仍可能持有旧引用。后续请求在正常初始化前访问残留状态。</p><a href={sources[2].url} target="_blank" rel="noreferrer">官方技术依据 ↗</a></article>
      <article><span>03 · 后续更新</span><h3>核心版本之外的两项检查</h3><p>10/23 会话配置补充涉及 CVE-2026-115560；10/26 Windows 包更新 Brotli，涉及 CVE-2026-111497。</p><a href={sources[0].url} target="_blank" rel="noreferrer">查看官方后续公告 ↗</a></article>
    </div>
    <p className="runtime-security-baseline">10/21 核心补丁基线：8.2.35 / 8.3.36 / 8.4.27 / 8.5.12。Windows 官方依赖重打包另有 -1 修订。供应商回补按完整包与公告核验。</p>
    <RuntimeSecurityDeepDive />

    <details id="runtime-security-mechanism"><summary>为什么应用还没执行，也可能出问题？</summary>
      <ol className="runtime-security-flow" aria-label="请求到 Worker 复用的处理顺序">
        {['代理与请求预算', '运行时初始化 / 请求解析', '应用入口与鉴权', '业务处理', '请求清理与 Worker 复用'].map(step => <li key={step}>{step}</li>)}
      </ol>
      <p>PHP 的部分上传会话处理在应用脚本前发生。添加应用内过滤、隐藏报错、更新框架，并不能修复运行时里的无效内存状态。</p>
      <div className="runtime-security-actions"><button type="button" aria-pressed={!safe} onClick={() => setSafe(false)}>观察错误生命周期</button><button type="button" aria-pressed={safe} onClick={() => setSafe(true)}>观察先初始化、再发布</button></div>
      <p>下面是受控异常的教学模型：{lifecycle.staleState ? '旧 Worker 引用仍在，请求 B 可能消费请求 A 的状态。' : '请求结束先解除引用，请求 B 不再看到旧状态。'}它不制造真实内存损坏，也不复现 PHP 原生利用。</p>
      <div className="runtime-security-table"><table><thead><tr><th>阶段</th><th>Worker 是否可见</th><th>约束</th></tr></thead><tbody>
        {lifecycle.events.map((event, i) => <tr key={i}><td>{event.phase}</td><td>{event.published ? '持有引用' : '无公开引用'}</td><td>{'staleState' in event ? event.staleState ? '存在残留' : '未读到残留' : event.ready ? '初始化完成' : '尚未完成初始化'}</td></tr>)}
      </tbody></table></div>
    </details>

    <details id="runtime-security-remediation"><summary>怎么落地修复：盘点、配置、发布、验收</summary>
      <ol className="runtime-security-checklist">
        <li><strong>找准运行进程。</strong>盘点 Web SAPI、扩展、镜像摘要与旧 Worker；CLI 输出只作旁证。</li>
        <li><strong>更新对应软件。</strong>修复 PHP 核心并核对供应商回补；Windows 包修订和实际 Brotli 依赖单独验收。</li>
        <li><strong>配置在解析前生效。</strong>暂时停用上传进度；PHP 8.5 的 fatal backtrace 入口另作收窄；改动必须覆盖全部 Worker。</li>
        <li><strong>约束资源与请求。</strong>按业务限制请求体、解压输出、字段/部件与超时；预算包含 Worker 峰值 RSS 和并发。关闭 PHP 内存上限需另行评估 cgroup 与退出策略。</li>
        <li><strong>小流量替换、观察再放量。</strong>检查崩溃、5xx、RSS/OOM、P99 与队列。单实例安排维护窗口；回退制品也必须已修补。</li>
      </ol>
      <pre><code>{`; FPM pool 示例，核对业务影响后使用\nphp_admin_flag[session.upload_progress.enabled] = off\n; 仅 PHP 8.5：部分已确认路径的缓解\nphp_admin_flag[fatal_error_backtraces] = off\nphp_admin_value[post_max_size] = 32M\nphp_admin_value[upload_max_filesize] = 24M`}</code></pre>
      <p>这些配置缩小部分暴露面，不代替核心升级。上传进度会影响产品功能；完整手册列出内存上限取舍、缺少 Content-Length 等测试与回退要求。</p>
    </details>

    <details id="runtime-security-languages"><summary>Java、C++、Python、Node.js、Go、Rust 的类似风险</summary>
      <p>下面比较的是生命周期与原生依赖机制，不表示其他语言受上述 PHP CVE 影响，也不把普通上下文残留直接等同于 RCE。</p>
      <div className="runtime-security-table"><table><thead><tr><th>语言</th><th>类似问题</th><th>处理重点</th></tr></thead><tbody>{languages.map(([name, risk, action]) => <tr key={name}><th scope="row">{name}</th><td>{risk}</td><td>{action}</td></tr>)}</tbody></table></div>
      <h3>Java 线程复用：失败请求之后，下一请求还能读到谁？</h3>
      <p>下载包内的 Java 示例用单线程池保证 Worker 复用。先验证未清理会残留 tenant-A，再验证 finally 清理后异常继续上抛、下一任务无旧身份，tenant-B 可正常绑定。</p>
      <pre><code>{`TENANT.set(tenant);\ntry {\n    return action.call();\n} finally {\n    TENANT.remove();\n}`}</code></pre>
      <p>这个同步示例不能代替异步身份传播，也不承诺 JVM OOM 后继续运行。异步任务需要在实际执行线程建立并清理自身作用域。</p>
      <a href="/runtime-security/ThreadLocalIsolationDemo.java">查看完整可运行 Java 示例</a>
    </details>

    <details id="runtime-security-checker"><summary>离线核对演示：版本已升级，为什么仍有待办？</summary>
      <p>以下输入是示例声明，只在浏览器内计算。Linux 示例假设未加载 Brotli；真实资产必须核对实际依赖。规则截止 {policyDate}，不扫描服务器。</p>
      <div className="runtime-security-form">
        <label>Web PHP 版本<input value={phpVersion} onChange={event => setVersion(event.target.value)} placeholder="8.4.27" autoComplete="off"/></label>
        <label>安装来源<select value={distribution} onChange={event => setDistribution(event.target.value)}><option value="upstream">上游原版</option><option value="vendor">发行版 / 供应商回补</option><option value="unknown">尚未确认</option></select></label>
        <label>示例平台<select value={platform} onChange={event => setPlatform(event.target.value)}><option value="linux">Linux（未加载 Brotli 示例）</option><option value="windows">Windows（加载 Brotli 示例）</option></select></label>
      </div>
      <div className="runtime-security-checkboxes">
        <label><input type="checkbox" checked={uploadProgressEnabled} onChange={event => setUpload(event.target.checked)}/>上传进度仍开启</label>
        <label><input type="checkbox" checked={workersReplaced} onChange={event => setWorkers(event.target.checked)}/>声明全部旧 Worker 已替换</label>
        {platform === 'windows' && <label><input type="checkbox" checked={windowsRebuilt} onChange={event => setRebuilt(event.target.checked)}/>声明使用对应官方 -1 重打包</label>}
      </div>
      <div className="runtime-security-result" aria-live="polite"><strong>{result.status === 'covered' ? '提供的记录满足本清单规则' : statusLabel[result.status]}</strong><p>{result.scope}</p>
        {result.findings.map(finding => <article key={finding.id} data-status={finding.status}><span>{statusLabel[finding.status]}</span><h4>{finding.title}</h4><p>{finding.detail}</p></article>)}
      </div>
    </details>

    <details id="runtime-security-evidence"><summary>已执行的验证与源代码</summary>
      <p>本地记录：{evidence.ruleTests} 项版本规则、{evidence.guardTests} 项异步与资源保护、{evidence.releaseGateTests} 项交接规则测试通过；Python {evidence.python.tests} 项、C++ {evidence.cpp.safeChecks} 项及 Java 示例通过。含待办 / 满足规则 / 错误输入的命令行退出码为 {evidence.cliExitCodes.mixed} / {evidence.cliExitCodes.accepted} / {evidence.cliExitCodes.invalid}。</p>
      <p>未运行 PHP 引擎利用或生产修复验收；配置片段需在目标环境验证。源码摘要、验证时间与范围见下载记录。</p>
      <pre><code>{`node --test php-policy.test.mjs\nnode assess.mjs inventory.example.json\n# 含待办示例预期退出 2\nnode verify.mjs\n# 完整验证需 Java、Python 3.7+、支持 ASan 的 clang++`}</code></pre>
      <nav aria-label="源码下载"><a href="/runtime-security/source.zip">完整源码包</a><a href="/runtime-security/php-policy.mjs">页面共用规则</a><a href="/runtime-security/evidence.json">验证记录与摘要</a></nav>
    </details>
    <details><summary>原始公告与跨语言依据</summary><ul>{sources.map(source => <li key={source.url}><a href={source.url} target="_blank" rel="noreferrer">{source.title} ↗</a></li>)}</ul></details>
  </section>;
}
