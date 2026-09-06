import { createHash } from 'node:crypto';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, lstatSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, isAbsolute, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual } from 'node:util';

// 此工具不是操作系统沙箱。批准后会执行候选 Java，不能阻止它联网或读写文件。
// 不可信候选只能在公司批准的隔离环境运行；本工具自身不调用网络或模型 API。
const root = dirname(fileURLToPath(import.meta.url));
const workshop = join(root, 'workshop');
const generated = mkdtempSync(join(tmpdir(), 'ticket-candidate-verification-'));
const reportPath = join(generated, 'report.json');
const report = {
  schemaVersion: 1, status: 'not_started', mode: null,
  sampleOnly: true, verifierInvokedModel: false,
  generatedAt: new Date().toISOString(),
  candidate: { file: 'TicketQuery.java', sha256: null },
  execution: { approved: false, node: process.version, target: 'local_java_process',
    osSandbox: false, networkIsolationEnforced: false, externalEffectsMonitored: false, candidateCompiled: false },
  trustedHashes: {}, results: [],
  scope: { satisfied: false, changedNonTargetIds: [], limitation: '只验证固定样例行为，不证明源码改动范围或未覆盖行为。' },
};

class VerificationError extends Error {
  constructor(code, message) { super(message); this.code = code; }
}
function fail(code, message) { throw new VerificationError(code, message); }
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
function trustedFile(relative) {
  let bytes;
  try { bytes = readFileSync(join(workshop, relative)); }
  catch { fail('TRUSTED_INPUT_MISSING', `缺少受信工件 workshop/${relative}`); }
  report.trustedHashes[`workshop/${relative}`] = hash(bytes);
  return bytes;
}
function run(command, args, label, cwd = generated) {
  const result = spawnSync(command, args, {
    encoding: 'utf8', cwd, timeout: 30_000, maxBuffer: 1_048_576,
  });
  if (result.error || result.status !== 0) fail('PROCESS_FAILED', `${label}失败，不计作通过或预期红`);
  return { stdout: result.stdout.trim(), stderr: result.stderr.trim() };
}
function selectJdk(configured) {
  if (!configured && process.platform === 'darwin') {
    const found = spawnSync('/usr/libexec/java_home', ['-v', '21'], { encoding: 'utf8', timeout: 10_000 });
    if (!found.error && found.status === 0) configured = found.stdout.trim();
  }
  const suffix = process.platform === 'win32' ? '.exe' : '';
  const executable = name => configured ? join(configured, 'bin', name + suffix) : name;
  const java = executable('java');
  const javac = executable('javac');
  const javaOutput = run(java, ['-version'], 'JDK 运行环境检查');
  const javacOutput = run(javac, ['-version'], 'JDK 编译环境检查');
  const javaVersion = `${javaOutput.stdout}\n${javaOutput.stderr}`.match(/version "(\d+(?:\.\d+)*)/u)?.[1];
  const javacVersion = `${javacOutput.stdout}\n${javacOutput.stderr}`.match(/javac (\d+(?:\.\d+)*)/u)?.[1];
  if (javaVersion?.split('.')[0] !== '21' || javacVersion?.split('.')[0] !== '21') {
    fail('JDK_VERSION', '需要 JDK 21，可通过 --jdk 或 WORKSHOP_JDK 指定');
  }
  report.execution.java = javaVersion;
  report.execution.javac = javacVersion;
  return { java, javac };
}
function parseArguments() {
  const [mode, candidatePath, ...options] = process.argv.slice(2);
  if (!['implement', 'repair'].includes(mode) || !candidatePath
      || !isAbsolute(candidatePath) || basename(candidatePath) !== 'TicketQuery.java') {
    fail('USAGE', '用法：node verify-candidate.mjs implement|repair /绝对路径/TicketQuery.java --approve-local-execution [--jdk JDK目录]');
  }
  let approved = false;
  let jdk = process.env.WORKSHOP_JDK;
  for (let i = 0; i < options.length; i++) {
    if (options[i] === '--approve-local-execution' && !approved) approved = true;
    else if (options[i] === '--jdk' && options[i + 1] && !options[i + 1].startsWith('--')) jdk = options[++i];
    else fail('USAGE', '存在未知、重复或不完整参数');
  }
  report.mode = mode;
  report.execution.approved = approved;
  if (!approved) fail('APPROVAL_REQUIRED', '尚未批准本地执行；不会编译或运行候选。此工具不是安全沙箱。');
  return { mode, candidatePath, jdk };
}
function decodeBase64(value) {
  if (typeof value !== 'string' || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/u.test(value)) {
    fail('PROBE_PROTOCOL', '探针返回的编码无效');
  }
  return Buffer.from(value, 'base64').toString('utf8');
}
function decodeActual(output) {
  let value;
  try { value = JSON.parse(output); } catch { fail('PROBE_PROTOCOL', '探针未返回唯一且有效的结果'); }
  if (!value || typeof value !== 'object') fail('PROBE_PROTOCOL', '探针结果不是对象');
  if (value.kind === 'ok' && Object.keys(value).sort().join(',') === 'kind,offset,page,queryBase64,size'
      && Number.isInteger(value.page) && Number.isInteger(value.size)
      && typeof value.offset === 'string' && /^-?\d+$/u.test(value.offset)) {
    return { kind: 'ok', page: value.page, size: value.size, offset: value.offset,
      query: decodeBase64(value.queryBase64) };
  }
  if (value.kind === 'error' && value.type === 'IllegalArgumentException'
      && Object.keys(value).sort().join(',') === 'kind,messageBase64,type') {
    return { kind: 'error', type: value.type, message: decodeBase64(value.messageBase64) };
  }
  fail('PROBE_PROTOCOL', '探针结果结构不符合受信合同');
}
function compile(jdk, source, probe, name) {
  const folder = join(generated, name);
  const sources = join(folder, 'source');
  const classes = join(folder, 'classes');
  const empty = join(folder, 'empty');
  for (const directory of [sources, classes, empty]) mkdirSync(directory, { recursive: true });
  // 只复制已读取并计算摘要的单个候选和受信探针，不发现候选旁边的测试或源文件。
  writeFileSync(join(sources, 'TicketQuery.java'), source);
  writeFileSync(join(sources, 'QueryProbe.java'), probe);
  run(jdk.javac, ['--release', '21', '-encoding', 'UTF-8', '-proc:none',
    '-sourcepath', empty, '-classpath', empty, '-d', classes,
    join(sources, 'TicketQuery.java'), join(sources, 'QueryProbe.java')], `${name} 编译`, folder);
  return { folder, classes };
}
function observe(jdk, compiled, fixture, label) {
  const { page, size, query } = fixture.input;
  const encoded = query === null ? '@null' : Buffer.from(query, 'utf8').toString('base64');
  const output = run(jdk.java, ['-cp', compiled.classes, 'QueryProbe', String(page), String(size), encoded], `${label}/${fixture.id} 执行`, compiled.folder);
  if (output.stderr) fail('PROBE_PROTOCOL', `${label}/${fixture.id} 有额外标准错误输出，结果需人工检查`);
  return decodeActual(output.stdout);
}

try {
  const { mode, candidatePath, jdk: configured } = parseArguments();
  if (Number(process.versions.node.split('.')[0]) < 24) fail('NODE_VERSION', '需要 Node 24 或更新版本');
  console.error('执行提醒：本程序不是操作系统沙箱，不能阻止候选联网或访问文件；请使用公司隔离环境。');
  let source;
  try {
    const info = lstatSync(candidatePath);
    if (!info.isFile() || info.isSymbolicLink() || info.size > 128_000) {
      fail('CANDIDATE_UNREADABLE', '候选必须是至多 128 KB 的普通 Java 文件');
    }
    source = readFileSync(candidatePath);
  } catch { fail('CANDIDATE_UNREADABLE', '无法读取合规的候选 TicketQuery.java'); }
  report.candidate.sha256 = hash(source);
  report.trustedHashes['verify-candidate.mjs'] = hash(readFileSync(fileURLToPath(import.meta.url)));
  const probe = trustedFile('tests/QueryProbe.java');
  let cases;
  try { cases = JSON.parse(trustedFile('tests/cases.json').toString('utf8')); }
  catch (error) { if (error instanceof VerificationError) throw error; fail('TRUSTED_INPUT_INVALID', '受信用例不是有效 JSON'); }
  if (!Array.isArray(cases) || cases.length !== 12
      || !cases.every((c, index) => c.id === `P${String(index + 1).padStart(2, '0')}`
        && Number.isInteger(c.input?.page) && Number.isInteger(c.input?.size)
        && (c.input.query === null || typeof c.input.query === 'string') && c.expected)) {
    fail('TRUSTED_INPUT_INVALID', '受信用例必须包含按顺序排列的 P01–P12 及完整输入预期');
  }
  const overflow = cases.find(c => c.id === 'P03');
  if (overflow.input.page !== 2147483647 || overflow.input.size !== 200
      || !isDeepStrictEqual(overflow.expected, { kind: 'ok', page: 2147483647, size: 200, offset: '429496729400', query: 'urgent' })) {
    fail('TRUSTED_INPUT_INVALID', 'P03 的独立溢出合同发生漂移');
  }
  const jdk = selectJdk(configured);
  let baseline = null;
  if (mode === 'repair') {
    const compiled = compile(jdk, trustedFile('before/TicketQuery.java'), probe, 'trusted-before');
    baseline = cases.map(c => observe(jdk, compiled, c, 'trusted-before'));
    if (!isDeepStrictEqual(baseline[2], { ...overflow.expected, offset: '-200' })) {
      fail('BASELINE_DRIFT', '受信旧版未准确复现 P03 的 -200 溢出，不能建立修复结论');
    }
    report.trustedBefore = { total: cases.length,
      contractPassed: baseline.filter((actual, index) => isDeepStrictEqual(actual, cases[index].expected)).length,
      overflowReproduced: true };
  }
  const candidate = compile(jdk, source, probe, 'candidate');
  report.execution.candidateCompiled = true;
  for (const [index, fixture] of cases.entries()) {
    const actual = observe(jdk, candidate, fixture, 'candidate');
    // 完整实现采用字面合同；单缺陷修复只有 P03 用新合同，其他项必须保持受信旧版实际行为。
    const expected = mode === 'repair' && fixture.id !== 'P03' ? baseline[index] : fixture.expected;
    const pass = isDeepStrictEqual(actual, expected);
    report.results.push({ id: fixture.id, name: fixture.name, input: fixture.input,
      expected, actual, pass, contractExpected: fixture.expected,
      contractPass: isDeepStrictEqual(actual, fixture.expected),
      ...(baseline ? { beforeActual: baseline[index] } : {}) });
  }
  const passed = report.results.filter(r => r.pass).length;
  const contractPassed = report.results.filter(r => r.contractPass).length;
  report.summary = { total: 12, taskPassed: passed, taskFailed: 12 - passed,
    contractPassed, contractFailed: 12 - contractPassed };
  report.scope.requirement = mode === 'implement' ? 'full_contract' : 'only_P03_changes';
  report.scope.changedNonTargetIds = mode === 'repair'
    ? report.results.filter(r => r.id !== 'P03' && !isDeepStrictEqual(r.actual, r.beforeActual)).map(r => r.id) : [];
  report.scope.satisfied = passed === 12;
  report.status = report.scope.satisfied ? 'verified' : 'candidate_rejected';
  if (!report.scope.satisfied) process.exitCode = 1;
  console.log(`候选任务验收：${passed}/12；完整合同：${contractPassed}/12；范围满足：${report.scope.satisfied ? '是' : '否'}。`);
} catch (error) {
  const known = error instanceof VerificationError;
  const code = known ? error.code : 'UNEXPECTED_ERROR';
  report.status = code === 'APPROVAL_REQUIRED' ? 'execution_not_approved'
    : code === 'USAGE' ? 'usage_error' : 'infrastructure_error';
  // 不把环境或编译失败折算成候选的普通测试失败，也不记录本机路径与原始错误输出。
  report.error = { code, message: known ? error.message : '运行异常，请人工检查隔离环境和受信工件', countsAsExpectedFailure: false };
  process.exitCode = 1;
  console.error(report.error.message);
} finally {
  writeFileSync(reportPath, JSON.stringify(report, null, 2) + '\n', 'utf8');
  console.log(`REPORT_PATH=${reportPath}`);
}
