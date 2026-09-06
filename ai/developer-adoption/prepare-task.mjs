import { readFileSync, lstatSync, realpathSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, resolve, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const repo = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const workshop = 'ai/developer-adoption/workshop';
const shared = ['AGENTS.md', 'ai/developer-adoption/common-workflow.md'];

/** 工具无关任务合同。显式文件白名单，不遍历仓库、不执行命令、不连接模型。 */
export const tasks = {
  understand: {
    title: '解释账户摘要的真实查询链路', mode: 'read_only',
    instruction: '仅只读定位 GET /api/accounts/{id}/ledger-summary。给出入口、应用服务、SQL 的文件与方法依据；区分已确认和未知，不修改代码、不连接数据库。',
    sources: ['ai/developer-adoption/context-map.json',
      'src/main/java/dev/fincore/web/AccountController.java',
      'src/main/java/dev/fincore/application/AccountService.java',
      'src/main/java/dev/fincore/infrastructure/persistence/mapper/AccountMapper.java'],
    acceptance: ['三个层次均给真实文件/符号引用', '不把源码关系说成运行期性能或权限证明', '工作区无代码改动'],
  },
  implement: {
    title: '实现内部工单分页参数合同', mode: 'candidate_patch',
    instruction: '在独立候选副本中实现 TicketQuery：page>=0，size=1..200，否则抛 IllegalArgumentException；long offset；null query 为空串；只剥离首尾 Character.isWhitespace 或 isSpaceChar 空白，保留内部字符。保留接口与用例，不加依赖，不改其他模块。',
    sources: [`${workshop}/before/TicketQuery.java`, `${workshop}/tests/cases.json`, `${workshop}/tests/QueryProbe.java`],
    acceptance: ['独立用例 12/12 通过', '说明非法输入被拒绝属于行为收紧', '数据库、HTTP、深分页性能标记未验证'],
  },
  repair: {
    title: '只修复分页偏移 int 溢出', mode: 'candidate_patch',
    instruction: '复现 page=2147483647、size=200 得到 -200 的缺陷，正确偏移为429496729400。只修复乘法类型，不改变非法参数和关键词行为；保留测试期望。只交候选补丁，不顺带实现完整需求。',
    sources: [`${workshop}/before/TicketQuery.java`, `${workshop}/tests/cases.json`, `${workshop}/tests/QueryProbe.java`],
    acceptance: ['P03 从失败转为通过', '其他用例的实际行为与旧版相同', '明确这是5/12，不冒充完整需求完成'],
  },
};

export function prepareTask(id, root = repo) {
  if (!Object.hasOwn(tasks, id)) throw new Error('任务只允许 understand、implement、repair');
  const task = tasks[id];
  const base = realpathSync(root);
  let total = 0;
  const files = [...shared, ...task.sources].map(path => {
    const full = resolve(base, path);
    const info = lstatSync(full);
    const resolved = realpathSync(full);
    const distance = relative(base, resolved);
    if (info.isSymbolicLink() || !info.isFile() || distance.startsWith(`..${sep}`) || distance === '..') {
      throw new Error('任务来源必须为仓库内普通文件');
    }
    total += info.size;
    if (info.size > 48_000 || total > 96_000) throw new Error('上下文过大，请人工缩小来源');
    const content = readFileSync(full, 'utf8');
    return { path, sha256: createHash('sha256').update(content).digest('hex'), content };
  });
  return {
    schemaVersion: 1, taskId: id, title: task.title, mode: task.mode,
    agent: 'tool_neutral', modelInvoked: false, instruction: task.instruction,
    acceptance: task.acceptance,
    resultContract: ['需求与改动范围', '引用或补丁', '测试命令与实际结果', '未验证项', '风险与人工待决项'],
    sourceNote: '文件白名单与摘要用于上下文复核，不是脱敏证明、权限边界或源码版本签名。',
    evaluationNote: '此包不含参考实现。若在完整仓库运行 Agent，仍可能读到答案；评估新候选必须使用独立环境与受信测试。',
    files,
  };
}

export function renderTask(bundle) {
  return `# ${bundle.title}\n\n${bundle.instruction}\n\n## 验收\n${bundle.acceptance.map(s=>`- ${s}`).join('\n')}\n\n## 必须交付\n${bundle.resultContract.map(s=>`- ${s}`).join('\n')}\n\n${bundle.sourceNote}\n${bundle.evaluationNote}\n\n${bundle.files.map(f=>`## 来源：${f.path}\nSHA-256: ${f.sha256}\n\n${f.content}`).join('\n\n')}\n`;
}

// /tmp 等目录可能是别名；按真实文件判断直接执行，避免解压后静默不输出。
if (process.argv[1] && realpathSync(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const [id, format, ...extra] = process.argv.slice(2);
    if (extra.length || (format && format !== '--json')) throw new Error('用法：node prepare-task.mjs understand|implement|repair [--json]');
    const bundle = prepareTask(id);
    process.stdout.write(format ? JSON.stringify(bundle,null,2)+'\n' : renderTask(bundle));
  } catch {
    // 不回显文件系统错误中的绝对路径或可能的输入内容。
    console.error('任务包生成失败：核对任务名、文件是否齐全、来源是否为仓库内普通文件及大小限制。');
    process.exitCode = 1;
  }
}
