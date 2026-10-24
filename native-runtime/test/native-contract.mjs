import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';
import {spawn, execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
const require = createRequire(import.meta.url);
const {loadBinding} = require('../lib/android-binding.cjs');
const binding = loadBinding();
const runFile = promisify(execFile);
const self = fileURLToPath(import.meta.url);
const lock = fd => new Promise((resolve,reject)=>{try{binding.tryLock(fd,resolve);}catch(e){reject(e);}});
if (process.argv[2] === '--child') {
  const handle = await fs.open(process.argv[4], 'a+');
  const errno = await lock(handle.fd);
  if (process.argv[3] === 'hold' && errno === 0) {
    globalThis.ownedHandle = handle;
    process.stdout.write(JSON.stringify({errno,pid:process.pid})+'\n');
    const parentPid = process.ppid;
    setInterval(()=>{if(process.ppid!==parentPid)process.exit(0);},500);
    setTimeout(()=>process.exit(0),45000).unref();
  } else {
    await handle.close();
    console.log(JSON.stringify({errno}));
  }
} else {
  const expectedPlatform = process.argv[2];
  const report = {platform:process.platform,arch:process.arch,node:process.version,backend:'real-node-api-posix-flock',checks:[],passed:false};
  const check = async(name,fn)=>{await fn();report.checks.push({name,passed:true});};
  const root = await fs.mkdtemp(path.join(os.tmpdir(),'dshm-native-flock-'));
  const file = path.join(root,'原生互斥检查.lock');
  let handle;
  let holder;
  const childTry = async()=>JSON.parse((await runFile(process.execPath,[self,'--child','try',file],{timeout:20000,encoding:'utf8'})).stdout);
  try {
    await check('actual host platform matches expectation',async()=>assert.equal(process.platform,expectedPlatform));
    await check('invalid descriptor reports real EBADF',async()=>assert.equal(await lock(-1),os.constants.errno.EBADF));
    handle = await fs.open(file,'a+');
    await check('exclusive nonblocking lock acquired',async()=>assert.equal(await lock(handle.fd),0));
    await check('repeat lock on same descriptor succeeds',async()=>assert.equal(await lock(handle.fd),0));
    await check('separate OS process is denied while held',async()=>assert.ok([os.constants.errno.EAGAIN,os.constants.errno.EWOULDBLOCK].includes((await childTry()).errno)));
    await check('descriptor ownership remains with caller',async()=>{await handle.writeFile('owned by caller');assert.equal((await handle.stat()).isFile(),true);});
    await handle.close();handle=null;
    await check('caller close releases lock for a new process',async()=>assert.equal((await childTry()).errno,0));
    holder=spawn(process.execPath,[self,'--child','hold',file],{stdio:['ignore','pipe','pipe']});
    const holderExit=new Promise((resolve,reject)=>{holder.once('error',reject);holder.once('exit',(code,signal)=>resolve({code,signal}));});
    await new Promise((resolve,reject)=>{
      let text='';const timer=setTimeout(()=>reject(new Error('owned lock child did not become ready')),20000);
      holder.stdout.on('data',b=>{text+=b;if(text.includes('\n')){clearTimeout(timer);try{assert.equal(JSON.parse(text.split('\n')[0]).errno,0);resolve();}catch(e){reject(e);}}});
      holder.once('error',e=>{clearTimeout(timer);reject(e);});
      holder.once('exit',()=>{clearTimeout(timer);reject(new Error('owned lock child exited before ready'));});
    });
    await check('second contender is denied by child-held lock',async()=>assert.ok([os.constants.errno.EAGAIN,os.constants.errno.EWOULDBLOCK].includes((await childTry()).errno)));
    // Only this test's own child handle is signalled. Never scan or kill a server.
    holder.kill('SIGKILL');
    await check('owned test child terminates by SIGKILL',async()=>assert.equal((await holderExit).signal,'SIGKILL'));
    holder=null;
    await check('kernel releases lock after owner process death',async()=>assert.equal((await childTry()).errno,0));
    report.passed=true;
  } catch(error) {
    report.error={name:error.name,message:error.message};
    process.exitCode=1;
  } finally {
    if(handle)await handle.close();
    if(holder && holder.exitCode===null && holder.signalCode===null){holder.kill('SIGKILL');await new Promise(resolve=>holder.once('exit',resolve));}
    await fs.rm(root,{recursive:true,force:true});
    console.log(JSON.stringify(report,null,2));
  }
}
