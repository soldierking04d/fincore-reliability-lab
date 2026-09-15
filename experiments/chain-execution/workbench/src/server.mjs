import http from 'node:http';
import {readFile} from 'node:fs/promises';
import {randomBytes,timingSafeEqual} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import bs58 from 'bs58';
import {parseStrictJson} from './rpc.mjs';

const mint='AakW2qYEFRK5DunP7yNaXAr9eknBtLiEd9iMXAAC6Mck';
const pool='HUeniRZwa8nSXimfMreuv7MidAoVGMesxLPDssLSyj6c';
const config={mode:'LOCAL_SIMULATION_ONLY',cluster:'solana:mainnet',walletEnabled:true,executionAllowed:false,
  mint:{address:mint,symbol:'FCLAB',decimals:6},pool:{address:pool,dex:'Orca Whirlpool'},
  limits:{BUY:{maxAtomic:'100000',decimals:9,symbol:'SOL',defaultHuman:'0.0001'},
    SELL:{maxAtomic:'10000000000',decimals:6,symbol:'FCLAB',defaultHuman:'100'},slippageBps:50}};
const staticFiles=new Map([
  ['/', ['index.html','text/html; charset=utf-8']],['/index.html',['index.html','text/html; charset=utf-8']],
  ['/styles.css',['styles.css','text/css; charset=utf-8']],['/app.bundle.js',['app.bundle.js','text/javascript; charset=utf-8']]
]);
const fail=(status,code,message)=>Object.assign(new Error(message),{httpStatus:status,code});
const nonce=()=>randomBytes(32).toString('hex');
function equal(a,b){return typeof a==='string'&&typeof b==='string'&&Buffer.byteLength(a)===Buffer.byteLength(b)&&timingSafeEqual(Buffer.from(a),Buffer.from(b));}

/**
 * HTTP层只接收公开地址和整数金额。会话/CSRF用于隔离浏览器来源，不是钱包所有权证明。
 * 同时只允许一个预检：CPU构建/模拟全部结束后才释放槽位，断开浏览器不能绕过资源预算。
 * 无排队、无自动重试、无历史金融状态；钱包权限和最终账本均不保存在该进程中。
 */
export function createWorkbenchServer({preview,cooldownMs=1500,now=Date.now,sessionTtlMs=15*60_000}={}){
  if(typeof preview!=='function')throw new TypeError('preview required');
  const sessions=new Map(); let active=false;
  function respond(res,status,data,type='application/json; charset=utf-8'){
    if(res.destroyed)return;
    res.writeHead(status,{'Content-Type':type});res.end(type.startsWith('application/json')?JSON.stringify(data):data);
  }
  function reject(res,status,code,message){respond(res,status,{error:{code,message},executionAllowed:false});}
  function cookieSession(req){
    const cookies=(req.headers.cookie??'').split(';').map(x=>x.trim());
    const values=cookies.filter(x=>x.startsWith('fincore_preview='));
    if(values.length!==1)return null;
    const key=values[0].slice('fincore_preview='.length);const entry=sessions.get(key);
    if(!entry||entry.expires<=now()){sessions.delete(key);return null;}return entry;
  }
  async function body(req){
    if(!/^application\/json(?:\s*;\s*charset=utf-8)?$/i.test(req.headers['content-type']??''))throw fail(415,'CONTENT_TYPE','只接受 JSON 请求。');
    if(req.headers['content-encoding'])throw fail(415,'CONTENT_ENCODING','不接受压缩请求。');
    if(Number(req.headers['content-length'])>4096)throw fail(413,'BODY_TOO_LARGE','请求正文超过限制。');
    let size=0;const chunks=[];
    const timer=setTimeout(()=>req.destroy(),3000);timer.unref();
    try{
      for await(const chunk of req){size+=chunk.length;if(size>4096)throw fail(413,'BODY_TOO_LARGE','请求正文超过限制。');chunks.push(chunk);}
      try{return parseStrictJson(new TextDecoder('utf-8',{fatal:true}).decode(Buffer.concat(chunks)));}
      catch{throw fail(400,'INVALID_JSON','请求必须是无重复字段的有效 JSON。');}
    }finally{clearTimeout(timer);}
  }
  function validate(input){
    if(!input||Array.isArray(input)||Object.keys(input).sort().join(',')!=='amountAtomic,side,wallet')throw fail(400,'INVALID_INPUT','只允许钱包地址、买卖方向和整数金额。');
    const {wallet,side,amountAtomic}=input;
    try{if(typeof wallet!=='string'||wallet.length>44||bs58.decode(wallet).length!==32||bs58.encode(bs58.decode(wallet))!==wallet)throw 0;}
    catch{throw fail(400,'INVALID_WALLET','需要完整的 Solana 公开地址，不能使用 0x 地址。');}
    if(!['BUY','SELL'].includes(side)||typeof amountAtomic!=='string'||! /^[1-9][0-9]{0,13}$/.test(amountAtomic)||BigInt(amountAtomic)>BigInt(config.limits[side].maxAtomic))throw fail(400,'INVALID_AMOUNT','方向或金额不符合本地模拟限额。');
    return {wallet,side,amountAtomic};
  }
  const server=http.createServer(async(req,res)=>{
    res.setHeader('Cache-Control','no-store');res.setHeader('X-Content-Type-Options','nosniff');
    res.setHeader('Referrer-Policy','no-referrer');res.setHeader('X-Frame-Options','DENY');
    res.setHeader('Content-Security-Policy',"default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'; object-src 'none'");
    // 拒绝DNS重绑定/反向代理伪造；本阶段仅直接回环访问或SSH本地转发，不相信Forwarded头。
    const origin=`http://127.0.0.1:${server.address().port}`;
    if(req.headers.host!==new URL(origin).host)return reject(res,403,'INVALID_HOST','仅允许通过指定回环地址访问。');
    try{
      if(typeof req.url!=='string'||!req.url.startsWith('/')||req.url.startsWith('//'))throw fail(400,'INVALID_PATH','请求路径无效。');
      const url=new URL(req.url,origin);
      if(req.method==='GET'&&url.pathname==='/api/config'){
        if(req.headers['sec-fetch-site']==='cross-site'||(req.headers.origin&&req.headers.origin!==origin))throw fail(403,'CROSS_SITE','禁止跨站获取会话。');
        for(const [id,entry] of sessions)if(entry.expires<=now())sessions.delete(id);
        let session=cookieSession(req);
        if(!session){
          if(sessions.size>=32)throw fail(429,'SESSION_LIMIT','本地会话已满，请稍后再试。');
          const id=nonce();session={csrf:nonce(),expires:now()+sessionTtlMs,last:0};sessions.set(id,session);
          res.setHeader('Set-Cookie',`fincore_preview=${id}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${Math.floor(sessionTtlMs/1000)}`);
        }
        return respond(res,200,{...config,csrfToken:session.csrf});
      }
      if(req.method==='POST'&&url.pathname==='/api/preview'){
        const session=cookieSession(req);
        if(req.headers.origin!==origin||req.headers['sec-fetch-site']==='cross-site'||!session||!equal(req.headers['x-csrf-token'],session.csrf))throw fail(403,'SESSION_REQUIRED','会话已失效或请求来源不匹配，请刷新页面。');
        const input=validate(await body(req));
        if(active||now()-session.last<cooldownMs)throw fail(429,'PREVIEW_BUSY','已有预检正在运行或请求过快，请等结果后再试。');
        active=true;session.last=now();
        try{const result=await preview(input);return respond(res,200,{...result,executionAllowed:false});}
        finally{active=false;}
      }
      if(req.method==='GET'&&staticFiles.has(url.pathname)){
        const [file,type]=staticFiles.get(url.pathname);
        const content=await readFile(new URL(`../public/${file}`,import.meta.url));
        return respond(res,200,content,type);
      }
      return reject(res,404,'NOT_FOUND','此接口不存在。');
    }catch(error){
      if(error.httpStatus)return reject(res,error.httpStatus,error.code,error.message);
      return reject(res,502,'PREVIEW_UNAVAILABLE','预检未完成：节点、数据或服务暂不可用；没有发起链上交易。');
    }
  });
  server.requestTimeout=5000;server.headersTimeout=5000;server.keepAliveTimeout=2000;server.maxConnections=32;
  return server;
}

if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
  const [{createReadOnlyRpc},{createDexPreview}]=await Promise.all([import('./rpc.mjs'),import('./dex.mjs')]);
  const port=Number(process.env.FINCORE_WORKBENCH_PORT??4398);
  if(!Number.isInteger(port)||port<1024||port>65535)throw new Error('端口无效');
  const server=createWorkbenchServer({preview:createDexPreview({rpc:createReadOnlyRpc()})});
  server.listen(port,'127.0.0.1',()=>process.stdout.write(`本地只读工作台：http://127.0.0.1:${port}/\n禁止签名与广播；不保存钱包凭据。\n`));
}
