import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { runInNewContext } from 'node:vm';

// 验证页面实际脚本的状态与按钮接线；不冒充浏览器视觉验收。
class Element {
  constructor(){this.children=[];this.value='';this.checked=false;this.disabled=false;this.hidden=false;this.textContent='';}
  append(...nodes){this.children.push(...nodes);}
  replaceChildren(...nodes){this.children=[...nodes];}
  get firstChild(){return this.children[0];}
}
test('页面恢复真实记录，批准勾选控制验证按钮，搜索历史与模型调用不混用',async()=>{
  const source=await readFile(new URL('./public/app.js',import.meta.url),'utf8');
  const html=await readFile(new URL('./public/index.html',import.meta.url),'utf8');
  const elements=new Map([...html.matchAll(/id="([^"]+)"/g)].map(m=>[m[1],new Element()]));
  elements.get('mode').value='codex';
  let phase='review',version=2;const calls=[];
  const data=()=>({id:'run-1',version,status:phase,mode:'codex',busy:false,createdAt:new Date().toISOString(),
    stages:[],attempts:[{number:1,status:'completed',durationMs:100,usage:{input:100,cachedInput:10,output:30}}],
    usage:{calls:1,unknownCalls:0,input:100,cachedInput:10,output:30},activeMs:100,costs:{usd:null,note:'未填单价'},
    sourceBefore:'old',candidate:{sha256:'hash',source:'new',summary:'方案',risks:['需人工审查']},execution:{model:'服务默认'},baseline:{passed:4,results:[]}});
  const fetch=async(path,options)=>{
    calls.push({path,options});
    let value;
    if(path==='/api/config')value={token:'local',requirement:'固定合同',cliAvailable:true,jdkAvailable:true,model:'默认'};
    else if(path==='/api/runs')value=[{id:'run-1',createdAt:new Date().toISOString(),status:phase,mode:'codex'}];
    else if(path.endsWith('/verify')){const b=JSON.parse(options.body);assert.equal(b.approved,true);assert.equal(b.version,version);phase='passed';version++;value={...data(),verification:{passed:12,results:[]}};}
    else value=data();
    return {ok:true,json:async()=>value};
  };
  const context={document:{getElementById:id=>elements.get(id),createElement:()=>new Element()},fetch,
    window:{location:{href:'http://127.0.0.1:4400/'}},URL,console,setInterval:()=>{},Date,Uint16Array};
  await runInNewContext(`(async()=>{${source}\n})()`,context);
  for(let n=0;n<10;n++)await new Promise(r=>setImmediate(r));
  assert.equal(elements.get('workspace').hidden,false);assert.equal(elements.get('verify').disabled,true);
  assert.match(elements.get('candidate').textContent,/new/);assert.equal(elements.get('generate').disabled,true);
  elements.get('approve').checked=true;elements.get('approve').onchange();
  assert.equal(elements.get('verify').disabled,false);
  await elements.get('verify').onclick();
  assert.equal(elements.get('state').textContent,'测试通过 · 待验收');
  assert.equal(calls.filter(x=>x.path.endsWith('/verify')).length,1);
  assert.equal(calls.filter(x=>x.path.endsWith('/generate')).length,0);
  elements.get('review').value='检查边界后接受';elements.get('review').oninput();
  assert.equal(elements.get('accept').disabled,false);
});
