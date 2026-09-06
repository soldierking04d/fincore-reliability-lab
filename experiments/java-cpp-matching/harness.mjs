// 本工具只验证独立内存模型；生产数据库、账本、网络与故障恢复均不在测量范围。
// 金额使用 BigInt，参考模型通过线性扫描选最优订单，与两个有序价位实现保持结构独立。
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../..');
const out = path.join(root, 'target/java-cpp-matching');
const MAX = (1n << 63n) - 1n;
const identifier = /^[A-Za-z0-9_]{1,40}$/;
const java = process.env.MATCHING_JAVA || 'java';
const javaArgs = ['-Xms256m', '-Xmx1g', '-XX:+UseG1GC', '-cp', path.join(out, 'classes'), 'MatchingLab'];

function run(bin, args, input, allowFailure = false) {
  const p = spawnSync(bin, args, {input, encoding:'utf8', maxBuffer:128 * 1024 * 1024,timeout:120000,env:{...process.env,LC_ALL:'C',LANG:'C'}});
  if (p.error) throw p.error;
  if (p.status !== 0 && !allowFailure) throw new Error(`${bin} ${args.join(' ')} failed (${p.status}): ${p.stderr}`);
  return p;
}
function insist(ok, message) { if (!ok) throw new Error(message); }
function digest(s) { return crypto.createHash('sha256').update(s).digest('hex'); }
function fnv(s) { let h=14695981039346656037n; for (const c of Buffer.from(s)) h=BigInt.asUintN(64,(h^BigInt(c))*1099511628211n); return h.toString(); }

class Reference {
  constructor() { this.orders=new Map(); this.requests=new Map(); this.sequence=0; }
  apply(line) {
    const t=line.trim().split(/\s+/);
    if (!((t[0]==='N' && t.length===7)||(t[0]==='C' && t.length===4)) || !t.slice(1,4).every(x=>identifier.test(x))) return 'R|?|BAD_PROTOCOL';
    const [kind,req,id,owner,side,p,q]=t; const key=t.join(' ');
    const previous=this.requests.get(req);
    if(previous) return previous.key===key ? previous.result : `R|${req}|DUPLICATE_KEY`;
    const reject=code=>`R|${req}|${code}`;
    let result;
    if(kind==='C') {
      const o=this.orders.get(id);
      result=!o?reject('NOT_FOUND'):o.owner!==owner?reject('NOT_OWNER'):o.remaining===0n?reject('TERMINAL'):`C|${req}|${id}|${o.remaining}`;
      if(result.startsWith('C|')) { o.cancelled+=o.remaining; o.remaining=0n; }
    } else {
      let price,qty;
      if(side!=='B'&&side!=='S') result=reject('BAD_SIDE');
      else if(!/^-?[0-9]+$/.test(p)||!/^-?[0-9]+$/.test(q)) result=reject('BAD_NUMBER');
      else {
        price=BigInt(p); qty=BigInt(q);
        if(price < -MAX-1n || price>MAX || qty < -MAX-1n || qty>MAX) result=reject('BAD_NUMBER');
        else if(price<=0n||qty<=0n) result=reject('NON_POSITIVE');
        else if(price*qty>MAX) result=reject('OVERFLOW');
        else if(this.orders.has(id)) result=reject('ORDER_EXISTS');
      }
      if(!result) {
        const eligible=[...this.orders.values()].filter(o=>o.remaining>0n&&o.side!==side&&(side==='B'?o.price<=price:o.price>=price));
        eligible.sort((a,b)=>a.price===b.price?a.seq-b.seq:side==='B'?(a.price<b.price?-1:1):(a.price>b.price?-1:1));
        let left=qty,projected=0n;
        for(const maker of eligible) {if(left===0n)break;if(maker.owner===owner){left=0n;break;}const n=left<maker.remaining?left:maker.remaining;projected+=n*maker.price;left-=n;}
        if(projected+left*price>MAX) result=reject('OVERFLOW');
        if(!result) {
          const order={id,owner,side,price,qty,remaining:qty,filled:0n,cancelled:0n,executed:0n,seq:this.sequence++};
          const trades=[];
          for(const maker of eligible) { if(order.remaining===0n) break; if(maker.owner===owner) {order.cancelled=order.remaining;order.remaining=0n;break;} const n=order.remaining<maker.remaining?order.remaining:maker.remaining; maker.remaining-=n; maker.filled+=n; order.remaining-=n; order.filled+=n;maker.executed+=n*maker.price;order.executed+=n*maker.price; trades.push(`${maker.id}:${n}:${maker.price}`); }
          this.orders.set(id,order);
          result=`A|${req}|${id}|${order.remaining}|${trades.join(',')||'-'}|${order.cancelled}`;
        }
      }
    }
    this.requests.set(req,{key,result}); this.verify(); return result;
  }
  verify() {
    let bestBid=0n,bestAsk=MAX; let hasAsk=false;
    for(const o of this.orders.values()) {
      insist(o.qty===o.remaining+o.filled+o.cancelled&&o.remaining>=0n&&o.filled>=0n&&o.cancelled>=0n,`quantity conservation ${o.id}`);
      insist(o.executed>=0n&&o.executed+o.remaining*o.price<=MAX,`execution notional ${o.id}`);
      if(o.remaining>0n) { if(o.side==='B'&&o.price>bestBid) bestBid=o.price; if(o.side==='S'&&o.price<=bestAsk) {bestAsk=o.price;hasAsk=true;} }
    }
    insist(!hasAsk||bestBid<bestAsk,'crossed resting book');
  }
  snapshot() {return [...this.orders.values()].sort((a,b)=>a.id<b.id?-1:1).map(o=>`${o.id}:${o.owner}:${o.side}:${o.price}:${o.qty}:${o.remaining}:${o.filled}:${o.cancelled}:${o.executed}`).join(';');}
}

// 固定 xorshift32，不依赖宿主语言的随机库；同一个文本文件重放给双方。
function workload(kind,count,seed) {
  let state=seed>>>0; const random=n=>{state^=state<<13;state^=state>>>17;state^=state<<5;return (state>>>0)%n;};
  const lines=[],known=[],requests=[];
  for(let i=0;i<count;i++) {
    let line;
    if(kind==='sweeps') {
      const phase=i%200;
      line=phase<160?`N r${i} o${i} maker${phase%17} S ${100+phase%40} ${1+random(8)}`:`N r${i} o${i} taker B 150 ${1+random(45)}`;
    } else {
      const n=random(100); const duplicate=kind==='retries'?35:10;
      if(n<duplicate&&requests.length) line=requests[random(requests.length)];
      else if(n<duplicate+17&&known.length) {const o=known[random(known.length)]; line=`C r${i} ${o.id} ${random(10)===0?'wrong':o.owner}`;}
      else {const id=`o${i}`,owner=`u${random(64)}`; line=`N r${i} ${id} ${owner} ${random(2)?'B':'S'} ${97+random(8)} ${1+random(19)}`; known.push({id,owner});}
      if(i%997===996) line=`N r${i} invalid${i} x B 9223372036854775807 2`;
    }
    lines.push(line);requests.push(line);
  }
  return lines;
}

function replay(bin,args,lines,batch=false) {return run(bin,[...args,batch?'--replay-batch':'--replay'],lines.join('\n')+'\n').stdout.trimEnd().split('\n');}
function timedProcess(bin,args) {
  const platform=os.platform();
  if(platform!=='darwin'&&platform!=='linux') return {run:run(bin,args),usage:{available:false}};
  const result=run('/usr/bin/time',[platform==='darwin'?'-p':'-v',bin,...args]);
  let usage={scope:'Whole child process INCLUDING parsing, all warmup, verification, sample output and shutdown; not engine CPU/heap.'};
  if(platform==='darwin') {
    const cpu=result.stderr.match(/real\s+([\d.]+)\s+user\s+([\d.]+)\s+sys\s+([\d.]+)/);
    usage={...usage,wallSeconds:cpu?Number(cpu[1]):null,userCpuSeconds:cpu?Number(cpu[2]):null,systemCpuSeconds:cpu?Number(cpu[3]):null,maxRssBytes:null,source:'macOS /usr/bin/time -p; RSS unavailable because sandbox blocks time -l kern.clockrate query'};
  } else {
    const read=regex=>{const match=result.stderr.match(regex);return match?Number(match[1]):null;};
    const rss=read(/Maximum resident set size \(kbytes\):\s*([\d.]+)/);
    usage={...usage,userCpuSeconds:read(/User time \(seconds\):\s*([\d.]+)/),systemCpuSeconds:read(/System time \(seconds\):\s*([\d.]+)/),maxRssBytes:rss===null?null:rss*1024,source:'GNU time -v; RSS KiB converted to bytes'};
  }
  return {run:result,usage};
}
const engines = [
  {name:'java',bin:java,args:javaArgs},
  {name:'cpp',bin:path.join(out,'matching-cpp'),args:[]},
  ...(fs.existsSync(path.join(out,'matching-cpp-sanitized'))?[{name:'cpp-asan-ubsan',bin:path.join(out,'matching-cpp-sanitized'),args:[]}]:[])
];
// 不能只看“没有测试失败”：主动证明 ASan/UBSan 检测到错误时会以失败状态阻断。
const probe=path.join(out,'sanitizer-probe');
insist(fs.existsSync(probe),'sanitizer gate probe missing; use verification script');
for(const [mode,diagnostic] of [['undefined',/runtime error: signed integer overflow/],['address',/AddressSanitizer: heap-buffer-overflow/]]) {
  const result=run(probe,[mode],undefined,true);
  insist(result.status!==0&&diagnostic.test(result.stderr),`sanitizer ${mode} gate did not reject deliberate defect`);
}
console.log('Sanitizer gate: deliberate signed overflow and heap overrun rejected');
const gold=JSON.parse(fs.readFileSync(path.join(here,'fixtures/golden.json'),'utf8'));
let goldenCommands=0;
for(const test of gold.cases) {
  const ref=new Reference(); const expected=test.input.map(x=>ref.apply(x));
  insist(JSON.stringify(expected)===JSON.stringify(test.expected),`manual golden/reference mismatch: ${test.name}`);
  for(const engine of engines) {
    const actual=replay(engine.bin,engine.args,test.input);
    insist(actual.length===expected.length+1,`${engine.name}: response count ${test.name}`);
    for(let i=0;i<expected.length;i++) insist(actual[i]===expected[i],`${engine.name}: ${test.name}[${i}] expected ${expected[i]}, got ${actual[i]}`);
    insist(actual.at(-1)===`STATE|${ref.snapshot()}`,`${engine.name}: state ${test.name}`);
  }
  goldenCommands+=test.input.length;
}
console.log(`Golden: ${gold.cases.length} cases, ${goldenCommands} commands, ${engines.length} engines passed`);
if(process.argv.includes('--golden')) process.exit(0);

const seeds=[1,42,20260906]; const differentialCommands=3000;
for(const seed of seeds) {
  const lines=workload('mixed',differentialCommands,seed); const ref=new Reference();
  const expected=lines.map(x=>ref.apply(x)); expected.push(`STATE|${ref.snapshot()}`);
  const input=lines.join('\n')+'\n'; fs.writeFileSync(path.join(out,`differential-${seed}.txt`),input);
  for(const engine of engines) {
    const actual=replay(engine.bin,engine.args,lines);
    insist(actual.length===expected.length,`${engine.name}: differential count seed ${seed}`);
    for(let i=0;i<expected.length;i++) insist(actual[i]===expected[i],`${engine.name}: differential seed ${seed}, line ${i+1}`);
  }
}
console.log(`Differential: ${seeds.length*differentialCommands} commands, BigInt reference + internal per-command invariants passed`);
if(process.argv.includes('--tests-only')) process.exit(0);

const count=Number(process.env.MATCHING_COUNT||24000),warmup=Number(process.env.MATCHING_WARMUP||3),repeats=Number(process.env.MATCHING_REPEATS||5);
insist(Number.isInteger(count)&&count>=1000&&count<=1000000,'MATCHING_COUNT must be 1000..1000000');
insist(Number.isInteger(warmup)&&warmup>=1&&warmup<=20,'MATCHING_WARMUP must be 1..20');
insist(Number.isInteger(repeats)&&repeats>=3&&repeats<=20,'MATCHING_REPEATS must be 3..20');
const results=[];
for(const kind of ['mixed','sweeps','retries']) {
  const lines=workload(kind,count,20260906), input=lines.join('\n')+'\n';
  const inputPath=path.join(out,`workload-${kind}.txt`);fs.writeFileSync(inputPath,input);
  const row={workload:kind,seed:20260906,commands:count,inputSha256:digest(input),java:[],cpp:[]};
  // 每个独立进程先执行完整预热，再测量一次；交替先后顺序减少顺序/热状态偏差。
  for(let repeat=0;repeat<repeats;repeat++) {
    const pair=repeat%2===0?engines.slice(0,2):engines.slice(0,2).reverse();
    for(const engine of pair) {
      const r=timedProcess(engine.bin,[...engine.args,'--bench',inputPath,String(warmup)]);
      const measurement=JSON.parse(r.run.stdout);measurement.repeat=repeat+1;measurement.processUsage=r.usage;
      row[engine.name].push(measurement);
    }
  }
  const hashes=[...row.java,...row.cpp].map(x=>x.stateHash);
  insist(new Set(hashes).size===1,`${kind}: benchmark final state diverged`);
  // 完整未计时重放再次对比所有响应，而非只依赖最终状态散列。
  const a=replay(engines[0].bin,engines[0].args,lines,true), b=replay(engines[1].bin,engines[1].args,lines,true);
  insist(JSON.stringify(a)===JSON.stringify(b),`${kind}: full benchmark differential`);
  insist(fnv(a.at(-1).slice(6))===hashes[0],`${kind}: measured versus replay state`);
  row.responseSha256=digest(a.join('\n'));
  results.push(row);console.log(`Benchmark ${kind}: ${count} commands × ${repeats} independent processes per language completed`);
}
const optional=(cmd,args)=>{try{return run(cmd,args,undefined,true).stdout.trim();}catch{return 'unavailable';}};
const javaPropertyText=run(java,['-XshowSettings:properties','-version']).stderr;
const javaProperties=Object.fromEntries(['os.arch','os.name','os.version','java.vm.name','java.vm.version','java.vendor','sun.arch.data.model'].map(key=>[key,javaPropertyText.split('\n').find(line=>line.trim().startsWith(key+' = '))?.trim().split(' = ').slice(1).join(' = ')||'unavailable']));
const sourceFiles=['experiments/java-cpp-matching/java/MatchingLab.java','experiments/java-cpp-matching/cpp/matching_lab.cpp','experiments/java-cpp-matching/harness.mjs','experiments/java-cpp-matching/fixtures/golden.json','experiments/java-cpp-matching/fixtures/sanitizer-probe.cpp','scripts/verify-java-cpp-matching.sh'];
const provenance={sourceSha256:Object.fromEntries(sourceFiles.map(file=>[file,digest(fs.readFileSync(path.join(root,file)))])),binarySha256:Object.fromEntries(['matching-cpp','matching-cpp-sanitized','sanitizer-probe',...fs.readdirSync(path.join(out,'classes')).filter(file=>file.endsWith('.class')).map(file=>'classes/'+file)].map(file=>[file,digest(fs.readFileSync(path.join(out,file)))]))};
const report={
  schemaVersion:1,generatedAt:new Date().toISOString(),status:'measured-local-synthetic',provenance,
  scope:'Single-thread, one-symbol, volatile in-memory matching only. Production DB baseline NOT MEASURED and NOT COMPARABLE.',
  correctness:{goldenCases:gold.cases.length,goldenCommands,differentialSeeds:seeds,differentialCommands:seeds.length*differentialCommands,engines:engines.map(x=>x.name),benchmarkFullResponseDifferential:true,sanitizerGateProbe:true},
  configuration:{hostPlatform:os.platform(),hostArch:os.arch(),osRelease:os.release(),cpu:os.cpus()[0]?.model,cpuCount:os.cpus().length,totalMemoryBytes:os.totalmem(),javaVersion:run(java,['-version']).stderr.trim(),javaFlags:javaArgs.slice(0,3),cppVersion:optional(process.env.MATCHING_CPP_COMPILER||'clang++',['--version']),cppFlags:process.env.MATCHING_CPP_FLAGS||'unknown',javaProperties,processArchitecture:process.env.MATCHING_PROCESS_ARCH||'unknown',translation:process.env.MATCHING_TRANSLATION||'unknown',nodeVersion:process.version,warmupPasses:warmup,independentRepeats:repeats,seed:20260906,processTimeoutMs:120000},
  methodology:{clock:'System.nanoTime / std::chrono::steady_clock',latency:'One timestamp pair per apply(); request validation, STP CANCEL_TAKER, matching, order bookkeeping, dedup response caching and engine allocations included. Parsing, output formatting, file IO, engine creation, warmup, invariant walks and final snapshot excluded.',throughput:'commands / complete measured loop wall time; includes timestamp/sample-array overhead',quantiles:'nearest-rank p50/p99/p999 per independent process; raw samples saved by each engine',warmup:'Each fresh process replays the full identical workload in fresh engines, then constructs a fresh measured engine. No claim that JIT or CPU reached steady state.',allocation:'Both engines allocate orders, price levels, dedup entries and trade results. Java reports measured-thread allocated bytes where supported; C++ allocation bytes/counts not instrumented. Cross-language allocation comparison unavailable.',limitations:['Local synthetic microbenchmark; no gateway, serialization, network, journal, database, Kafka, durability, recovery, concurrency or end-to-end order latency.','No CPU pinning, turbo/thermal isolation or OS-noise control. Hardware and translation recorded.','Duplicate request and completed order history remain in memory; bounded workload, not a production retention design.','Latency includes clock overhead. Timed samples can contain GC/JIT/OS scheduling pauses. No universal language winner inferred.','STP CANCEL_TAKER preserves earlier valid fills; cached protocol response replay differs from production current-order snapshot replay.']},results
};
fs.writeFileSync(path.join(out,'report.json'),JSON.stringify(report,null,2)+'\n');
const median=a=>[...a].sort((x,y)=>x-y)[Math.floor(a.length/2)];
const summary={generatedAt:report.generatedAt,scope:report.scope,provenance,correctness:report.correctness,configuration:report.configuration,methodology:report.methodology,workloads:results.map(r=>({name:r.workload,commands:r.commands,repeats,inputSha256:r.inputSha256,java:stats(r.java),cpp:stats(r.cpp)}))};
function stats(rows){return {medianThroughputOpsSec:median(rows.map(r=>r.throughputOpsSec)),medianP50Ns:median(rows.map(r=>r.p50Ns)),medianP99Ns:median(rows.map(r=>r.p99Ns)),medianP999Ns:median(rows.map(r=>r.p999Ns)),worstMaxNs:Math.max(...rows.map(r=>r.maxNs)),perRepeat:rows};}
fs.writeFileSync(path.join(out,'summary.json'),JSON.stringify(summary,null,2)+'\n');
console.log(`PASS: ${path.join(out,'report.json')}`);
