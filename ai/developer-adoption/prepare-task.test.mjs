import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, mkdirSync, copyFileSync, writeFileSync, symlinkSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { prepareTask, renderTask, tasks } from './prepare-task.mjs';

const root=resolve(dirname(fileURLToPath(import.meta.url)),'../..');
for (const id of Object.keys(tasks)) {
  test(`${id} 使用工具无关合同与精确来源摘要`,()=>{
    const bundle=prepareTask(id);
    assert.equal(bundle.agent,'tool_neutral');
    assert.equal(bundle.modelInvoked,false);
    assert.ok(bundle.files.some(f=>f.path==='AGENTS.md'));
    assert.ok(bundle.files.some(f=>f.path.endsWith('common-workflow.md')));
    assert.equal(new Set(bundle.files.map(f=>f.path)).size,bundle.files.length);
    assert.ok(bundle.files.every(f=>!/(after\/|overflow-only|\.patch$|verified-report|\.env)/u.test(f.path)));
    for(const file of bundle.files) assert.equal(file.sha256,createHash('sha256').update(readFileSync(join(root,file.path))).digest('hex'));
    assert.ok(renderTask(bundle).includes(bundle.instruction));
    assert.deepEqual(prepareTask(id),bundle);
  });
}
test('未知任务与路径输入不能用于读取任意文件',()=>{
  for(const id of ['__proto__','constructor','../../.env','',undefined]) assert.throws(()=>prepareTask(id));
});
function fixture() {
  const copy=mkdtempSync(join(tmpdir(),'ai-context-test-'));
  for(const file of prepareTask('repair').files){
    mkdirSync(dirname(join(copy,file.path)),{recursive:true});
    copyFileSync(join(root,file.path),join(copy,file.path));
  }
  return copy;
}
test('上下文过大时明确拒绝而不是截断成不完整规范',()=>{
  const copy=fixture();
  writeFileSync(join(copy,'AGENTS.md'),'A'.repeat(48001));
  assert.throws(()=>prepareTask('repair',copy),/过大/u);
});
test('符号链接来源被拒绝',()=>{
  const copy=mkdtempSync(join(tmpdir(),'ai-context-link-'));
  symlinkSync(join(root,'AGENTS.md'),join(copy,'AGENTS.md'));
  assert.throws(()=>prepareTask('repair',copy),/普通文件/u);
});
test('两类修改任务验收不同；更换 Agent 不改变它们',()=>{
  assert.ok(prepareTask('repair').acceptance.some(v=>v.includes('5/12')));
  assert.ok(prepareTask('implement').acceptance.some(v=>v.includes('12/12')));
  const claude=readFileSync(join(root,'CLAUDE.md'),'utf8');
  assert.ok(claude.includes('@AGENTS.md'));
  assert.ok(claude.includes('@ai/developer-adoption/common-workflow.md'));
});

test('通过目录别名直接运行仍会输出任务包，不能空成功',()=>{
  const folder=mkdtempSync(join(tmpdir(),'ai-cli-alias-'));
  const alias=join(folder,'repo');
  symlinkSync(root,alias,'dir');
  const result=spawnSync(process.execPath,[join(alias,'ai/developer-adoption/prepare-task.mjs'),'repair','--json'],{encoding:'utf8',timeout:10000});
  assert.equal(result.status,0);
  assert.equal(JSON.parse(result.stdout).taskId,'repair');
});
