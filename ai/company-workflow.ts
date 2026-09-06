/**
 * 普通公司需求的 AI 辅助演示：问题 → 当前资料 → 候选草稿 → 人工采用反馈。
 * 这里使用合成资料和固定候选，没有调用模型。可运行部分是来源有效性、声明校验与反馈记录。
 * 目的不是让规则冒充 AI，而是让业务方先验证工作流与维护成本，再决定是否接入真实模型。
 */
export type UseCase = 'support'|'knowledge'|'requirements';
export type Condition = 'normal'|'missing'|'outdated'|'invented';
export type Source = {id:string; title:string; version:string; current:boolean; points:{id:string; text:string}[]};
export type Example = {id:UseCase; title:string; question:string; before:string; benefit:string; downside:string; sources:Source[]; unknowns:string[]};
export const examples:Example[] = [
  {id:'support', title:'客服回复辅助', question:'客户说验证码收不到，已经连续点了三次，应该怎样回复？',
    before:'客服翻帮助中心、查群公告，再手写回复；同一个问题重复整理。',
    benefit:'减少找资料和组织文字的时间，新客服更容易给出一致回复。',
    downside:'资料过期会被反复引用；草稿太长或不准确，反而增加客服复核时间。',
    sources:[
      {id:'S1',title:'短信验证码帮助说明（教学样本）',version:'v3',current:true,points:[
        {id:'check_number',text:'先请客户核对手机号与区号，并检查短信拦截设置。'},
        {id:'wait_before_retry',text:'避免连续点击；教学规则为等待 60 秒后再尝试一次。'}]},
      {id:'S2',title:'客服升级处理说明（教学样本）',version:'v2',current:true,points:[
        {id:'escalate_support',text:'仍未收到时，由客服记录脱敏问题和发生时间，交支持人员核查发送记录。'}]},
    ],unknowns:['目前没有查询真实短信发送记录，不能判断具体失败原因，也不能保证送达时间。']},
  {id:'knowledge',title:'内部知识库问答',question:'我是新加入的同事，要申请测试环境，应该准备什么、找谁？',
    before:'新人搜索多个文档或打断同事询问，老员工重复解释相同流程。',
    benefit:'减少新人找资料的时间，把经常被问的问题沉淀为可维护的知识。',
    downside:'旧流程和新流程冲突时可能给错路径；大家不再更新文档，答案会逐步失真。',
    sources:[
      {id:'K1',title:'测试环境申请指南（教学样本）',version:'v2',current:true,points:[
        {id:'prepare_request',text:'准备项目名称、环境用途、预计使用期限与所属团队。'},
        {id:'submit_request',text:'向内部服务台提交申请，由对应团队负责人确认资源需求。'}]},
      {id:'K2',title:'新人协作手册（教学样本）',version:'v1',current:true,points:[
        {id:'track_request',text:'在申请工单中跟踪进度；需要加急时说明业务影响，由服务台协调。'}]},
    ],unknowns:['没有读取实际资源余量和申请进度，因此不能承诺开通时间。']},
  {id:'requirements',title:'需求整理与测试草案',question:'运营想让活动邮件支持预约发送，还能取消。帮忙整理开发前要确认的规则和测试。',
    before:'产品口头描述，研发边做边补问题，QA 直到联调才发现边界没说清楚。',
    benefit:'更早列出遗漏问题与测试候选，减少整理工作和部分需求往返。',
    downside:'模型容易替产品“补全”未确认规则，生成很多看似完整却无效的测试。',
    sources:[
      {id:'R1',title:'活动预约发送需求纪要（教学样本）',version:'v1',current:true,points:[
        {id:'schedule_time',text:'已确认：预约发送需要选择未来时间，并明确使用的时区。'},
        {id:'cancel_pending',text:'已确认：草稿和尚未开始发送的预约可以取消。'}]},
      {id:'R2',title:'活动质量要求（教学样本）',version:'v1',current:true,points:[
        {id:'prevent_duplicate',text:'已确认：同一活动面向同一收件人的重复触发不能产生重复发送。'}]},
    ],unknowns:['待产品确认：发送中是否允许取消、部分失败如何重试、变更预约时间的截止条件。',
      '测试候选：过去时间和时区边界、预约后取消、到点与取消同时发生、重复触发。候选不等于已执行测试。']},
];
export const conditions:{id:Condition;label:string;effect:string}[]=[
  {id:'normal',label:'资料完整',effect:'查看带来源的正常草稿'},
  {id:'missing',label:'找不到相关资料',effect:'没有依据时不编答案'},
  {id:'outdated',label:'关键资料已过期',effect:'显示维护任务，不沿用旧口径'},
  {id:'invented',label:'候选擅自做了承诺',effect:'独立规则拒绝无来源声明'},
];
export type DraftPoint={sourceId:string;pointId:string;text:string};
export type Session={
  useCase:UseCase; condition:Condition; stage:'input'|'retrieved'|'drafted'|'ready'|'blocked'|'feedback';
  sources:Source[]; candidate:DraftPoint[]; feedback:'adopted'|'edited'|'manual'|null; editNote:string;
  code:string; message:string; trace:string[];
};
export function startSession(useCase:UseCase='support',condition:Condition='normal'):Session {
  return {useCase,condition,stage:'input',sources:[],candidate:[],feedback:null,editNote:'',code:'READY',
    message:'先找相关且有效的资料，再组织回复。',trace:[]};
}
function update(s:Session,changes:Partial<Session>,message:string):Session {
  return {...s,...changes,message,trace:[...s.trace,message]};
}
/** 来源是固定合成记录。生产检索应在获准文档内先过滤，再检索，保留文档版本。 */
export function nextStep(s:Session):Session {
  const example=examples.find(e=>e.id===s.useCase)!;
  if(s.stage==='input') {
    let sources=example.sources.map(x=>({...x,points:x.points.map(p=>({...p}))}));
    if(s.condition==='missing') sources=[];
    if(s.condition==='outdated') sources[0].current=false;
    return update(s,{stage:'retrieved',sources,code:'RETRIEVED'},'检索已完成：保留来源、版本与有效状态。此处是合成检索结果，不是联网搜索。');
  }
  if(s.stage==='retrieved') {
    if(!s.sources.length) return update(s,{stage:'blocked',code:'NO_SOURCE'},'没有相关资料，不生成答案。转给原负责人处理，并记录知识缺口。');
    if(s.sources.some(d=>!d.current)) return update(s,{stage:'blocked',code:'STALE_SOURCE'},'关键资料已过期。先请文档负责人更新，不让流畅草稿放大旧规则。');
    const candidate=s.sources.flatMap(d=>d.points.map(p=>({sourceId:d.id,pointId:p.id,text:p.text})));
    if(s.condition==='invented') candidate.push({sourceId:'UNKNOWN',pointId:'guarantee',text:'一定会在 10 秒内处理成功，无需任何人工确认。'});
    return update(s,{stage:'drafted',candidate,code:'DRAFTED'},'固定候选已生成，尚未校验。真实模型未来可替换这个生成环节，不替代来源与人工判断。');
  }
  if(s.stage==='drafted') {
    const issue=validateDraft(s);
    return issue?update(s,{stage:'blocked',code:issue},'候选含无来源或不符合原文的声明，未展示为可采用回复。请人工处理并记录错误样本。')
      :update(s,{stage:'ready',code:'REVIEW_READY'},'来源与受控声明校验通过。请阅读未知项，再选择采用、修改后采用或转人工。');
  }
  return s;
}
/**
 * 只验证当前教学声明合同：每条必须精确对应合成资料。
 * 真实模型可自由改写文本，不能套用这个规则声称语义正确；需要独立标注、真实模型评测与抽检。
 */
export function validateDraft(s:Session):string|null {
  const expected=examples.find(e=>e.id===s.useCase)!;
  if(!s.sources.length || s.sources.some(d=>!d.current)) return 'SOURCE_INVALID';
  if(s.sources.length!==expected.sources.length || !expected.sources.every(e=>s.sources.some(d=>
    d.id===e.id && d.title===e.title && d.version===e.version && d.points.length===e.points.length &&
    e.points.every(ep=>d.points.some(p=>p.id===ep.id&&p.text===ep.text))))) return 'SOURCE_INVALID';
  const expectedCount=s.sources.reduce((n,d)=>n+d.points.length,0);
  if(s.candidate.length!==expectedCount) return 'CLAIM_REJECTED';
  const seen=new Set<string>();
  for(const p of s.candidate) {
    if(!p||Object.keys(p).sort().join(',')!=='pointId,sourceId,text')return 'CLAIM_REJECTED';
    const source=s.sources.find(d=>d.id===p.sourceId);
    if(!source?.points.some(e=>e.id===p.pointId && e.text===p.text)) return 'CLAIM_REJECTED';
    const key=JSON.stringify([p.sourceId,p.pointId]);if(seen.has(key))return 'CLAIM_REJECTED';seen.add(key);
  }
  return null;
}
/** 反馈只在当前页面内存记录；不会发信、改工单或把输入上传。修改时必须填写修改原因。 */
export function recordFeedback(s:Session,feedback:'adopted'|'edited'|'manual',editNote=''):Session {
  if(s.feedback) return s;
  if(feedback==='manual' && ['ready','blocked'].includes(s.stage)) return update(s,{stage:'feedback',feedback,code:'MANUAL'},'已记录转人工；计入使用分母，不伪装成 AI 解决成功。');
  if(s.stage!=='ready'||validateDraft(s)) return update(s,{stage:'blocked',code:'REVIEW_REQUIRED'},'还没有可复核草稿，不能记录采用。');
  if(feedback==='edited'&&!editNote.trim())return {...s,message:'请填写需要改动的地方；不能把返工当作直接采用。'};
  return update(s,{stage:'feedback',feedback,editNote:editNote.trim().slice(0,500),code:feedback==='edited'?'EDITED':'ADOPTED'},
    feedback==='edited'?'已记录“修改后采用”及返工说明，计入后续维护。没有发送任何消息。':'已记录教学采用结果。没有发送任何消息；实际效果仍需公司试点衡量。');
}
export function runExample(useCase:UseCase,condition:Condition) {
  let s=startSession(useCase,condition);
  for(let i=0;i<3;i++)s=nextStep(s);
  return s;
}
/** 所有参数为试点前的假设，成本包含复核、失败、维护和建设分摊，不是供应商报价。 */
export function estimateValue(adoption=70,usable=90) {
  const used=2000*0.6*adoption/100;
  const manualMinutes=8;
  const assistedMinutes=usable/100*(3+0.25*2)+(1-usable/100)*9;
  const hours=used*(manualMinutes-assistedMinutes)/60;
  const value=hours*100;
  const variable=used*0.2;
  const operations=500+8*100;
  const setup=12000/12;
  return {used,hours,value,variable,operations,setup,net:Math.round(value-variable-operations-setup)};
}
