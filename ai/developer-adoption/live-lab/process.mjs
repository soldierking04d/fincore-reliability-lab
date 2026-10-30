import { spawn } from 'node:child_process';
import { performance } from 'node:perf_hooks';

/** 不经过 shell；超时、取消或输出超限时终止整个子进程组，不留后台编译或模型请求。 */
export function processRun(command, args, { cwd, input = '', timeout = 30000, signal, env = process.env, limit = 262144, onLine } = {}) {
  return new Promise((resolve, reject) => {
    const started = performance.now();
    const child = spawn(command, args, { cwd, env, detached: true, stdio: ['pipe', 'pipe', 'pipe'] });
    child.stdout.setEncoding('utf8'); child.stderr.setEncoding('utf8');
    let stdout = '', stderr = '', pending = '', failure;
    const stop = reason => {
      failure ||= reason;
      try { process.kill(-child.pid, 'SIGKILL'); } catch { /* 进程可能已退出。 */ }
    };
    const abort = () => stop('CANCELLED');
    const timer = setTimeout(() => stop('PROCESS_TIMEOUT'), timeout);
    signal?.addEventListener('abort', abort, { once: true });
    if (signal?.aborted) abort();
    child.stdin.on('error', () => {});
    child.on('error', () => { failure = 'PROCESS_UNAVAILABLE'; });
    child.stdout.on('data', bytes => {
      stdout += bytes.toString('utf8');
      if (Buffer.byteLength(stdout) + Buffer.byteLength(stderr) > limit) return stop('OUTPUT_LIMIT');
      pending += bytes.toString('utf8');
      const lines = pending.split('\n'); pending = lines.pop();
      for (const line of lines) { try { onLine?.(line); } catch { stop('INVALID_PROCESS_EVENT'); } }
    });
    child.stderr.on('data', bytes => {
      stderr += bytes.toString('utf8');
      if (Buffer.byteLength(stdout) + Buffer.byteLength(stderr) > limit) stop('OUTPUT_LIMIT');
    });
    child.on('close', code => {
      clearTimeout(timer); signal?.removeEventListener('abort', abort);
      if (pending.trim() && !failure) { try { onLine?.(pending); } catch { failure='INVALID_PROCESS_EVENT'; } }
      if (failure) reject(Object.assign(new Error(failure), { code: failure }));
      else resolve({ code, stdout, stderr, durationMs: Math.round(performance.now() - started) });
    });
    child.stdin.end(input);
  });
}
