import { readFile } from 'node:fs/promises';
import { assessPhpAsset } from './php-policy.mjs';

// 只接受本地 JSON 文件，避免“安全核验”工具变成扫描或执行远端请求的入口。
if (!process.argv[2] || process.argv.length !== 3) {
  console.error('用法：node assess.mjs 本地资产清单.json');
  process.exitCode = 64;
} else {
  try {
    const assets = JSON.parse(await readFile(process.argv[2], 'utf8'));
    if (!Array.isArray(assets) || assets.length === 0) throw new Error('清单必须是非空数组');
    const results = assets.map(asset => ({ asset: asset.name ?? '未命名资产', ...assessPhpAsset(asset) }));
    console.log(JSON.stringify({ results }, null, 2));
    // 待处理与待核验都阻止此清单通过；不把资料缺失当作零风险。
    process.exitCode = results.some(result => result.status !== 'covered') ? 2 : 0;
  } catch (error) {
    console.error(`清单读取失败：${error.message}`);
    process.exitCode = 64;
  }
}
