const $=id=>document.getElementById(id);
let config,run,requesting=false,lastCandidate='';
const states={baseline:'旧版测试运行中',ready:'旧版已复现 · 待生成',generating:'生成候选中',review:'待人工确认执行',testing:'候选测试中',passed:'测试通过 · 待验收',failed:'测试未通过',accepted:'人工已验收',error:'执行异常',cancelled:'已取消',interrupted:'运行被中断'};
const phases={baseline:'旧版复现',generate:'候选生成',verify:'独立验证'};
const elapsed=ms=>ms===null||ms===undefined?'—':ms<1000?`${ms} ms`:`${(ms/1000).toFixed(2)} s`;
const text=(id,value)=>{$(id).textContent=value;};
function node(tag,value,cls){const n=document.createElement(tag);if(value!==undefined)n.textContent=value;if(cls)n.className=cls;return n;}
async function api(path,body){
  const response=await fetch(path,{cache:'no-store',headers:body?{'Content-Type':'application/json','X-Lab-Token':config.token}:{},...(body?{method:'POST',body:JSON.stringify(body)}:{})});
  const data=await response.json();if(!response.ok)throw Error(data.error||`HTTP ${response.status}`);return data;
}
function rates(){
  const values=['rate-input','rate-cache','rate-output'].map(id=>$(id).value.trim());
  if(values.every(x=>!x))return null;
  if(values.some(x=>!x))throw Error('请完整填写三项单价，或者全部留空。');
  const [input,cachedInput,output]=values.map(Number);
  if(![input,cachedInput,output].every(n=>Number.isFinite(n)&&n>=0&&n<=10000))throw Error('单价必须是 0–10000 的有效数字。');
  return {input,cachedInput,output};
}
// 最长公共子序列生成逐行差异；仅显示文本，不渲染模型返回的 HTML。
function diff(before,after){
  if(!after)return '等待候选';
  const a=before.split('\n'),b=after.split('\n');
  if(a.length>500||b.length>500)return '源码过长，请查看完整候选与报告。';
  const dp=Array.from({length:a.length+1},()=>new Uint16Array(b.length+1));
  for(let i=a.length-1;i>=0;i--)for(let j=b.length-1;j>=0;j--)dp[i][j]=a[i]===b[j]?1+dp[i+1][j+1]:Math.max(dp[i+1][j],dp[i][j+1]);
  let i=0,j=0;const lines=[];
  while(i<a.length||j<b.length){if(i<a.length&&j<b.length&&a[i]===b[j]){lines.push('  '+a[i++]);j++;}
    else if(j<b.length&&(i===a.length||dp[i][j+1]>=dp[i+1][j]))lines.push('+ '+b[j++]);else lines.push('- '+a[i++]);}
  return lines.join('\n');
}
async function history(){
  const records=await api('/api/runs');$('history').replaceChildren(node('option','选择历史记录'));
  $('history').firstChild.value='';
  for(const r of records){const option=node('option',`${new Date(r.createdAt).toLocaleString('zh-CN')} · ${states[r.status]||r.status} · ${r.mode}`);option.value=r.id;$('history').append(option);}
  $('history').value=run?.id||'';
}
function render(){
  if(!run)return;
  $('workspace').hidden=false;
  text('state',states[run.status]||run.status);
  text('run-error',run.error||'');
  const known=run.usage.unknownCalls===0;
  const activeMs=run.activeMs+run.stages.filter(s=>s.durationMs===null).reduce((sum,s)=>sum+Date.now()-Date.parse(s.startedAt),0);
  const metrics=[['执行耗时（不含人工等待）',elapsed(activeMs)],['模型调用',`${run.usage.calls} / 2`],
    ['输入 Token（含缓存）',run.usage.calls?`${run.usage.input.toLocaleString()}${known?'':' + 未知'}`:'未调用'],
    ['输出 Token',run.usage.calls?`${run.usage.output.toLocaleString()}${known?'':' + 未知'}`:'未调用'],
    ['折算费用 · USD',run.costs.usd===null?'未折算':`$${run.costs.usd.toFixed(6)}`]];
  $('metrics').replaceChildren(...metrics.map(([label,value])=>{const box=node('div',undefined,'metric');box.append(node('span',label),node('strong',value));return box;}));
  text('cost-note',`${run.costs.note} 缓存输入：${run.usage.cachedInput} Token。${run.usage.unknownCalls?'存在未返回用量的调用，不能把已知小计当作完整成本。':''}`);
  $('attempts').replaceChildren(...(run.attempts.length?run.attempts.map(a=>node('p',`调用 ${a.number} · ${a.status} · ${elapsed(a.durationMs)} · ${a.usage?`输入 ${a.usage.input}（缓存 ${a.usage.cachedInput}），输出 ${a.usage.output} Token`:'用量未知，不能计作零消费'}`)):[node('p','未调用模型。')]));
  $('timeline').replaceChildren(...run.stages.map(s=>node('div',`${phases[s.name]} · ${s.durationMs===null?'执行中':elapsed(s.durationMs)} · ${s.status}`,`stage ${s.status==='failed'?'fail':''}`)));
  const key=run.id+':'+(run.candidate?.sha256||'');
  if(key!==lastCandidate){$('approve').checked=false;$('review').value=run.review?.note||'';lastCandidate=key;}
  text('summary',run.candidate?.summary||'旧版失败结果确定后，再生成代码；当前没有候选。');
  $('risks').replaceChildren(...(run.candidate?.risks||[]).map(x=>node('li',x)));
  text('before',run.sourceBefore);text('candidate',run.candidate?.source||'等待生成');text('diff',diff(run.sourceBefore,run.candidate?.source));
  text('mode-note',run.mode==='codex'?`真实模型路径：${run.execution.model}；模型只返回代码，不执行测试。${run.progress||''}`:'当前为仓库夹具演练，不调用 AI、不产生模型 Token；Java 编译与验证仍实际执行。');
  $('generate').textContent=run.status==='failed'?'按失败反馈重新生成':'生成候选代码';
  const canGenerate=['ready','failed'].includes(run.status)&&(run.mode!=='codex'||run.attempts.length<2);
  $('generate').disabled=requesting||!canGenerate;
  $('verify').disabled=requesting||run.status!=='review'||!$('approve').checked;
  $('accept').disabled=requesting||run.status!=='passed'||$('review').value.trim().length<4;
  $('cancel').disabled=requesting||!run.busy;
  $('create').disabled=requesting||run.busy;
  $('download').href=`/api/runs/${run.id}/report`;
  text('review-result',run.review?`验收时间：${new Date(run.review.at).toLocaleString('zh-CN')}；从候选待审到验收（含等待）：${elapsed(run.review.waitAndReviewMs)}。评审意见：${run.review.note}`:'尚未人工验收。');
  text('test-summary',`旧版 ${run.baseline?.passed??'—'}/12 → 候选 ${run.verification?.passed??'—'}/12`);
  text('diagnostic',run.verification?.diagnostic||'');$('diagnostic').hidden=!run.verification?.diagnostic;
  const baseline=run.baseline?.results||[],current=run.verification?.results||[];
  $('tests').replaceChildren(...baseline.map(c=>{
    const result=current.find(r=>r.id===c.id),tr=node('tr');
    const cell=node('td');cell.append(node('strong',c.id),node('p',c.name));
    const details=node('details');details.append(node('summary','查看输入与实际结果'),node('pre',JSON.stringify({input:c.input,expected:c.expected,beforeActual:c.actual,candidateActual:result?.actual??null},null,2)));
    const evidence=node('td');evidence.append(details);
    tr.append(cell,node('td',c.pass?'通过':'失败',c.pass?'pass':'fail'),node('td',result?(result.pass?'通过':'失败'):'未运行',result?(result.pass?'pass':'fail'):''),evidence);return tr;
  }));
}
async function mutate(action,body){
  requesting=true;render();
  try{run=await api(action==='create'?'/api/runs':`/api/runs/${run.id}/${action}`,action==='create'?body:{version:run.version,...body});
    text('notice','操作已接收，以下进度和结果均来自本机执行器。');await history();render();}
  catch(e){text('notice',`操作未完成：${e.message}`);if(run)run=await api('/api/runs/'+run.id).catch(()=>run);}
  finally{requesting=false;render();}
}
$('create').onclick=()=>{try{mutate('create',{mode:$('mode').value,notes:$('notes').value,rates:rates()});}catch(e){text('notice',e.message);}};
$('generate').onclick=()=>mutate('generate',{});
$('verify').onclick=()=>mutate('verify',{approved:$('approve').checked});
$('accept').onclick=()=>mutate('accept',{note:$('review').value});
$('cancel').onclick=()=>mutate('cancel',{});
$('approve').onchange=render;$('review').oninput=()=>{if(run)$('accept').disabled=run.status!=='passed'||$('review').value.trim().length<4||requesting;};
$('history').onchange=async()=>{if(!$('history').value)return;try{run=await api('/api/runs/'+$('history').value);render();}catch(e){text('notice',e.message);}};
$('reload').onclick=async()=>{try{await history();if(run)run=await api('/api/runs/'+run.id);render();}catch(e){text('notice',e.message);}};
try{
  config=await api('/api/config');text('requirement',config.requirement);
  text('environment',`Codex CLI：${config.cliAvailable?'已发现（登录状态以实际调用为准）':'未发现'}；Java OS 隔离环境：${config.jdkAvailable?'可进行执行检查':'不可用，验证将失败关闭'}；真实调用模型：${config.model}。`);
  text('notice','本地执行器已连接。先建立任务，复现旧版的失败用例。');await history();
  const records=await api('/api/runs');const wanted=new URL(window.location.href).searchParams.get('run');
  const latest=records.find(r=>r.id===wanted)||records.find(r=>r.mode==='codex'&&['review','passed','accepted'].includes(r.status))||records.find(r=>['review','passed','accepted'].includes(r.status))||records[0];
  if(latest){run=await api('/api/runs/'+latest.id);$('history').value=run.id;render();}
}catch(e){text('notice',`连接失败：${e.message}`);$('create').disabled=true;}
setInterval(async()=>{if(!run?.busy||requesting)return;try{const wasBusy=run.busy;run=await api('/api/runs/'+run.id);render();if(wasBusy&&!run.busy)await history();}catch(e){text('notice',`进度读取失败：${e.message}；保留上次结果，不当作成功。`);}},1200);
