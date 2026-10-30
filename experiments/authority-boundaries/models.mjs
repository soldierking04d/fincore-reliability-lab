/** 所有账户、数值和授权均为合成输入。这里只实现协议语义教学，不生成或广播以太坊交易。 */
export const scenarioDefinitions = [
  {id:'vendor-domain',title:'监控域名仍可信，脚本内容已变',group:'浏览器',result:'暴露',description:'CSP 允许供应商域名，却没有固定资源内容或隔离交易能力。'},
  {id:'vendor-pinned',title:'固定摘要拒绝变化的脚本',group:'浏览器',result:'阻断',description:'独立审查并固定摘要；字节发生变化就不加载，不自动刷新摘要。'},
  {id:'vendor-isolated',title:'隔离遥测，不交出 signer',group:'浏览器',result:'阻断',description:'独立源的遥测通道只能提交白名单统计，不能借桥接对象调用交易。'},
  {id:'nonce-batch',title:'50 个授权项：无效跳过，持久委托',group:'授权',result:'持久生效',description:'用协议语义模型说明 nonce 和签名都必须满足；不是链上攻击复现。'},
  {id:'nonce-no-signature',title:'猜对 nonce，但没有有效签名',group:'授权',result:'阻断',description:'nonce 不是密码，猜中也不能凭空获得账户授权。'},
  {id:'revert-persistence',title:'外层交易失败，不代表委托撤回',group:'授权',result:'持久生效',description:'授权列表先处理，外层执行回滚不清除已生效委托。'},
  {id:'recovery-frozen',title:'资产被冻结，但没有回到我方控制',group:'恢复',result:'不可恢复提现',description:'冻结状态、用户负债和可用资产分开，避免把冻结当到账。'},
  {id:'recovery-ready',title:'隔离、撤销、轮换和资金核对后恢复',group:'恢复',result:'可小额验证',description:'条件是合成演练，不证明真实平台已经完成恢复。'},
];

export function assessScript({page='trade',vendorAllowed=true,external=true,pinned=false,hashMatches=false,isolated=false,bridgeTelemetryOnly=false}) {
  if(external&&!vendorAllowed)return {decision:'blocked',reason:'SCRIPT_ORIGIN_DENIED',authorityExposed:false};
  if(pinned&&!hashMatches)return {decision:'blocked',reason:'INTEGRITY_MISMATCH',authorityExposed:false};
  const authorityExposed=page==='trade'&&external&&!(isolated&&bridgeTelemetryOnly);
  return {decision:authorityExposed?'exposed':'contained',reason:authorityExposed?'SAME_PAGE_AUTHORITY':'NO_TRADING_CAPABILITY',authorityExposed};
}

/** EIP-7702 的列表处理次序；signatureValid 是测试桩，不是密码学验签。 */
export function applyAuthorizations(initial, tuples, {chainId=1n,outerExecutionSucceeds=true}={}) {
  const account={nonce:BigInt(initial.nonce),delegate:initial.delegate??null};
  const results=[];
  for(const [index,t] of tuples.entries()){
    let reason='APPLIED';
    if(!t.signatureValid)reason='INVALID_SIGNATURE';
    else if(BigInt(t.chainId)!==0n&&BigInt(t.chainId)!==chainId)reason='CHAIN_MISMATCH';
    else if(BigInt(t.nonce)<0n||BigInt(t.nonce)>=(1n<<64n)-1n)reason='NONCE_RANGE';
    else if(BigInt(t.nonce)!==account.nonce)reason='NONCE_MISMATCH';
    if(reason==='APPLIED'){account.delegate=t.delegate==='0x0000000000000000000000000000000000000000'?null:t.delegate;account.nonce++;}
    results.push({index,reason,nonce:String(account.nonce),delegate:account.delegate});
  }
  return {nonce:String(account.nonce),delegate:account.delegate,outerExecutionSucceeds,results,
    note:'已处理的委托不随外层执行失败回滚；本模型省略gas、地址恢复、账户代码资格、RLP和真实签名校验。'};
}

/** 恢复门禁必须核对事实状态；冻结资产不参与可用资金加总。所有金额用最小单位整数。 */
export function recoveryGate({oldAuthorityRevoked,credentialsRotated,cleanEnvironment,reconciled,canaryReady,requiredMinor,availableMinor,frozenMinor}) {
  const flags={oldAuthorityRevoked,credentialsRotated,cleanEnvironment,reconciled,canaryReady};
  if(Object.values(flags).some(value=>typeof value!=='boolean'))throw Error('恢复条件不能未知当通过');
  const required=BigInt(requiredMinor),available=BigInt(availableMinor),frozen=BigInt(frozenMinor);
  if(required<0n||available<0n||frozen<0n)throw Error('金额不能为负');
  const missing=Object.entries(flags).filter(([,v])=>!v).map(([key])=>key);
  if(available<required)missing.push('CONTROLLED_ASSET_SHORTFALL');
  return {allowed:missing.length===0,missing,availableMinor:String(available),frozenMinor:String(frozen),
    frozenCountsAsAvailable:false,scope:'仅教学验收门禁；不自行解除真实提现限制。'};
}

export function runScenario(id) {
  if(!scenarioDefinitions.some(s=>s.id===id))throw Error('未知场景');
  const timeline=[];
  let detail;
  if(id.startsWith('vendor-')){
    const policy={pinned:id==='vendor-pinned',hashMatches:false,isolated:id==='vendor-isolated',bridgeTelemetryOnly:id==='vendor-isolated'};
    detail=assessScript(policy);
    timeline.push({step:'供应商更新资源',fact:'域名不变、脚本字节改变；本例不加载任何外部代码。'},
      {step:'资源与能力校验',fact:detail.reason},
      {step:'交易权限结果',fact:detail.authorityExposed?'监控脚本可触达同页能力，暴露未被解决。':'本模型没有把交易能力交给不可信脚本。'});
  }else if(['nonce-batch','nonce-no-signature','revert-persistence'].includes(id)){
    // 旧项 nonce 0..48 失效，49命中后递增至50，示例只表达列表处理规则。
    const tuples=Array.from({length:id==='nonce-batch'?50:1},(_,i)=>({chainId:'1',nonce:String(id==='nonce-batch'?i:49),signatureValid:id!=='nonce-no-signature',delegate:'illustrative-delegate'}));
    detail=applyAuthorizations({nonce:'49'},tuples,{outerExecutionSucceeds:id!=='revert-persistence'});
    timeline.push({step:'授权前',fact:'当前账户 nonce=49，委托为空；数字为合成输入。'},
      {step:'逐项核验',fact:`生效 ${detail.results.filter(r=>r.reason==='APPLIED').length} 项；无效项直接跳过。`},
      {step:'授权后',fact:`nonce=${detail.nonce}；委托=${detail.delegate||'无'}；外层${detail.outerExecutionSucceeds?'成功':'失败'}不决定已处理委托是否保留。`});
  }else{
    const ready=id==='recovery-ready';
    detail=recoveryGate({oldAuthorityRevoked:ready,credentialsRotated:ready,cleanEnvironment:ready,reconciled:ready,canaryReady:ready,
      requiredMinor:'100000',availableMinor:ready?'100000':'0',frozenMinor:'60000'});
    timeline.push({step:'损失确认',fact:'用户负债100000最小单位不因资产转出自动消失。'},
      {step:'冻结与控制权',fact:'60000已冻结但未返还，不计为可用资产。'},
      {step:'恢复判断',fact:detail.allowed?'各项条件满足，仅进入小额金丝雀验证，不自动全量开放。':`仍缺少：${detail.missing.join('、')}`} );
  }
  return {id,timeline,detail,scope:'确定性教学回放，不是对真实事故的攻击复现或修复证明。'};
}

export const exampleOutcomes=scenarioDefinitions.map(s=>runScenario(s.id));
