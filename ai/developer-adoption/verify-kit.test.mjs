import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const map = JSON.parse(readFileSync(resolve(root, 'ai/developer-adoption/context-map.json'), 'utf8'));

test('仓库地图的源文件、符号和调用证据仍存在', () => {
  assert.equal(map.nodes.length, 3);
  for (const item of map.nodes) {
    const source = readFileSync(resolve(root, item.path), 'utf8');
    assert.ok(source.includes(item.symbol), item.path);
    for (const token of item.evidence) assert.ok(source.includes(token), `${item.path}: ${token}`);
  }
  for (const file of map.referenceFiles) assert.ok(existsSync(resolve(root, file)), file);
});

test('上下文规则引用现有守则，版本化文件齐备', () => {
  const rule = readFileSync(resolve(root, '.cursor/rules/company-context.mdc'), 'utf8');
  assert.match(rule, /^---\n/);
  assert.match(rule, /alwaysApply: true/);
  assert.ok(rule.includes('AGENTS.md'));
  for (const file of ['task-pack.md', 'review-template.md', 'README.md']) {
    assert.ok(readFileSync(resolve(root, 'ai/developer-adoption', file), 'utf8').length > 100);
  }
});

test('忽略文件不能被误称为安全边界', () => {
  const ignore = readFileSync(resolve(root, '.cursorignore'), 'utf8');
  assert.ok(ignore.includes('不能阻止'));
  for (const pattern of ['.env', '**/*.pem', '**/*.key', 'reports/runtime/']) assert.ok(ignore.includes(pattern));
});
