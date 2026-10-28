/**
 * PHP 2026-10 公告的离线核对规则。浏览器与命令行共用此文件。
 * 这里只处理提供的资产记录，不读取服务器、不探测漏洞，也不把版本比较当作安全证明。
 * 发行版可能回补补丁，因此 vendor 记录始终交给供应商公告与包证据核验。
 */
export const policyDate = '2026-10-29';
export const fixedBranches = Object.freeze({ '8.2': 35, '8.3': 36, '8.4': 27, '8.5': 12 });
export const sources = Object.freeze([
  { title: 'PHP 10 月公告与后续更新', url: 'https://www.php.net/archive/2026.php' },
  { title: 'OOM 状态损坏：官方技术公告', url: 'https://github.com/php/php-src/security/advisories/GHSA-w8hw-w9fx-j79w' },
  { title: '跨请求残留状态：官方技术公告', url: 'https://github.com/php/php-src/security/advisories/GHSA-6wmf-4rv5-p3rp' },
  { title: 'PHP 上传进度执行时机', url: 'https://www.php.net/manual/en/session.upload-progress.php' },
  { title: 'Java ThreadLocal 生命周期', url: 'https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ThreadLocal.html' },
  { title: 'Python/C API 引用所有权', url: 'https://docs.python.org/3/c-api/intro.html#reference-counts' },
  { title: 'Node.js 原生扩展', url: 'https://nodejs.org/api/addons.html' },
  { title: 'Go 类型安全与 C 互操作', url: 'https://go.dev/doc/faq#IsGoTypeSafe' },
  { title: 'Rust FFI 边界', url: 'https://doc.rust-lang.org/nomicon/ffi.html' },
]);

/** @typedef {'action' | 'verify' | 'covered' | 'not-observed'} FindingStatus */
/** @typedef {{ id: string, status: FindingStatus, title: string, detail: string }} Finding */

function version(text) {
  if (typeof text !== 'string') return null;
  const match = /^(\d+)\.(\d+)\.(\d+)$/.exec(text);
  if (!match) return null;
  const parts = match.slice(1).map(Number);
  return parts.every(Number.isSafeInteger) ? parts : null;
}

/**
 * 每项只给出该公告范围内的判断；缺失配置与未知版本分支必须返回待核验。
 * @returns {{ policyDate: string, status: 'action' | 'verify' | 'covered', findings: Finding[], scope: string }}
 */
export function assessPhpAsset(asset = {}) {
  if (asset === null || typeof asset !== 'object' || Array.isArray(asset)) throw new TypeError('资产记录必须是对象');
  /** @type {Finding[]} */
  const findings = [];
  /** @param {string} id @param {FindingStatus} status @param {string} title @param {string} detail */
  const add = (id, status, title, detail) => findings.push({ id, status, title, detail });
  const parsed = version(asset.phpVersion);
  const branch = parsed ? `${parsed[0]}.${parsed[1]}` : null;
  const floor = fixedBranches[branch];
  if (asset.distribution === 'vendor') {
    add('core', 'verify', '核对供应商回补', '使用发行版安全公告、完整包修订号和镜像摘要核验两项核心补丁；不按较低的上游版本号直接认定未修复。');
  } else if (asset.distribution !== 'upstream' || !parsed) {
    add('core', 'verify', '版本或来源未确认', '需要实际 Web SAPI 的版本、软件来源和包修订；预发布号、发行版后缀不能直接当作正式上游版本。');
  } else if (floor !== undefined) {
    add('core', parsed[2] >= floor ? 'covered' : 'action', '两项核心 RCE 的补丁基线',
      parsed[2] >= floor ? '版本记录满足该分支 10 月 21 日补丁下限；仍需核对实际进程和完整性。' : `${branch} 分支至少升级到 ${branch}.${floor}，或核实供应商已回补。`);
  } else if (parsed[0] < 8 || (parsed[0] === 8 && parsed[1] <= 1)) {
    add('core', 'action', '上游停止维护分支', '迁移受支持分支；保留旧分支时必须取得维护方的回补与支持证据。');
  } else {
    add('core', 'verify', '超出本规则版本范围', '本规则只记录 8.2 至 8.5 的公告基线，不推断新分支或后续漏洞状态。');
  }

  add('upload-progress', asset.uploadProgressEnabled === false ? 'covered' : asset.uploadProgressEnabled === true ? 'action' : 'verify',
    '10 月 23 日会话配置补充', asset.uploadProgressEnabled === false
      ? '记录为关闭上传进度；需要启动配置与全部新 Worker 的证据，应用内 ini_set 不足以替代。'
      : '按官方建议临时关闭 session.upload_progress.enabled。115560 另有罕见的运行时序列化器切换前提；不是所有开启此功能的应用都已确认可利用。');

  const build = typeof asset.windowsBuild === 'string' ? /^(8\.[2345]\.\d+)-1$/.exec(asset.windowsBuild) : null;
  const knownWindowsRebuild = asset.platform === 'windows' && asset.distribution === 'upstream' && build
    && build[1] === asset.phpVersion && floor !== undefined && parsed[2] === floor;
  const unknownWindowsRebuild = asset.platform === 'windows' && typeof asset.windowsBuild === 'string'
    && asset.windowsBuild !== asset.phpVersion && !knownWindowsRebuild;
  const brotli = version(asset.brotliVersion);
  const brotliFixed = brotli && (brotli[0] > 1 || (brotli[0] === 1 && (brotli[1] > 2 || (brotli[1] === 2 && brotli[2] >= 1))));
  if (knownWindowsRebuild || brotliFixed) {
    add('brotli', 'covered', 'Brotli 依赖补丁', '记录满足 1.2.1 基线或官方同版本 Windows -1 重打包；验收仍应核对下载来源、摘要及实际加载库。');
  } else if (asset.brotliLoaded === false) {
    add('brotli', 'not-observed', '未记录加载 Brotli', '此资产记录未发现对应调用路径；不是对整个主机或依赖树的扫描结论。');
  } else {
    add('brotli', asset.distribution === 'vendor' || unknownWindowsRebuild ? 'verify' : brotli || (asset.platform === 'windows' && parsed && floor !== undefined && parsed[2] === floor) ? 'action' : 'verify',
      '核对 Brotli 与 Windows 包修订', 'Windows 核心版本不变也可能需要 -1 重打包；其他平台核对实际加载的 Brotli。关闭自动解压只能减少暴露面。');
  }
  add('worker-rollout', asset.workersReplaced === true ? 'covered' : asset.workersReplaced === false ? 'action' : 'verify',
    '确认全部 Worker 已替换', asset.workersReplaced === true
      ? '记录声明已替换；请保留进程代际、包摘要和流量切换证据。' : '安装文件后仍要替换旧 Web Worker、队列消费者与长驻进程，并核对实际生效配置。');
  const status = findings.some(f => f.status === 'action') ? 'action' : findings.some(f => f.status === 'verify') ? 'verify' : 'covered';
  return { policyDate, status, findings, scope: '离线公告核对，不是主机扫描、漏洞利用验证或完整安全认证。' };
}

/**
 * 用可控异常表示“对象尚未初始化就被公开”的失败路径，不制造真实 OOM 或无效指针。
 * safe 路径先构造临时上下文，成功后才发布；结束时先撤销引用，再释放请求状态。
 * 这仅演示生命周期约束，不模拟 PHP 补丁的 VM 安全点与硬 OOM 退出。
 */
export function demonstrateLifecycle({ safe = true, failInitialization = true } = {}) {
  let published = null;
  let allocated = null;
  const events = [];
  try {
    allocated = { ready: false, released: false, request: 'A' };
    if (!safe) published = allocated;
    events.push({ phase: '分配', published: Boolean(published), ready: false });
    if (failInitialization) throw new Error('受控初始化失败');
    allocated.ready = true;
    published = allocated;
    events.push({ phase: '初始化完成后发布', published: true, ready: true });
  } catch {
    events.push({ phase: '失败路径', published: Boolean(published), ready: false });
  } finally {
    if (safe) published = null;
    if (allocated) allocated.released = true;
    events.push({ phase: '请求结束', published: Boolean(published), ready: allocated?.ready ?? false });
  }
  const staleState = Boolean(published?.released);
  events.push({ phase: '同一 Worker 接收请求 B', published: Boolean(published), staleState });
  return { safe, events, staleState, invariantHeld: !staleState };
}
