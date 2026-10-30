import http from 'node:http';
import { randomBytes } from 'node:crypto';
import { readFile, access } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { homedir } from 'node:os';
import { Lab, requirement } from './engine.mjs';

export const port=4400;
const hosts=new Set([`127.0.0.1:${port}`,`localhost:${port}`]);
const origins=new Set([...hosts].map(x=>`http://${x}`));
export function makeServer(lab,{cliAvailable=false,jdkAvailable=false}={}) {
  const token=randomBytes(32).toString('hex');
  return http.createServer(async(req,res)=>{
    const send=(code,data,type='application/json; charset=utf-8')=>{res.writeHead(code,{'Content-Type':type});res.end(type.startsWith('application/json')?JSON.stringify(data):data);};
    res.setHeader('Cache-Control','no-store');res.setHeader('X-Content-Type-Options','nosniff');res.setHeader('Referrer-Policy','no-referrer');
    res.setHeader('X-Frame-Options','DENY');res.setHeader('X-Robots-Tag','noindex,nofollow');
    res.setHeader('Content-Security-Policy',"default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'");
    if(!hosts.has(req.headers.host)||(req.headers.origin&&!origins.has(req.headers.origin))
      ||(req.headers['sec-fetch-site']&&!['same-origin','none'].includes(req.headers['sec-fetch-site']))) return send(403,{error:'仅允许本机同源访问'});
    let url;try{url=new URL(req.url,'http://127.0.0.1');}catch{return send(400,{error:'地址无效'});}
    const path=url.pathname;
    try{
      if(req.method==='GET'){
        if(path==='/api/config')return send(200,{token,requirement,cliAvailable,jdkAvailable,model:lab.model||'CLI 服务默认模型',maxCalls:2});
        if(path==='/api/runs')return send(200,lab.list());
        const match=path.match(/^\/api\/runs\/([a-f0-9-]{36})(\/report)?$/);
        if(match){if(match[2])res.setHeader('Content-Disposition','attachment; filename="ai-delivery-report.json"');return send(200,lab.view(match[1]));}
        const files={'/':'index.html','/app.js':'app.js','/styles.css':'styles.css'};
        if(Object.hasOwn(files,path))return send(200,await readFile(new URL('./public/'+files[path],import.meta.url)),path.endsWith('.js')?'text/javascript; charset=utf-8':path.endsWith('.css')?'text/css; charset=utf-8':'text/html; charset=utf-8');
        return send(404,{error:'Not found'});
      }
      if(req.method!=='POST')return send(405,{error:'Method not allowed'});
      if(req.headers['x-lab-token']!==token||!req.headers['content-type']?.startsWith('application/json'))return send(403,{error:'需要本机页面操作令牌'});
      if(Number(req.headers['content-length'])>8192)return send(413,{error:'请求过大'});
      let raw='';for await(const chunk of req){raw+=chunk;if(Buffer.byteLength(raw)>8192){send(413,{error:'请求过大'});return;}}
      let body;try{body=JSON.parse(raw);}catch{return send(400,{error:'JSON 无效'});}
      if(!body||typeof body!=='object'||Array.isArray(body))return send(400,{error:'参数无效'});
      if(path==='/api/runs'){
        if(body.mode==='codex'&&!cliAvailable)return send(409,{error:'本机 Codex CLI 不可用，不能伪装模型执行。'});
        return send(202,await lab.create(body));
      }
      const match=path.match(/^\/api\/runs\/([a-f0-9-]{36})\/(generate|verify|accept|cancel)$/);
      if(!match)return send(404,{error:'Not found'});
      return send(202,await lab.action(match[1],body.version,match[2],body));
    }catch(error){
      const message=String(error.message||'FAILED');
      const known=/^(NOT_FOUND|VERSION_CONFLICT|LAB_BUSY|INVALID_|EXECUTION_APPROVAL_REQUIRED|REVIEW_REQUIRED|MODEL_CALL_LIMIT|HISTORY_LIMIT)/.test(message);
      send(message==='NOT_FOUND'?404:known?409:500,{error:known?message:'本机执行失败，请检查运行环境；不会将失败记为成功。'});
    }
  });
}

if(process.argv[1]===fileURLToPath(import.meta.url)){
  const root=fileURLToPath(new URL('./.local/runs/',import.meta.url));
  const jdk=process.env.AI_LAB_JDK||join(homedir(),'Library/Java/JavaVirtualMachines/openjdk-26/Contents/Home');
  const cli=process.env.AI_LAB_CODEX||'/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex';
  const available=async p=>access(p).then(()=>true,()=>false);
  const lab=new Lab({root,jdk,cli,model:process.env.AI_LAB_MODEL});await lab.init();
  const server=makeServer(lab,{cliAvailable:await available(cli),jdkAvailable:await available(join(jdk,'bin','javac'))&&process.platform==='darwin'});
  server.requestTimeout=10000;server.headersTimeout=10000;
  server.listen(port,'127.0.0.1',()=>console.log(`AI delivery lab: http://127.0.0.1:${port}/`));
  server.on('error',e=>{console.error(e.code||'START_FAILED');process.exitCode=1;});
  for(const name of ['SIGINT','SIGTERM'])process.on(name,()=>{for(const c of lab.controllers.values())c.abort();server.close();});
}
