/**
 * 演示一次修复交接怎样拒绝不完整证据；输入均为调用方提供的记录，不联系生产环境。
 * 通过只表示记录之间满足约束，不验证记录真实性或供应商签名。
 */
export function evaluateRelease(records) {
  const errors = [];
  if (!records || typeof records !== 'object' || Array.isArray(records)) return { passed: false, errors: ['缺少发布记录'] };
  if (!/^sha256:[a-f0-9]{64}$/.test(records.imageDigest ?? '')) errors.push('缺少可识别的制品摘要');
  if (!Array.isArray(records.expectedWorkers) || records.expectedWorkers.length === 0
      || !records.expectedWorkers.every(id => typeof id === 'string' && id.trim())) errors.push('缺少完整 Worker 范围');
  else if (new Set(records.expectedWorkers).size !== records.expectedWorkers.length) errors.push('Worker 范围重复');
  if (typeof records.generation !== 'string' || !records.generation.trim()) errors.push('缺少目标代际');
  const expectedWorkers = Array.isArray(records.expectedWorkers) ? records.expectedWorkers : [];
  const supplied = Array.isArray(records.workers) ? records.workers : [];
  const samples = supplied.filter(worker => worker && typeof worker === 'object' && typeof worker.id === 'string' && worker.id.trim());
  if (samples.length !== supplied.length) errors.push('Worker 观测包含无效记录');
  if (samples.some(worker => !expectedWorkers.includes(worker.id))) errors.push('出现范围外 Worker，需重新核对实例范围');
  if (new Set(samples.map(worker => worker.id)).size !== samples.length) errors.push('Worker 观测重复');
  for (const id of Array.isArray(records.expectedWorkers) ? records.expectedWorkers : []) {
    const worker = samples.find(sample => sample.id === id);
    if (!worker) { errors.push(`${id}: 缺少 Web 进程观测`); continue; }
    if (!['fpm-fcgi', 'apache2handler', 'cgi-fcgi', 'frankenphp', 'litespeed'].includes(worker.sapi)) errors.push(`${id}: 不能以 CLI 观测代替 Web SAPI`);
    if (worker.generation !== records.generation) errors.push(`${id}: 旧 Worker 仍存在`);
    if (worker.imageDigest !== records.imageDigest) errors.push(`${id}: 制品摘要不一致`);
    if (worker.uploadProgressEnabled !== false) errors.push(`${id}: 前置配置未确认关闭`);
  }
  if (records.patchEvidenceVerified !== true) errors.push('缺少维护方补丁核验记录');
  if (records.rollbackPatched !== true) errors.push('回退制品尚未确认修补');
  return { passed: errors.length === 0, errors };
}
