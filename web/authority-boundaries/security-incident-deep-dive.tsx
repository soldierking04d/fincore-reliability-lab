'use client';
import {useId,useState} from 'react';
import {authoritySequences,authoritySigningStates,type AuthoritySequence} from '@/lib/authority-technical';
import sources from '@/public/authority-boundaries/source-excerpts.json';

/** 每个消息独占一行；长图可放大和展开，红色拒绝分支与绿色正常流分开。 */
function DetailedSequence({sequence}:{sequence:AuthoritySequence}){
  const marker='detail-'+useId().replace(/:/g,'');
  const [zoom,setZoom]=useState(1);
  const [expanded,setExpanded]=useState(false);
  const {actors,roles,messages}=sequence;
  const width=actors.length*210+80;
  const height=messages.length*92+150;
  const x=(index:number)=>145+index*210;
  return <figure className="mt-4">
    <div className="mb-2 flex flex-wrap items-center gap-2 text-xs"><span className="mr-auto text-slate-500">从上到下读；箭头是消息，竖虚线是服务生命周期</span>{[0.85,1,1.25].map(value=><button type="button" key={value} aria-pressed={zoom===value} onClick={()=>setZoom(value)} className={`rounded border px-3 py-1 ${zoom===value?'border-emerald-700 bg-emerald-100':'border-slate-200 bg-white'}`}>{Math.round(value*100)}%</button>)}<button type="button" onClick={()=>setExpanded(!expanded)} className="rounded border border-slate-200 bg-white px-3 py-1">{expanded?'收起长图':'展开整张长图'}</button></div>
    <div className={`overflow-auto rounded-xl border border-emerald-900/15 bg-white ${expanded?'':'max-h-[680px]'}`} tabIndex={0} aria-label={`${sequence.title}，可横向与纵向滚动`}>
      <svg width={Math.round(width*zoom)} height={Math.round(height*zoom)} viewBox={`0 0 ${width} ${height}`} role="img" aria-label={sequence.title}>
        <defs>{[['normal','#42846b'],['reject','#bc4c45'],['return','#64748b']].map(([name,color])=><marker key={name} id={`${marker}-${name}`} markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 Z" fill={color}/></marker>)}</defs>
        <rect width={width} height={height} fill="#fff"/>
        {actors.map((actor,index)=><g key={actor}><rect x={x(index)-91} y="18" width="182" height="64" rx="9" fill="#edf7f0" stroke="#91b19c"/><text x={x(index)} y="44" textAnchor="middle" fontSize="16" fontWeight="600" fill="#254f41">{actor}</text><text x={x(index)} y="65" textAnchor="middle" fontSize="12" fill="#597167">{roles[index]}</text><line x1={x(index)} y1="84" x2={x(index)} y2={height-25} stroke="#b9cbbb" strokeDasharray="5 4"/></g>)}
        {messages.map((message,index)=>{
          const y=140+index*92;
          const tone=message.kind==='reject'?'reject':message.kind==='return'?'return':'normal';
          const color=tone==='reject'?'#a63835':tone==='return'?'#64748b':'#345e48';
          const self=message.from===message.to;
          const center=self?Math.min(x(message.from)+80,width-330):(x(message.from)+x(message.to))/2;
          return <g key={index}>
            {message.kind==='reject'&&<rect x="20" y={y-30} width={width-40} height="77" rx="8" fill="#fff1ee"/>}
            {message.kind==='check'&&<rect x="20" y={y-30} width={width-40} height="77" rx="8" fill="#f0f8f3"/>}
            {self?<path d={`M${x(message.from)} ${y} h90 v22 h-90`} fill="none" stroke={color} strokeWidth="1.7" markerEnd={`url(#${marker}-${tone})`}/>:<line x1={x(message.from)} y1={y} x2={x(message.to)+(message.from>message.to?4:-4)} y2={y} stroke={color} strokeWidth="1.7" strokeDasharray={message.kind==='return'?'6 4':undefined} markerEnd={`url(#${marker}-${tone})`}/>}
            <text x={center} y={y-12} textAnchor="middle" fontSize="14" fontWeight="600" fill={color} stroke="#fff" strokeWidth="4" paintOrder="stroke">{message.label}</text>
            <text x={width/2} y={y+43} textAnchor="middle" fontSize="12" fill="#586b60" stroke="#fff" strokeWidth="3" paintOrder="stroke">{message.note}</text>
          </g>;
        })}
      </svg>
    </div>
    <figcaption className="mt-2 text-xs leading-6 text-slate-500">绿色底：校验；红色底：拒绝或保持关闭；虚线箭头：返回。图是建议防御流程，非事故平台内部实录。</figcaption>
  </figure>;
}

/** 将“授权写入”和“外层调用回滚”分成两条泳道，防止读者误以为回滚覆盖授权列表。 */
function DelegationStateDiagram(){
  const marker='delegation-'+useId().replace(/:/g,'');
  const node=(x:number,y:number,title:string,sub:string,warning=false)=><g><rect x={x} y={y} width="270" height="78" rx="10" fill={warning?'#fff0ec':'#eff8f2'} stroke={warning?'#ca7f6a':'#7aa68b'}/><text x={x+135} y={y+30} textAnchor="middle" fontSize="16" fill="#345d47">{title}</text><text x={x+135} y={y+55} textAnchor="middle" fontSize="12" fill="#617769">{sub}</text></g>;
  const edge=(x1:number,y1:number,x2:number,y2:number,label:string)=><g><line x1={x1} y1={y1} x2={x2} y2={y2} stroke="#42846b" strokeWidth="2" markerEnd={`url(#${marker})`}/><text x={(x1+x2)/2} y={(y1+y2)/2-10} textAnchor="middle" fontSize="13" fill="#345d47" stroke="#fff" strokeWidth="5" paintOrder="stroke">{label}</text></g>;
  return <div className="mt-4 overflow-x-auto rounded-xl border border-emerald-900/15 bg-white p-3"><svg width="1180" height="600" viewBox="0 0 1180 600" role="img" aria-label="7702授权阶段先写持久委托与nonce；外层调用失败仅回滚调用效果；新有效零地址授权才能清除委托">
    <defs><marker id={marker} markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0 0 L8 4 L0 8 Z" fill="#42846b"/></marker></defs>
    <rect x="8" y="35" width="1164" height="240" rx="14" fill="#f5faf5"/><rect x="8" y="308" width="1164" height="170" rx="14" fill="#fffbf6"/>
    <text x="25" y="26" fontSize="16" fill="#345d47">A / 授权处理阶段：有效签名、链、nonce和账户代码资格</text>
    {node(25,65,'账户状态：nonce=n','当前委托可能为空，也可能已有委托')}{node(440,65,'逐项校验授权元组','链 / nonce范围 / 恢复签名 / 代码资格')}{node(870,65,'有效：写入委托并递增nonce','代码标记指向批准地址；状态可持久存在')}
    {edge(295,104,436,104,'取下一项')}{edge(710,104,866,104,'有效')}
    {node(440,180,'无效：本项跳过','继续核验后面的项；不凭nonce绕过签名',true)}{edge(575,143,575,176,'无效')}
    <text x="25" y="299" fontSize="16" fill="#916343">B / 外层执行阶段：执行结果与授权持久性分别判断</text>
    {node(25,348,'执行外层destination / data','授权列表已经处理，委托不局限于本次调用')}{node(440,348,'执行成功','调用效果提交；委托仍在')}{node(870,348,'执行revert','调用效果回滚；已处理委托仍在',true)}
    <path d={`M1005 143 V285 H160 V344`} fill="none" stroke="#42846b" strokeWidth="2" markerEnd={`url(#${marker})`}/><text x="575" y="280" textAnchor="middle" fontSize="13" fill="#345d47">授权处理完成 → 进入外层执行</text>
    {edge(295,387,436,387,'成功')}
    <path d={`M160 426 V459 H1005 V430`} fill="none" stroke="#b96650" strokeWidth="2" markerEnd={`url(#${marker})`}/><text x="790" y="450" textAnchor="middle" fontSize="13" fill="#9a503d">失败分支：只回滚调用效果</text>
    {node(440,510,'清除：新的有效零地址授权','须符合当前nonce与签名要求；另做状态回读')}
    <path d={`M1140 426 V560 H714`} fill="none" stroke="#64748b" strokeDasharray="5 4" markerEnd={`url(#${marker})`}/>
    <path d={`M575 426 V506`} fill="none" stroke="#64748b" strokeDasharray="5 4" markerEnd={`url(#${marker})`}/><text x="25" y="505" fontSize="13" fill="#916343">清理路径是独立操作：</text><text x="745" y="548" fontSize="13" fill="#916343">疑似密钥失守时，还须重建可信密钥根。</text>
  </svg></div>;
}

export function SecurityIncidentDeepDive(){
  return <div className="mt-6 space-y-4">
    <div className="rounded-xl border border-emerald-800/20 bg-white p-4"><h4 className="text-lg font-semibold">详细技术链路：每一步检查什么，失败后怎么办</h4><p className="mt-2 text-sm leading-7 text-slate-600">先选链路，再沿箭头看数据、权限、状态和异常分支。图下的验收表把“应当做到”和“已运行验证”分开。长图支持85% / 100% / 125%缩放和整图展开。</p><nav className="mt-3 flex flex-wrap gap-3 text-sm text-emerald-800" aria-label="详细技术链路"><a href="#authority-browser-detail">浏览器</a><a href="#authority-ops-detail">运维隔离</a><a href="#authority-signing-detail">逐笔签名</a><a href="#authority-7702-detail">7702状态</a><a href="#authority-recovery-detail">恢复协作</a></nav></div>
    {authoritySequences.map(sequence=><details key={sequence.id} id={`authority-${sequence.id}-detail`} className="scroll-mt-24 rounded-xl border border-emerald-900/15 bg-white/80 p-4" open={sequence.id==='browser'}>
      <summary className="cursor-pointer text-base font-semibold">{sequence.title} · {sequence.messages.length}步</summary><p className="mt-3 text-sm leading-7 text-slate-600">{sequence.scope}</p>
      <DetailedSequence sequence={sequence}/>
      <div className="mt-4 space-y-3">{sequence.checks.map(check=><article key={check.point} className="rounded-lg border border-slate-200 p-4"><h5 className="font-semibold">{check.point}</h5><dl className="mt-3 grid gap-2 text-sm leading-7 md:grid-cols-[76px_1fr]"><dt className="text-emerald-800">机制</dt><dd>{check.mechanism}</dd><dt className="text-rose-800">失效方式</dt><dd>{check.failure}</dd><dt className="text-emerald-800">验收证据</dt><dd>{check.evidence}</dd><dt className="text-amber-800">落地范围</dt><dd>{check.implementation}</dd></dl></article>)}</div>
      {sequence.id==='signing'&&<div className="mt-5 overflow-x-auto"><h5 className="font-semibold">签名、广播与资金状态：重试和释放边界</h5><table className="mt-3 min-w-[850px] w-full text-left text-sm"><thead><tr className="bg-emerald-50">{['状态','已知事实','允许的重试','资金处理'].map(t=><th key={t} className="p-3">{t}</th>)}</tr></thead><tbody>{authoritySigningStates.map(row=><tr key={row.state} className="border-b border-slate-200 align-top"><td className="p-3 font-mono text-xs">{row.state}</td><td className="p-3">{row.meaning}</td><td className="p-3">{row.retry}</td><td className="p-3">{row.release}</td></tr>)}</tbody></table></div>}
    </details>)}
    <details id="authority-7702-detail" className="scroll-mt-24 rounded-xl border border-emerald-900/15 bg-white/80 p-4"><summary className="cursor-pointer text-base font-semibold">7702：授权列表、持久委托与回滚边界状态图</summary><DelegationStateDiagram/><div className="mt-4 space-y-3 text-sm leading-7"><p>授权元组包含chain_id、委托地址、nonce与签名。外层交易发起人与授权账户可以不同；支付gas不是获得他人授权的凭据。链为0的语义需单独审查，不能默认当作仅本链。</p><p>协议先处理有效外层交易的发送方nonce，再处理授权列表。若发送方与授权账户相同，必须按此顺序计算所需nonce。本地模型显式从“授权处理开始时”的账户nonce=49输入，不模拟外层交易发送方递增。</p><p>结构或数值编码越界可使整个交易无效；“无效项跳过”指进入授权处理后的逐项语义校验失败。同一账户多个有效项按顺序影响nonce，最后一个有效项决定委托。有效授权nonce必须小于2⁶⁴−1；模型省略真实签名恢复、RLP、gas与账户代码资格。</p><p>委托不是代币allowance；清除委托也不会自动补回已转出的资产或证明密钥安全。撤销后需在确认块回读代码委托状态，并盘点其它链和其它授权。</p><a className="text-emerald-800" href="https://eips.ethereum.org/EIPS/eip-7702" target="_blank" rel="noreferrer">核对协议规范 ↗</a></div></details>
    <details className="rounded-xl border border-emerald-900/15 bg-white/80 p-4"><summary className="cursor-pointer font-semibold">真实Java代码逐段阅读：审批、签名检查与规范编码</summary><p className="mt-3 text-sm leading-7 text-slate-600">下方直接取自已编译验证的下载源码，不是阅读用伪代码。使用临时Ed25519密钥；没有真实交易解码、数据库、钱包连接或HSM接入。审批与签名同进程，只验证逻辑，不证明信任域隔离。</p><p className="mt-2 break-all font-mono text-xs text-slate-500">源码SHA-256：{sources.sha256}</p>{sources.excerpts.map(item=><div key={item.title} className="mt-4"><h5 className="font-semibold">{item.title}</h5><pre className="mt-2 max-h-[460px] overflow-auto rounded-lg bg-slate-950 p-4 text-xs leading-6 text-slate-100"><code>{item.code}</code></pre></div>)}<a className="mt-4 inline-block text-sm text-emerald-800" href="/authority-boundaries/SigningIntentGuard.java">查看完整源文件</a></details>
  </div>;
}
