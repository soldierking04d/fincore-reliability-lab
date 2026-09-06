import { createHash } from 'node:crypto';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual } from 'node:util';

// 只有编译及运行成功后的断言失败才算旧版预期红；环境失败一律退出并报告。
const root = dirname(fileURLToPath(import.meta.url));
const generated = mkdtempSync(join(tmpdir(), 'ai-pagination-workshop-'));
const reportPath = join(generated, 'report.json');
const report = {
  schemaVersion: 1,
  status: 'running',
  sampleOnly: true,
  representsProductionDefect: false,
  cursorWasInvoked: false,
  generatedAt: new Date().toISOString(),
  execution: { node: process.version, target: 'local_java_process', externalCalls: 0 },
  sourceHashes: {},
  variants: [],
};

function run(command, args, label) {
  const result = spawnSync(command, args, { encoding: 'utf8', timeout: 30_000, maxBuffer: 1_048_576 });
  if (result.error || result.status !== 0) {
    // 报告只保留相对来源名和固定错误标签，避免记录本机私有绝对路径。
    throw new Error(`${label}失败；未计入预期红结果`);
  }
  return `${result.stdout ?? ''}${result.stderr ?? ''}`.trim();
}

function selectJdk() {
  const args = process.argv.slice(2);
  if (args.length !== 0 && !(args.length === 2 && args[0] === '--jdk' && args[1])) {
    throw new Error('参数错误：仅支持 node run-workshop.mjs [--jdk JDK目录]');
  }
  let configured = args[1] ?? process.env.WORKSHOP_JDK;
  if (!configured && process.platform === 'darwin') {
    const found = spawnSync('/usr/libexec/java_home', ['-v', '21'], { encoding: 'utf8', timeout: 10_000 });
    if (!found.error && found.status === 0) configured = found.stdout.trim();
  }
  const suffix = process.platform === 'win32' ? '.exe' : '';
  const command = name => configured ? join(configured, 'bin', name + suffix) : name;
  const java = command('java');
  const javac = command('javac');
  const javaInfo = run(java, ['-version'], 'Java 环境检查');
  const javacInfo = run(javac, ['-version'], 'Java 编译器检查');
  const javaVersion = javaInfo.match(/version "(\d+(?:\.\d+)*)/u)?.[1];
  const javacVersion = javacInfo.match(/javac (\d+(?:\.\d+)*)/u)?.[1];
  if (javaVersion?.split('.')[0] !== '21' || javacVersion?.split('.')[0] !== '21') {
    throw new Error('需要可运行的 JDK 21；请用 --jdk 或 WORKSHOP_JDK 指定，不会下载依赖');
  }
  report.execution.java = javaVersion;
  report.execution.javac = javacVersion;
  return { java, javac };
}

function decodeActual(output) {
  const raw = JSON.parse(output);
  if (raw.kind === 'ok' && Object.keys(raw).sort().join(',') === 'kind,offset,page,queryBase64,size') {
    if (!Number.isInteger(raw.page) || !Number.isInteger(raw.size)
        || typeof raw.offset !== 'string' || !/^-?\d+$/u.test(raw.offset)
        || typeof raw.queryBase64 !== 'string') throw new Error('探针返回值类型无效');
    return { kind: raw.kind, page: raw.page, size: raw.size, offset: raw.offset,
      query: Buffer.from(raw.queryBase64, 'base64').toString('utf8') };
  }
  if (raw.kind === 'error' && raw.type === 'IllegalArgumentException'
      && Object.keys(raw).sort().join(',') === 'kind,messageBase64,type' && typeof raw.messageBase64 === 'string') {
    return { kind: raw.kind, type: raw.type, message: Buffer.from(raw.messageBase64, 'base64').toString('utf8') };
  }
  throw new Error('探针返回结构无效；不能计作测试断言失败');
}

try {
  if (Number(process.versions.node.split('.')[0]) < 24) throw new Error('需要 Node 24 或更新版本');
  const { java, javac } = selectJdk();
  const files = ['before/TicketQuery.java', 'after/TicketQuery.java', 'overflow-only/TicketQuery.java', 'tests/QueryProbe.java', 'tests/cases.json', 'run-workshop.mjs', 'solution.patch', 'overflow-only.patch'];
  for (const relative of files) {
    report.sourceHashes[relative] = createHash('sha256').update(readFileSync(join(root, relative))).digest('hex');
  }
  const cases = JSON.parse(readFileSync(join(root, 'tests/cases.json'), 'utf8'));
  if (!Array.isArray(cases) || cases.length < 8 || cases.length > 12
      || new Set(cases.map(c => c.id)).size !== cases.length || !cases.some(c => c.baseline)) {
    throw new Error('独立用例文件无效：需 8 至 12 个不同编号，并包含正常基线');
  }
  for (const variant of ['before', 'after', 'overflow-only']) {
    const classes = join(generated, variant, 'classes');
    mkdirSync(classes, { recursive: true });
    run(javac, ['--release', '21', '-encoding', 'UTF-8', '-d', classes,
      join(root, variant, 'TicketQuery.java'), join(root, 'tests/QueryProbe.java')], `${variant} 编译`);
    const results = [];
    for (const fixture of cases) {
      const { page, size, query } = fixture.input;
      const encoded = query === null ? '@null' : Buffer.from(query, 'utf8').toString('base64');
      const output = run(java, ['-cp', classes, 'QueryProbe', String(page), String(size), encoded], `${variant}/${fixture.id} 执行`);
      const actual = decodeActual(output);
      results.push({ id: fixture.id, name: fixture.name, baseline: fixture.baseline,
        input: fixture.input, expected: fixture.expected, actual, pass: isDeepStrictEqual(actual, fixture.expected) });
    }
    const passed = results.filter(r => r.pass).length;
    report.variants.push({ variant, compiled: true, total: results.length,
      passed, failed: results.length - passed, baselinePassed: results.filter(r => r.baseline && r.pass).length, results });
  }
  const [before, after, overflowOnly] = report.variants;
  const overflowRows = [before, after, overflowOnly].map(v => v.results.find(r => r.id === 'P03'));
  report.contract = {
    beforeHasRealFailure: before.failed > 0,
    beforeNormalBaselinePasses: before.baselinePassed > 0,
    afterAllPass: after.failed === 0,
    sameCasesForBothVariants: isDeepStrictEqual(before.results.map(r => [r.id, r.input, r.expected]), after.results.map(r => [r.id, r.input, r.expected])),
    // 明确检查目标缺陷；其他失败再多也不能代替溢出复现。
    overflowReproducedExactly: overflowRows.every(r => r && r.input.page === 2147483647
      && r.input.size === 200 && r.expected.offset === '429496729400')
      && overflowRows[0].actual.offset === '-200' && !overflowRows[0].pass
      && overflowRows.slice(1).every(r => r.actual.offset === '429496729400' && r.pass),
    // 单缺陷任务只改变 P03；其余表现必须与旧版一致，不冒充全合同实现。
    surgicalFixOnlyChangesOverflow: overflowOnly.results.every((r, index) => r.id === 'P03'
      ? r.pass && !before.results[index].pass
      : isDeepStrictEqual(r.actual, before.results[index].actual)),
  };
  report.status = Object.values(report.contract).every(Boolean) ? 'demonstration_verified' : 'verification_failed';
  if (report.status !== 'demonstration_verified') process.exitCode = 1;
  console.log(`旧版：${before.passed}/${before.total} 通过，${before.failed} 项真实断言失败。`);
  console.log(`修复版：${after.passed}/${after.total} 通过。`);
  console.log(`单缺陷版：${overflowOnly.passed}/${overflowOnly.total} 通过，仅改变溢出用例，其余行为与旧版一致。`);
} catch (error) {
  report.status = 'infrastructure_error';
  const message = (error instanceof Error ? error.message : '未知运行错误')
    .replaceAll(root, '<workshop>').replaceAll(generated, '<temporary>');
  report.error = { message, countsAsExpectedFailure: false };
  process.exitCode = 1;
  console.error(`运行失败：${report.error.message}`);
} finally {
  // 编译文件和报告均留在新建临时目录；不向源码目录生成 class 或运行报告。
  writeFileSync(reportPath, JSON.stringify(report, null, 2) + '\n', 'utf8');
  console.log(`JSON 报告：${reportPath}`);
}
