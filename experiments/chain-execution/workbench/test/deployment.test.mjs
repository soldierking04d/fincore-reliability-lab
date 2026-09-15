import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import http from 'node:http';
import net from 'node:net';
import { mkdtemp, readFile, rm, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, isAbsolute, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const workbench = dirname(dirname(fileURLToPath(import.meta.url)));

async function availablePort() {
  const reservation = net.createServer();
  await new Promise((resolve, reject) => {
    reservation.once('error', reject);
    reservation.listen(0, '127.0.0.1', resolve);
  });
  const port = reservation.address().port;
  await new Promise((resolve, reject) => reservation.close(error => error ? reject(error) : resolve()));
  return port;
}

function ready(child, port) {
  return new Promise((resolve, reject) => {
    let output = '';
    const finish = error => {
      clearTimeout(timer);
      child.stdout.off('data', onData); child.off('exit', onExit); child.off('error', onError);
      error ? reject(error) : resolve();
    };
    const onData = chunk => {
      output = (output + chunk.toString()).slice(-4096);
      if (output.includes(`http://127.0.0.1:${port}/`)) finish();
    };
    const onExit = (code, signal) => finish(new Error(`服务在监听前退出：code=${code}, signal=${signal}`));
    const onError = () => finish(new Error('测试子进程无法启动'));
    const timer = setTimeout(() => finish(new Error('测试服务五秒内未开始监听')), 5000);
    child.stdout.on('data', onData); child.once('exit', onExit); child.once('error', onError);
  });
}

function stop(child) {
  if (!child || child.exitCode !== null || child.signalCode !== null) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const finish = error => {
      clearTimeout(force); clearTimeout(deadline); child.off('exit', onExit);
      error ? reject(error) : resolve();
    };
    const onExit = () => finish();
    const force = setTimeout(() => child.kill('SIGKILL'), 1000);
    const deadline = setTimeout(() => finish(new Error('测试子进程未在清理期限内退出')), 2500);
    child.once('exit', onExit);
    child.kill('SIGTERM');
  });
}

function readConfig(port) {
  return new Promise((resolve, reject) => {
    const request = http.get({ hostname: '127.0.0.1', port, path: '/api/config', agent: false,
      signal: AbortSignal.timeout(2000) }, response => {
      let body = '';
      response.setEncoding('utf8');
      response.on('data', chunk => {
        body += chunk;
        if (body.length > 8192) request.destroy(new Error('配置响应超过测试上限'));
      });
      response.on('error', reject);
      response.on('end', () => {
        try { resolve({ status: response.statusCode, body: JSON.parse(body) }); }
        catch { reject(new Error('配置响应不是有效 JSON')); }
      });
    });
    request.on('error', reject);
  });
}

test('部署入口从 current 符号链接工作目录真实启动，并保持只读配置', { timeout: 12_000 }, async t => {
  const unit = await readFile(join(workbench, 'deploy/fincore-chain-workbench.service'), 'utf8');
  const command = /^ExecStart=(.+)$/m.exec(unit)?.[1].trim().split(/\s+/);
  const workingDirectory = /^WorkingDirectory=(.+)$/m.exec(unit)?.[1].trim();
  assert.ok(command && workingDirectory, '部署单元必须明确入口和工作目录');
  const entry = command.at(-1);
  assert.deepEqual(command.slice(1, -1), ['--max-old-space-size=256']);

  const scratch = await mkdtemp(join(tmpdir(), 'fincore-deployment-test-'));
  let child;
  t.after(async () => {
    try { await stop(child); }
    finally { await rm(scratch, { recursive: true, force: true }); }
  });
  const current = join(scratch, 'current');
  await symlink(workbench, current, 'dir');
  // 将 unit 的绝对 current 路径映射到测试别名：旧配置会真实退出 0，而非只靠字符串断言失败。
  const localEntry = entry.startsWith(`${workingDirectory}/`)
    ? join(current, entry.slice(workingDirectory.length + 1)) : entry;
  const port = await availablePort();
  // 拦截启动代码使用的 Node HTTP / HTTPS / fetch 出站入口；这不是操作系统网络隔离。
  const noOutbound = 'data:text/javascript,' + encodeURIComponent(
    "import https from 'node:https'; import http from 'node:http';"
    + "const deny=()=>{throw new Error('DEPLOYMENT_TEST_OUTBOUND_FORBIDDEN')};"
    + 'https.request=deny;https.get=deny;http.request=deny;http.get=deny;globalThis.fetch=deny;');
  child = spawn(process.execPath, ['--import', noOutbound, ...command.slice(1, -1), localEntry], {
    cwd: current, env: { NODE_ENV: 'production', FINCORE_WORKBENCH_PORT: String(port) },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  child.stderr.resume();
  await ready(child, port);
  const response = await readConfig(port);
  assert.equal(response.status, 200);
  assert.equal(response.body.executionAllowed, false);
  assert.equal(response.body.mode, 'LOCAL_SIMULATION_ONLY');
  assert.equal(response.body.cluster, 'solana:mainnet');
  assert.equal(isAbsolute(entry), false, 'unit 应从实际工作目录解析相对入口');
  assert.equal(entry, 'src/server.mjs');
});
