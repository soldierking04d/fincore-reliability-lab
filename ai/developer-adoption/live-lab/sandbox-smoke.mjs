import { mkdtemp, mkdir, writeFile, realpath } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { homedir } from 'node:os';
import assert from 'node:assert/strict';
import { sandboxProfile } from './verifier.mjs';
import { processRun } from './process.mjs';

// 只读取自己创建的无敏感哨兵，检查拒绝边界；不尝试读取用户密钥或个人文件。
const base=fileURLToPath(new URL('./.local/',import.meta.url));await mkdir(base,{recursive:true,mode:0o700});
const folder=await mkdtemp(join(base,'sandbox-check-'));const root=await realpath(folder);
const execute=join(root,'execution');await mkdir(execute);const tmp=join(execute,'tmp');await mkdir(tmp);
const jdk=process.env.AI_LAB_JDK||join(homedir(),'Library/Java/JavaVirtualMachines/openjdk-26/Contents/Home');
const sentinel=join(root,'outside.txt');await writeFile(sentinel,'non-sensitive boundary fixture');
const java=`import java.nio.file.*;import java.net.*;
public class BoundaryProbe {
 public static void main(String[] args)throws Exception{
  boolean read=false,network=false,write=false;
  try{Files.readString(Path.of(args[0]));}catch(java.io.IOException e){read=e.getMessage().contains("Operation not permitted")||e.getMessage().contains("Permission denied");}
  try(Socket s=new Socket()){s.connect(new InetSocketAddress("127.0.0.1",4400),1000);}catch(java.io.IOException e){network=e.getMessage().contains("Operation not permitted")||e.getMessage().contains("Permission denied");}
  try{Files.writeString(Path.of("forbidden.txt"),"x");}catch(java.io.IOException e){write=e.getMessage().contains("Operation not permitted")||e.getMessage().contains("Permission denied");}
  Path own=Path.of(args[1],"allowed.txt");Files.writeString(own,"ok");
  System.out.println("{\\"outsideReadBlocked\\":"+read+",\\"networkBlocked\\":"+network+",\\"testDirectoryWriteBlocked\\":"+write+",\\"temporaryWriteAllowed\\":"+Files.readString(own).equals("ok")+"}");
 }
}`;
await writeFile(join(execute,'BoundaryProbe.java'),java);
const compiled=await processRun(join(jdk,'bin','javac'),['--release','21',join(execute,'BoundaryProbe.java')],{cwd:execute});
assert.equal(compiled.code,0,compiled.stderr);
const profile=join(execute,'runtime.sb');await writeFile(profile,await sandboxProfile(execute,jdk,tmp));
const result=await processRun('/usr/bin/sandbox-exec',['-f',profile,join(jdk,'bin','java'),'-Xmx64m','-XX:-UsePerfData','-cp',execute,'BoundaryProbe',sentinel,tmp],{cwd:execute,timeout:8000,env:{PATH:'/usr/bin:/bin',HOME:tmp,TMPDIR:tmp}});
assert.equal(result.code,0,result.stderr);const evidence=JSON.parse(result.stdout);
assert.deepEqual(evidence,{outsideReadBlocked:true,networkBlocked:true,testDirectoryWriteBlocked:true,temporaryWriteAllowed:true});
console.log(JSON.stringify({status:'passed',...evidence}));
