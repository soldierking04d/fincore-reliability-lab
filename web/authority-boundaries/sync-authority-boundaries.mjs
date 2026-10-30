import {mkdir,copyFile,readFile,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {verifyPublicRoots} from './verify-public-content.mjs';
const source=new URL('../../fincore-reliability-lab/experiments/authority-boundaries/',import.meta.url);
const target=new URL('../public/authority-boundaries/',import.meta.url);
await mkdir(target,{recursive:true});
const files=['models.mjs','models.test.mjs','asset-audit.mjs','SigningIntentGuard.java','casebook.json','verify.mjs','README.md'];
const reuse=process.argv.includes('--reuse-evidence');
if(process.argv.slice(2).some(arg=>arg!=='--reuse-evidence'))throw Error('仅支持 --reuse-evidence');
const evidence=reuse?JSON.parse(await readFile(new URL('evidence.json',source),'utf8')):JSON.parse(execFileSync(process.execPath,[fileURLToPath(new URL('verify.mjs',source))],{encoding:'utf8',maxBuffer:1024*1024}));
if(!reuse)await writeFile(new URL('evidence.json',source),JSON.stringify(evidence,null,2)+'\n');
if(!evidence.passed||evidence.node.tests!==11||evidence.java.passed!==16)throw Error('原验证记录不完整');
for(const file of files){
  if(createHash('sha256').update(await readFile(new URL(file,source))).digest('hex')!==evidence.sourceSha256[file])throw Error('验证后源码变化');
  await copyFile(new URL(file,source),new URL(file,target));
}
await copyFile(new URL('../../fincore-reliability-lab/docs/security/authority-boundaries-20261030.md',import.meta.url),new URL('authority-boundaries-20261030.md',target));
await writeFile(new URL('evidence.json',target),JSON.stringify(evidence,null,2)+'\n');
const java=await readFile(new URL('SigningIntentGuard.java',target),'utf8');
const excerpt=(start,end)=>{const a=java.indexOf(start),b=java.indexOf(end,a);if(a<0||b<=a)throw Error('代码片段边界丢失');return java.slice(a,b).trimEnd();};
await writeFile(new URL('source-excerpts.json',target),JSON.stringify({sha256:evidence.sourceSha256['SigningIntentGuard.java'],excerpts:[
  {title:'完整意图字段与数组防御性复制',code:excerpt('    record Intent(','    static final class Denied')},
  {title:'审批绑定、验签、防重和原子预算',code:excerpt('        Approval approve(','        synchronized void pause()')},
  {title:'输入校验、策略代次和长度前缀编码',code:excerpt('    private static void validate(','    private static byte[] signature(')},
]},null,2)+'\n');
await verifyPublicRoots([fileURLToPath(target)]);
const archive=execFileSync('/usr/bin/zip',['-q','-',...files,'authority-boundaries-20261030.md','evidence.json','source-excerpts.json'],{cwd:fileURLToPath(target),maxBuffer:3*1024*1024});
await writeFile(new URL('source.zip',target),archive);
console.log(JSON.stringify({nodeTests:evidence.node.tests,javaChecks:evidence.java.passed,scenarios:evidence.scenarios.length,totalMs:evidence.totalDurationMs}));
