import { processRun } from './process.mjs';

export const responseSchema = {
  type: 'object', additionalProperties: false,
  properties: {
    summary: { type: 'string' }, source: { type: 'string' },
    risks: { type: 'array', items: { type: 'string' } },
  }, required: ['summary', 'source', 'risks'],
};

/** 仅采纳 CLI 完成事件中的实际用量；缺失就是未知，不用字符数猜 Token。 */
export function parseUsage(usage) {
  const values = ['input_tokens', 'cached_input_tokens', 'output_tokens'].map(key => usage?.[key]);
  if (!values.every(n => Number.isSafeInteger(n) && n >= 0) || values[1] > values[0]) return null;
  return { input: values[0], cachedInput: values[1], output: values[2], source: 'codex_cli_turn_completed' };
}

export function estimateCost(attempts, rates) {
  if (!attempts.length) return { usd: null, note: '未调用模型，不产生本次模型 Token 记录。' };
  if (attempts.some(a => !a.usage)) return { usd: null, note: '部分调用未返回用量，无法可靠折算总金额。' };
  if (!rates || !['input', 'cachedInput', 'output'].every(k => Number.isFinite(rates[k]) && rates[k] >= 0)) {
    return { usd: null, note: '尚未填写单价；订阅额度不能直接换算成账单美元。' };
  }
  const usd = attempts.reduce((sum, {usage:u}) => sum + ((u.input - u.cachedInput) * rates.input + u.cachedInput * rates.cachedInput + u.output * rates.output) / 1e6, 0);
  return { usd, note: '按自填 USD / 百万 Token 单价折算，不是实际扣款；未含人工和基础设施成本。' };
}

export async function callCodex({ prompt, cwd, schemaPath, signal, onUsage, onProgress, cli, model, runner = processRun }) {
  let finalText = '', completed = false;
  const args = ['exec', '--ignore-user-config', '--ignore-rules', '--ephemeral', '--skip-git-repo-check',
    '--sandbox', 'read-only', '--disable', 'shell_tool', '--disable', 'unified_exec', '--disable', 'multi_agent',
    '-c', 'web_search="disabled"', '-c', 'model_reasoning_effort="low"',
    '--json', '--output-schema', schemaPath, '-C', cwd];
  if (model) args.push('--model', model);
  args.push('-');
  const result = await runner(cli, args, { cwd, input: prompt, timeout: 180000, signal, limit: 524288,
    onLine(line) {
      let event; try { event = JSON.parse(line); } catch { return; }
      if (event.type === 'turn.completed') { completed = true; onUsage(parseUsage(event.usage)); }
      if (event.type === 'item.completed' && event.item?.type === 'agent_message') finalText = event.item.text;
      // 不持久化完整 CLI 事件，避免带入账号、环境或工具日志；只保留固定进度名称。
      if (event.type === 'turn.started') onProgress('模型已开始生成');
    },
  });
  if (result.code !== 0 || !completed) throw new Error('MODEL_CALL_FAILED');
  let answer; try { answer = JSON.parse(finalText); } catch { throw new Error('MODEL_JSON_INVALID'); }
  if (!answer || typeof answer.source !== 'string' || Buffer.byteLength(answer.source) > 24000
      || !answer.source.includes('public final class TicketQuery') || typeof answer.summary !== 'string'
      || !Array.isArray(answer.risks) || !answer.risks.every(x => typeof x === 'string')
      || Object.keys(answer).sort().join(',') !== 'risks,source,summary') throw new Error('MODEL_CONTRACT_INVALID');
  return answer;
}
