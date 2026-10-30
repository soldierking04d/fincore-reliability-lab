import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {assessScript,applyAuthorizations,recoveryGate,runScenario,scenarioDefinitions} from './models.mjs';
import {auditScripts,requireSelfHostedScripts} from './asset-audit.mjs';
const tuple=(nonce,changes={})=>({chainId:'1',nonce:String(nonce),delegate:'reviewed-code',signatureValid:true,...changes});
test('CSP允许供应商域名，不阻止同源页面能力被复用',()=>{
  assert.equal(assessScript({vendorAllowed:true}).authorityExposed,true);
  assert.equal(assessScript({vendorAllowed:false}).reason,'SCRIPT_ORIGIN_DENIED');
});
test('SRI内容变化应失败；隔离必须同时约束桥接能力',()=>{
  assert.equal(assessScript({pinned:true,hashMatches:false}).reason,'INTEGRITY_MISMATCH');
  assert.equal(assessScript({isolated:true,bridgeTelemetryOnly:false}).authorityExposed,true);
  assert.equal(assessScript({isolated:true,bridgeTelemetryOnly:true}).authorityExposed,false);
  const digest=s=>'sha384-'+createHash('sha384').update(s).digest('base64');
  assert.notEqual(digest('/* reviewed telemetry fixture */'),digest('/* changed fixture */'));
});
test('50个列表项只有nonce匹配的一个生效，不产生猜nonce绕过验签',()=>{
  const result=applyAuthorizations({nonce:'49'},Array.from({length:50},(_,i)=>tuple(i)));
  assert.equal(result.results.filter(r=>r.reason==='APPLIED').length,1);assert.equal(result.nonce,'50');
  assert.equal(applyAuthorizations({nonce:'49'},[tuple(49,{signatureValid:false})]).delegate,null);
});
test('授权nonce递增且重放失败；chain_id=0明确具有跨链语义',()=>{
  assert.equal(applyAuthorizations({nonce:'1'},[tuple(1),tuple(1)]).results[1].reason,'NONCE_MISMATCH');
  assert.equal(applyAuthorizations({nonce:'1'},[tuple(1,{chainId:'137'})]).delegate,null);
  assert.equal(applyAuthorizations({nonce:'1'},[tuple(1,{chainId:'0'})],{chainId:137n}).delegate,'reviewed-code');
});
test('最后一个有效项生效；外层失败保留委托；清除委托需要新有效授权',()=>{
  const r=applyAuthorizations({nonce:'1'},[tuple(1),tuple(2,{delegate:'other-reviewed-code'})],{outerExecutionSucceeds:false});
  assert.equal(r.delegate,'other-reviewed-code');assert.equal(r.nonce,'3');
  const cleared=applyAuthorizations(r,[tuple(3,{delegate:'0x0000000000000000000000000000000000000000'})]);
  assert.equal(cleared.delegate,null);assert.equal(cleared.nonce,'4');
});
test('uint64最大值的授权nonce不可继续递增',()=>{
  assert.equal(applyAuthorizations({nonce:String((1n<<64n)-1n)},[tuple((1n<<64n)-1n)]).results[0].reason,'NONCE_RANGE');
});
const ready={oldAuthorityRevoked:true,credentialsRotated:true,cleanEnvironment:true,reconciled:true,canaryReady:true,requiredMinor:'100000',availableMinor:'100000',frozenMinor:'60000'};
test('冻结不等于追回：余额缺口阻止恢复，未知条件失败关闭',()=>{
  assert.equal(recoveryGate({...ready,availableMinor:'0'}).allowed,false);
  assert.equal(recoveryGate(ready).allowed,true);
  for(const name of ['oldAuthorityRevoked','credentialsRotated','cleanEnvironment','reconciled','canaryReady'])assert.equal(recoveryGate({...ready,[name]:false}).allowed,false);
  assert.throws(()=>recoveryGate({...ready,reconciled:undefined}));
});
test('全部回放可重复，无网络或钱包副作用',()=>{
  assert.equal(scenarioDefinitions.length,8);
  for(const s of scenarioDefinitions)assert.deepEqual(runScenario(s.id),runScenario(s.id));
  assert.throws(()=>runScenario('unknown'));
});
test('HTML盘点识别外部script但不请求或执行内容',()=>{
  assert.throws(()=>requireSelfHostedScripts('<script src="https://vendor.invalid/monitor.js"></script>'),/发行阻断/);
  assert.equal(requireSelfHostedScripts('<script src="/_next/main.js"></script>').externalCount,0);
  const result=auditScripts('<script src="https://telemetry.invalid/tag.js"></script><script src="/_next/a.js"></script>');
  assert.equal(result.externalCount,1);assert.equal(result.requiresReview,true);assert.equal(result.scripts.length,2);
});
test('固定摘要和CORS声明只表示静态条件齐备，不是执行认证',()=>{
  const sri='sha384-'+createHash('sha384').update('reviewed fixture').digest('base64');
  const result=auditScripts(`<script src="https://telemetry.invalid/tag.js" integrity="${sri}" crossorigin="anonymous"></script>`);
  assert.equal(result.requiresReview,false);assert.match(result.limitation,/未验证CORS/);
});
test('移除查询参数防公开报告带入URL凭据，动态加载保持未核验',()=>{
  const result=auditScripts('<script src="https://telemetry.invalid/tag.js?token=private"></script>');
  assert.doesNotMatch(JSON.stringify(result),/private|\?token/);assert.match(result.limitation,/动态加载/);
});
