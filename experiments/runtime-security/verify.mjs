import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { assessPhpAsset, policyDate } from './php-policy.mjs';
import { observeConcurrentTenants, decodeGzipBounded } from './runtime-guards.mjs';
import { gzipSync, gunzipSync } from 'node:zlib';

const cwd = fileURLToPath(new URL('.', import.meta.url));
const temporary = await mkdtemp(path.join(tmpdir(), 'fincore-runtime-security-'));
try {
  function testFile(file) {
    const result = execFileSync(process.execPath, ['--test', '--test-reporter=tap', file], { cwd, encoding: 'utf8', timeout: 20000 });
    const count = Number(/# tests (\d+)/.exec(result)?.[1]);
    if (!count || !/# fail 0\b/.test(result)) throw Error(`${file}: 测试结果未确认`);
    return count;
  }
  const count = testFile('php-policy.test.mjs');
  const guardTests = testFile('runtime-guards.test.mjs');
  const releaseGateTests = testFile('release-gates.test.mjs');
  const assets = JSON.parse(await readFile(path.join(cwd, 'inventory.example.json'), 'utf8'));
  const mixed = spawnSync(process.execPath, ['assess.mjs', 'inventory.example.json'], { cwd, encoding: 'utf8' });
  if (mixed.status !== 2) throw Error('含待办的清单必须退出 2');
  const acceptedFile = path.join(temporary, 'accepted.json');
  await writeFile(acceptedFile, JSON.stringify([assets[2]]));
  const accepted = spawnSync(process.execPath, ['assess.mjs', acceptedFile], { cwd, encoding: 'utf8' });
  const invalid = spawnSync(process.execPath, ['assess.mjs'], { cwd, encoding: 'utf8' });
  if (accepted.status !== 0 || invalid.status !== 64) throw Error('清单退出码契约失败');
  execFileSync('javac', ['-encoding', 'UTF-8', '-d', temporary, 'ThreadLocalIsolationDemo.java'], { cwd, encoding: 'utf8' });
  const java = execFileSync('java', ['-cp', temporary, 'ThreadLocalIsolationDemo'], { cwd, encoding: 'utf8' }).trim();
  if (!java.startsWith('PASS:')) throw Error('Java 隔离示例未通过');

  // 只尝试明确的解释器入口，不安装依赖。macOS 默认 PATH 可能仍指向 3.6，需核对最低版本。
  let pythonCommand;
  let pythonVersion;
  for (const candidate of process.env.RUNTIME_SECURITY_PYTHON ? [process.env.RUNTIME_SECURITY_PYTHON] : ['python3', '/usr/bin/python3']) {
    const probe = spawnSync(candidate, ['--version'], { encoding: 'utf8', timeout: 5000 });
    const match = /Python (\d+)\.(\d+)\.\d+/.exec(`${probe.stdout ?? ''}${probe.stderr ?? ''}`);
    if (probe.status === 0 && match && (+match[1] > 3 || (+match[1] === 3 && +match[2] >= 7))) {
      pythonCommand = candidate; pythonVersion = match[0]; break;
    }
  }
  if (!pythonCommand) throw Error('Python 实验需要已有 Python 3.7+，可通过 RUNTIME_SECURITY_PYTHON 指定');
  const python = JSON.parse(execFileSync(pythonCommand, ['context_isolation.py'], { cwd, encoding: 'utf8', timeout: 15000 }));
  if (python.passed !== true) throw Error('Python 上下文实验失败');

  const nativeBinary = path.join(temporary, 'request-lifetime');
  execFileSync(process.env.RUNTIME_SECURITY_CXX ?? 'clang++', ['-std=c++17', '-O0', '-g', '-fsanitize=address', '-fno-omit-frame-pointer', 'request_lifetime.cpp', '-o', nativeBinary], { cwd, encoding: 'utf8', timeout: 60000 });
  const nativeEnv = { ...process.env, ASAN_OPTIONS: 'abort_on_error=0:exitcode=23:detect_leaks=0:symbolize=0' };
  const unsafe = spawnSync(nativeBinary, ['unsafe'], { cwd, env: nativeEnv, encoding: 'utf8', timeout: 10000, maxBuffer: 1024 * 1024 });
  if (unsafe.status === null || unsafe.status === 0 || !/AddressSanitizer: heap-use-after-free/.test(unsafe.stderr)) throw Error('C++ 负例未被 ASan 明确检测');
  const cpp = JSON.parse(execFileSync(nativeBinary, [], { cwd, env: nativeEnv, encoding: 'utf8', timeout: 10000 }));
  if (cpp.passed !== true) throw Error('C++ 所有权修正示例失败');

  const compressed = gzipSync(Buffer.alloc(128 * 1024, 'A'));
  const unboundedBytes = gunzipSync(compressed).length;
  let boundedCode;
  try { await decodeGzipBounded([compressed], { outputLimit: 32768 }); } catch (error) { boundedCode = error.code; }
  if (boundedCode !== 'OUTPUT_LIMIT') throw Error('输出预算未生效');
  const nodeContext = { negative: await observeConcurrentTenants({ isolated: false }), fixed: await observeConcurrentTenants({ isolated: true }) };

  const hashFiles = ['php-policy.mjs', 'php-policy.test.mjs', 'assess.mjs', 'inventory.example.json', 'ThreadLocalIsolationDemo.java', 'verify.mjs',
    'runtime-guards.mjs', 'runtime-guards.test.mjs', 'context_isolation.py', 'request_lifetime.cpp', 'release-gates.mjs', 'release-gates.test.mjs', 'casebook.json'];
  const sourceSha256 = Object.fromEntries(await Promise.all(hashFiles.map(async file => [file, createHash('sha256').update(await readFile(path.join(cwd, file))).digest('hex')])));
  console.log(JSON.stringify({ verifiedAt: new Date().toISOString(), policyDate, passed: true, ruleTests: count, guardTests, releaseGateTests,
    cliExitCodes: { mixed: mixed.status, accepted: accepted.status, invalid: invalid.status },
    java: { passed: true, result: java },
    python: { ...python, version: pythonVersion },
    cpp: { ...cpp, asanDetected: true, negativeExit: unsafe.status, scope: 'Local ownership example; not a PHP exploit.' },
    nodeContext,
    decoding: { compressedBytes: compressed.length, unboundedBytes, outputLimit: 32768, boundedCode, scope: 'gzip resource budget example; not a Brotli memory-corruption patch test.' },
    exampleAssets: assets.map(asset => ({ name: asset.name, status: assessPhpAsset(asset).status })),
    sourceSha256, scope: 'Local PHP policy rules, Java/Node/Python context isolation, C++ ASan and gzip budgets; PHP engine and production hosts were not tested.' }, null, 2));
} finally {
  await rm(temporary, { recursive: true, force: true });
}
