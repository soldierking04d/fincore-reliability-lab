import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
/** 只读HTML静态引用，不请求脚本URL、不eval；不是通用HTML解析器或浏览器安全扫描。 */
export function auditScripts(html,base='https://fincore.invalid/'){
  const origin=new URL(base).origin;
  const scripts=[];
  for(const match of html.matchAll(/<script\b([^>]*)>/gi)){
    const attributes={};
    for(const attr of match[1].matchAll(/([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/g))attributes[attr[1].toLowerCase()]=attr[2]??attr[3];
    if(!attributes.src)continue;
    const url=new URL(attributes.src,base);
    const external=url.origin!==origin;
    const integrityValid=(attributes.integrity||'').trim().split(/\s+/).some(value=>{
      const parsed=value.match(/^sha(384|512)-([A-Za-z0-9+/]+=*)$/);
      return parsed&&Buffer.from(parsed[2],'base64').length===Number(parsed[1])/8;
    });
    scripts.push({origin:url.origin,path:url.pathname,external,integrity:attributes.integrity||null,
      corsDeclared:attributes.crossorigin!==undefined,requiresReview:external&&(!integrityValid||attributes.crossorigin!=="anonymous")});
  }
  return {scripts,externalCount:scripts.filter(s=>s.external).length,requiresReview:scripts.some(s=>s.requiresReview),
    limitation:'仅识别HTML内带引号的静态script src。未验证CORS响应、外部脚本字节、动态加载、登录态、CSP运行效果或浏览器扩展。无发现不等于全站安全。'};
}
/** 当前本站无需外部script，出现即阻止导出，不因声明integrity就自动豁免。 */
export function requireSelfHostedScripts(html,base){
  const report=auditScripts(html,base);
  if(report.externalCount>0)throw Error('发行阻断：发现外部静态脚本；须先独立审查并调整发布策略');
  return report;
}
if(process.argv[1]===fileURLToPath(import.meta.url)){
  if(process.argv.length!==3)throw Error('用法：node asset-audit.mjs /本地公开HTML文件');
  console.log(JSON.stringify(auditScripts(await readFile(process.argv[2],'utf8')),null,2));
}
