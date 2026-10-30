import { readFile, writeFile, mkdir, realpath } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';
import { processRun } from './process.mjs';

export const workshop = fileURLToPath(new URL('../workshop/', import.meta.url));
export const sha = value => createHash('sha256').update(value).digest('hex');

export async function trustedInputs() {
  const [before, probe, casesText] = await Promise.all([
    readFile(join(workshop, 'before/TicketQuery.java'), 'utf8'),
    readFile(join(workshop, 'tests/QueryProbe.java'), 'utf8'),
    readFile(join(workshop, 'tests/cases.json'), 'utf8'),
  ]);
  const cases = JSON.parse(casesText);
  if (!Array.isArray(cases) || cases.length !== 12 || cases.some((c, i) => c.id !== `P${String(i + 1).padStart(2, '0')}`)) throw new Error('TRUSTED_INPUT_INVALID');
  return { before, probe, cases, hashes: { before: sha(before), probe: sha(probe), cases: sha(casesText) } };
}

/** macOS 本地隔离：默认拒绝网络；只允许读取系统/JDK/当前运行目录，写当前目录。
 * 这不是生产多租户沙箱，所有候选执行仍须人工确认；不支持环境一律失败关闭。
 */
export async function sandboxProfile(folder, jdk, writable = folder) {
  const safe = path => JSON.stringify(path);
  const reads = ['/System', '/usr/lib', '/usr/share', '/Library/Apple', '/private/var/db/dyld', '/private/var/db/timezone', '/dev', '/private/etc', await realpath(jdk), await realpath(folder)];
  return `(version 1)(deny default)
    (import "dyld-support.sb")
    (allow syscall*)(allow mach-bootstrap)
    (allow process*)(allow sysctl-read)(allow mach-lookup)(allow file-map-executable)
    (allow file-read-metadata)
    (allow file-read* ${reads.map(p => `(subpath ${safe(p)})`).join(' ')})
    (allow file-write* (subpath ${safe(await realpath(writable))}) (literal "/dev/null"))`;
}

export async function verifySource(source, folder, { jdk, trusted, signal } = {}) {
  if (process.platform !== 'darwin' || !jdk) throw new Error('SANDBOX_OR_JDK_UNAVAILABLE');
  await mkdir(folder, {recursive:true, mode:0o700});
  const root = await realpath(folder);
  const classes = join(root, 'classes'); await mkdir(classes);
  const runtimeTmp = join(root, 'runtime-tmp'); await mkdir(runtimeTmp);
  await writeFile(join(root, 'TicketQuery.java'), source, {mode:0o600});
  await writeFile(join(root, 'QueryProbe.java'), trusted.probe, {mode:0o600});
  const profile = join(root, 'sandbox.sb');
  await writeFile(profile, await sandboxProfile(root, jdk), {mode:0o600});
  const runtimeProfile = join(root, 'runtime.sb');
  await writeFile(runtimeProfile, await sandboxProfile(root, jdk, runtimeTmp), {mode:0o600});
  const env = { PATH:'/usr/bin:/bin', HOME:root, TMPDIR:root, LANG:'en_US.UTF-8', LC_ALL:'en_US.UTF-8' };
  const run = (name, args, timeout) => processRun('/usr/bin/sandbox-exec', ['-f', name === 'java' ? runtimeProfile : profile, join(jdk,'bin',name), ...args],
    {cwd:root, env, signal, timeout, limit:65536});
  const compilation = await run('javac', ['-J-Xmx128m', '-J-XX:ActiveProcessorCount=2', '--release','21','-encoding','UTF-8',
    '-proc:none','-sourcepath', root, '-classpath', classes, '-d',classes,join(root,'TicketQuery.java'),join(root,'QueryProbe.java')], 20000);
  if (compilation.code !== 0) return { status:'compile_failed', compiled:false, compilationMs:compilation.durationMs, diagnostic:(compilation.stderr || compilation.stdout || '编译器异常退出；请检查隔离环境，不计作预期红。').slice(0,3000).replaceAll(root,'<run>'), results:[], passed:0, total:12, sourceHash:sha(source), trustedHashes:trusted.hashes };
  const results = [];
  for (const c of trusted.cases) {
    const query = c.input.query === null ? '@null' : Buffer.from(c.input.query).toString('base64');
    const result = await run('java', ['-Xmx64m','-Xss512k','-XX:ActiveProcessorCount=2','-XX:-UsePerfData', '-Djava.io.tmpdir='+runtimeTmp,
      '-cp',classes,'QueryProbe',String(c.input.page),String(c.input.size),query], 4000);
    if (result.code !== 0) throw new Error('CANDIDATE_PROCESS_FAILED');
    let value, actual;
    try {
      value = JSON.parse(result.stdout);
      actual = value.kind === 'ok' ? {kind:'ok',page:value.page,size:value.size,offset:value.offset,query:Buffer.from(value.queryBase64,'base64').toString('utf8')}
        : {kind:'error',type:value.type,message:Buffer.from(value.messageBase64,'base64').toString('utf8')};
    } catch { throw new Error('PROBE_PROTOCOL_INVALID'); }
    results.push({id:c.id,name:c.name,input:c.input,expected:c.expected,actual,pass:isDeepStrictEqual(actual,c.expected),durationMs:result.durationMs});
  }
  const passed = results.filter(r=>r.pass).length;
  return {status:passed===12?'passed':'failed', compiled:true, compilationMs:compilation.durationMs, results, passed,total:12, sourceHash:sha(source),trustedHashes:trusted.hashes};
}
