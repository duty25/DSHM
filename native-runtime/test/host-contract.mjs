import * as fs from 'node:fs/promises';
import {createRequire} from 'node:module';
import os from 'node:os';import path from 'node:path';import net from 'node:net';
import assert from 'node:assert/strict';import {spawn} from 'node:child_process';import {fileURLToPath} from 'node:url';
const {makeHostPatch}=createRequire(import.meta.url)('./host-fixture.cjs');
const report={checks:[],platform:process.platform,node:process.version,realModelCalls:0,realSessionsUsed:false,passed:false};
assert.equal(process.platform,process.env.DSHM_EXPECT_PLATFORM||'android');
const sdk=process.env.DSHM_NATIVE_CORE_ROOT;assert.ok(sdk&&path.isAbsolute(sdk));const marker=JSON.parse(await fs.readFile(path.join(sdk,'.dshm-native017-candidate.json')));assert.equal(marker.production,false);
const own=path.dirname(fileURLToPath(import.meta.url));const root=await fs.mkdtemp(path.join(os.tmpdir(),'dshm-native-host-'));
const home=path.join(root,'home'),dsh=path.join(root,'dsh-home'),profile=path.join(dsh,'profiles/web');await fs.mkdir(profile,{recursive:true});await fs.mkdir(home);await fs.mkdir(path.join(root,'tmp'));
await fs.writeFile(path.join(profile,'package.json'),JSON.stringify({private:true,type:'module',bundles:['@deepseek-ai/dsh-base','@deepseek-ai/dsh-web-app'],dependencies:{}}));
await fs.writeFile(path.join(profile,'cordis.patch.yml'),JSON.stringify(makeHostPatch(path.join(own,'startup-observer/index.mjs')),null,2)+'\n');
const port=await new Promise((resolve,reject)=>{const s=net.createServer();s.once('error',reject);s.listen(0,'127.0.0.1',()=>{const port=s.address().port;s.close(()=>resolve(port));});});
const env={...process.env,HOME:home,DSH_HOME:dsh,TMPDIR:path.join(root,'tmp'),DSH_PERMISSION_MODE:'danger-full-access'};
for(const key of Object.keys(env))if(/(?:API_KEY|ACCESS_TOKEN|AUTH_TOKEN|SECRET)$/.test(key))delete env[key];
const child=spawn(process.execPath,['--expose-internals',path.join(sdk,'node_modules/@deepseek-ai/dsh/lib/bin.js'),'web','--no-open','--host','127.0.0.1','--port',String(port)],{cwd:root,env,stdio:['ignore','pipe','pipe']});
let log='';let exited=false;let exitResult;
child.stdout.on('data',b=>{log+=b.toString();if(log.length>262144)log=log.slice(-262144);});child.stderr.on('data',b=>{log+=b.toString();if(log.length>262144)log=log.slice(-262144);});
const stopped=new Promise((resolve,reject)=>{child.once('error',reject);child.once('exit',(code,signal)=>{exited=true;exitResult={code,signal};resolve(exitResult);});});
const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
try{
 const deadline=Date.now()+90000;let cookie;let evidence;
 while(Date.now()<deadline){
  if(exited)throw new Error('Native host exited during startup: '+JSON.stringify(exitResult));
  const match=log.match(/http:\/\/127\.0\.0\.1:\d+\/\?token=([A-Za-z0-9_-]+)/);
  if(match){
   const base='http://127.0.0.1:'+port;
   if(!cookie){const r=await fetch(base+'/?token='+match[1],{redirect:'manual'});const raw=r.headers.get('set-cookie');if(raw){assert.ok(!/(?:^|;)\s*Secure(?:;|$)/i.test(raw),'Loopback HTTP auth cookie must not require HTTPS');cookie=raw.split(';')[0];}}
   if(cookie){const r=await fetch(base+'/api/native017-startup',{headers:{cookie}});if(r.ok){const d=await r.json();const bad=d.entries.filter(e=>[0,1,3].includes(e.state));if(bad.length===0){evidence=d;break;}}}
  }
  await wait(400);
 }
 assert.ok(evidence,'Native authenticated startup observer never became ready');assert.equal(evidence.platform,'android');assert.ok(evidence.clientCount>0);assert.deepEqual(evidence.services,{sessionPersistence:true,shell:true,fs:true});
 assert.equal(evidence.pid,child.pid,'Observer must belong to the owned test child');
 report.checks.push({name:'actual-android-owned-host',passed:true},{name:'client-graph-nonempty',passed:true},{name:'required-services-active',passed:true},{name:'no-loader-failure-or-pending',passed:true});
 report.activeEntries=evidence.entries.filter(e=>e.state===2).length;report.clientCount=evidence.clientCount;report.services=evidence.services;report.loaderFailedOrPending=[];
 child.kill('SIGTERM');const end=await Promise.race([stopped,wait(10000).then(()=>null)]);assert.ok(end,'Owned native host did not stop normally');assert.equal(end.code,0);report.cleanShutdown=true;report.checks.push({name:'clean-owned-host-shutdown',passed:true});report.passed=true;
}catch(error){report.error={message:error.message,code:error.code};process.exitCode=1;}
finally{
 if(!exited){child.kill('SIGTERM');await Promise.race([stopped,wait(2000)]);if(!exited){child.kill('SIGKILL');await stopped;report.forcedOwnedTestStop=true;}}
 const sanitized=log.replace(/([?&]token=)[A-Za-z0-9_-]+/g,'$1<redacted>');
 await fs.writeFile(path.join(process.env.DSHM_NATIVE_REPORT_DIR||os.tmpdir(),'native-host-contract.json'),JSON.stringify(report,null,2)+'\n');
 if(!report.passed)console.error(sanitized.slice(-8000));console.log(JSON.stringify(report));
 await fs.rm(root,{recursive:true,force:true});
}
