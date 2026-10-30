import {readFile,mkdtemp,mkdir,writeFile} from 'node:fs/promises';
import {tmpdir,homedir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {performance} from 'node:perf_hooks';
import {exampleOutcomes} from './models.mjs';
const root=fileURLToPath(new URL('./',import.meta.url));
const jdk=process.env.AUTHORITY_LAB_JDK||join(homedir(),'Library/Java/JavaVirtualMachines/openjdk-26/Contents/Home');
function run(command,args,cwd=root){
  const start=performance.now();const result=spawnSync(command,args,{cwd,encoding:'utf8',timeout:30000,maxBuffer:1024*1024});
  if(result.error||result.status!==0)throw Error(`执行失败：${args[0]||'environment'}；不作为预期红计入`);
  return {stdout:result.stdout,durationMs:Math.round(performance.now()-start)};
}
const started=performance.now();
const node=run(process.execPath,['--test','models.test.mjs']);
const count=Number(node.stdout.match(/# tests (\d+)/)?.[1]||node.stdout.match(/ℹ tests (\d+)/)?.[1]);
if(!Number.isInteger(count)||count<11)throw Error('Node测试汇总缺失');
const temp=await mkdtemp(join(tmpdir(),'fincore-authority-'));await mkdir(join(temp,'classes'));
const compile=run(join(jdk,'bin','javac'),['--release','21','-encoding','UTF-8','-proc:none','-d',join(temp,'classes'),join(root,'SigningIntentGuard.java')]);
const javaResult=run(join(jdk,'bin','java'),['-Xmx64m','-XX:ActiveProcessorCount=2','-cp',join(temp,'classes'),'SigningIntentGuard'],temp);
const java=JSON.parse(javaResult.stdout.trim());
if(java.passed!==16||java.walletConnected!==false||java.chainTransactionBroadcast!==false)throw Error('Java验证结果不完整');
const files=['models.mjs','models.test.mjs','asset-audit.mjs','SigningIntentGuard.java','casebook.json','verify.mjs','README.md'];
const sourceSha256={};for(const f of files)sourceSha256[f]=createHash('sha256').update(await readFile(join(root,f))).digest('hex');
const evidence={schemaVersion:1,verifiedAt:new Date().toISOString(),asOf:'2026-10-30',passed:true,
  node:{tests:count,durationMs:node.durationMs},java:{...java,compileMs:compile.durationMs,runMs:javaResult.durationMs,release:21},
  totalDurationMs:Math.round(performance.now()-started),sourceSha256,scenarios:exampleOutcomes,
  scope:'真实运行Node规则测试与Java签名/并发防御实验；八个回放为合成模型。未执行恶意脚本、未重放EVM、未调用钱包或验证真实平台补丁。'};
if(process.argv[2]==='--save')await writeFile(join(root,'evidence.json'),JSON.stringify(evidence,null,2)+'\n');
else if(process.argv.length>2)throw Error('仅支持 --save');
process.stdout.write(JSON.stringify(evidence,null,2)+'\n');
