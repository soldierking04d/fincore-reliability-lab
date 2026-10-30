'use client';
import { useId } from 'react';

function Box({x,y,w=205,title,sub,warning=false}:{x:number;y:number;w?:number;title:string;sub:string;warning?:boolean}){
  return <g><rect x={x} y={y} width={w} height="80" rx="12" fill={warning?'#fff1e9':'#f0f8f4'} stroke={warning?'#ce8d68':'#6c9a86'} strokeWidth="1.5"/><text x={x+w/2} y={y+31} textAnchor="middle" fontSize="17" fontWeight="600" fill="#254f41">{title}</text><text x={x+w/2} y={y+56} textAnchor="middle" fontSize="12" fill="#597167">{sub}</text></g>;
}

/** 这是建议防御拓扑，不声称掌握事故平台的内部服务部署图。 */
export function AuthorityTopology(){
  const marker='authority-'+useId().replace(/:/g,'');
  const edge=(x1:number,y1:number,x2:number,y2:number)=><line x1={x1} y1={y1} x2={x2} y2={y2} stroke="#42846b" strokeWidth="2" markerEnd={`url(#${marker})`}/>;
  return <div className="overflow-x-auto rounded-xl border border-emerald-900/15 bg-white p-3"><svg viewBox="0 0 1060 460" className="w-full min-w-[880px]" role="img" aria-label="FinCore建议防御架构：第三方与运维入口都必须经过独立意图校验，不能直接调用签名器">
    <defs><marker id={marker} markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 Z" fill="#42846b"/></marker></defs>
    <rect x="8" y="36" width="510" height="377" rx="18" fill="#fffaf5" stroke="#edd7c2" strokeDasharray="6 5"/><text x="28" y="25" fontSize="15" fill="#996444">低信任入口 · 不持有最终授权</text>
    <rect x="544" y="36" width="504" height="377" rx="18" fill="#f4faf6" stroke="#bdd7c7" strokeDasharray="6 5"/><text x="564" y="25" fontSize="15" fill="#3c735a">独立身份 / 发布域 · 签名与业务意图核对</text>
    <Box x={25} y={65} title="第三方遥测供应链" sub="固定制品 / SRI / 隔离" warning/>
    <Box x={292} y={65} title="已登录的交易页面" sub="会话不是任意交易授权"/>
    <Box x={566} y={65} title="权威业务意图与审批" sub="收款人 / 金额 / 链 / 有效期"/>
    <Box x={817} y={204} w={210} title="独立签名策略域" sub="最终字节解码 / 防重 / 限额"/>
    <Box x={25} y={307} title="运维终端 / 发布工具" sub="短期身份 / 最小权限" warning/>
    <Box x={292} y={307} title="业务服务容器" sub="无任意签名与提权能力"/>
    <Box x={566} y={307} title="身份与全局预算门禁" sub="跨实例原子预占 / 熔断"/>
    {edge(230,105,290,105)}{edge(497,105,562,105)}{edge(771,105,870,201)}
    {edge(230,347,290,347)}{edge(497,347,562,347)}{edge(771,347,870,286)}
    <path d="M394 149 L394 245 L810 245" fill="none" stroke="#bf6655" strokeWidth="2" strokeDasharray="6 4"/>
    <text x="414" y="229" fontSize="13" fill="#a14439">禁止：把 signer / 任意签名接口直接暴露给页面</text><text x="790" y="252" fontSize="27" fill="#b84e42">×</text>
    <text x="570" y="440" fontSize="14" fill="#416d58">签名后 → 广播与最终性 → 不可变账本 → 控制权与负债对账</text>
  </svg></div>;
}

export function SigningBoundarySequence(){
  const marker='intent-sequence-'+useId().replace(/:/g,'');
  const actors=['请求服务','意图库 / 审批','独立签名器','广播与账务'];
  const xs=[120,380,640,900];
  const messages=[{from:0,to:1,y:105,label:'① 请求引用已批准的业务ID'},
    {from:1,to:2,y:155,label:'② 完整字段绑定的短期审批'},
    {from:0,to:2,y:205,label:'③ 候选请求；不相信客户端自报类型'},
    {from:2,to:2,y:255,label:'④ 核对字节 / nonce / 策略代次 / 累计额度'},
    {from:2,to:3,y:315,label:'⑤ 匹配才签；广播未决与最终确认分开'}];
  return <div className="mt-4 overflow-x-auto rounded-xl border border-emerald-900/15 bg-white p-3"><svg viewBox="0 0 1060 365" className="w-full min-w-[880px]" role="img" aria-label="提现业务意图审批、最终字节复核、受限签名与广播确认时序">
    <defs><marker id={marker} markerWidth="7" markerHeight="7" refX="6" refY="3.5" orient="auto"><path d="M0,0 L7,3.5 L0,7 Z" fill="#42846b"/></marker></defs>
    {actors.map((a,i)=><g key={a}><rect x={xs[i]-95} y="10" width="190" height="45" rx="9" fill="#edf7f0" stroke="#91b19c"/><text x={xs[i]} y="39" textAnchor="middle" fontSize="17" fill="#275a43">{a}</text><line x1={xs[i]} y1="56" x2={xs[i]} y2="353" stroke="#bed0c2" strokeDasharray="5 4"/></g>)}
    {messages.map(m=><g key={m.y}>{m.from===m.to?<path d={`M${xs[m.from]} ${m.y-5} h140 v24 h-140`} fill="none" stroke="#42846b" markerEnd={`url(#${marker})`}/>:<line x1={xs[m.from]} y1={m.y} x2={xs[m.to]-4} y2={m.y} stroke="#42846b" strokeWidth="1.5" markerEnd={`url(#${marker})`}/>}<text x={m.from===m.to?660:(xs[m.from]+xs[m.to])/2} y={m.y-13} textAnchor={m.from===m.to?'start':'middle'} fontSize="13" fill="#3e6250">{m.label}</text></g>)}
  </svg></div>;
}
