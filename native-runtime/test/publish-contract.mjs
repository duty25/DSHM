import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {fileURLToPath} from 'node:url';
const require=createRequire(import.meta.url);const binding=require('../lib/android-binding.cjs').loadBinding();
assert.equal(typeof binding.renameNoReplace,'function');
const publish=(a,b)=>new Promise((resolve,reject)=>{try{binding.renameNoReplace(a,b,resolve);}catch(e){reject(e);}});
const self=fileURLToPath(import.meta.url);const run=promisify(execFile);
if(process.argv[2]==='--publish')console.log(JSON.stringify({errno:await publish(process.argv[3],process.argv[4])}));
else{
 const report={platform:process.platform,node:process.version,primitive:'renameat2(RENAME_NOREPLACE)',checks:[],passed:false};
 const root=await fs.mkdtemp(path.join(os.tmpdir(),'dshm-publish-'));let held;
 const check=async(name,fn)=>{await fn();report.checks.push({name,passed:true});};
 try{
  await check('actual platform matches',async()=>assert.equal(process.platform,process.argv[2]));
  const a=path.join(root,'初始文件'),b=path.join(root,'会话文件');await fs.writeFile(a,'durable-source');held=await fs.open(a,'r+');await held.sync();const original=await held.stat();
  await check('atomic exclusive publication succeeds',async()=>assert.equal(await publish(a,b),0));
  await check('publication preserves inode and open descriptor',async()=>{assert.equal((await fs.stat(b)).ino,original.ino);assert.equal(await held.readFile('utf8'),'durable-source');await assert.rejects(fs.stat(a),{code:'ENOENT'});});await held.close();held=null;
  const duplicate=path.join(root,'duplicate');await fs.writeFile(duplicate,'must-not-overwrite');
  await check('existing target is protected with EEXIST',async()=>{assert.equal(await publish(duplicate,b),os.constants.errno.EEXIST);assert.equal(await fs.readFile(b,'utf8'),'durable-source');assert.equal(await fs.readFile(duplicate,'utf8'),'must-not-overwrite');});
  const left=path.join(root,'left'),right=path.join(root,'right'),target=path.join(root,'winner');await fs.writeFile(left,'left');await fs.writeFile(right,'right');
  const child=async src=>JSON.parse((await run(process.execPath,[self,'--publish',src,target],{encoding:'utf8',timeout:20000})).stdout);
  const results=await Promise.all([child(left),child(right)]);
  await check('two OS processes have exactly one publication winner',async()=>{assert.equal(results.filter(x=>x.errno===0).length,1);assert.equal(results.filter(x=>x.errno===os.constants.errno.EEXIST).length,1);const winner=results[0].errno===0?'left':'right';assert.equal(await fs.readFile(target,'utf8'),winner);});
  await check('missing source fails without creating target',async()=>{const missing=path.join(root,'missing'),dest=path.join(root,'not-created');assert.equal(await publish(missing,dest),os.constants.errno.ENOENT);await assert.rejects(fs.stat(dest),{code:'ENOENT'});});
  report.passed=true;
 }catch(e){report.error={message:e.message,code:e.code};process.exitCode=1;}
 finally{if(held)await held.close();await fs.rm(root,{recursive:true,force:true});console.log(JSON.stringify(report,null,2));}
}
