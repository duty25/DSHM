"use strict";
// Independent native bootstrap: built-ins only, never load an old DSH SDK.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');const {spawn}=require('node:child_process');
const {verifySlot,requireThat}=require('./manifest.cjs');const {buildLaunchSpec,startTicks,ownedGuest}=require('./launch-spec.cjs');
async function launch(file){
 requireThat(process.platform==='android','Bootstrap must run in the Android app');const c=JSON.parse(fs.readFileSync(file,'utf8')),checked=verifySlot(c.slotRoot,{full:false});requireThat(c.manifestSha256===checked.manifestSha256,'Selected manifest changed');
 const i={nativePid:process.pid,nativeStart:startTicks(fs.readFileSync('/proc/self/stat','utf8')),tag:crypto.randomBytes(16).toString('hex')};c.manifest=checked.manifest;
 for(const k of ['dataRoot','userHome','tmpRoot','pidRoot'])fs.mkdirSync(c[k],{recursive:true,mode:0o700});const spec=buildLaunchSpec(c,process.env,i);requireThat(fs.existsSync(spec.program)&&fs.existsSync(c.rootfs),'proot or rootfs missing');
 const pidFile=path.join(c.pidRoot,i.tag+'.json'),recordFile=path.join(path.dirname(file),'native-process.json'),temp=recordFile+'.tmp-'+i.tag;fs.writeFileSync(temp,JSON.stringify({pid:process.pid,startTicks:i.nativeStart,tag:i.tag,slotId:checked.manifest.id,launcher:__filename,pidFile}),{flag:'wx',mode:0o600});fs.renameSync(temp,recordFile);
 console.log('[DSHM_MANAGED_BOOT] '+JSON.stringify({slotId:checked.manifest.id,dsh:checked.manifest.dshVersion,node:checked.manifest.nodeVersion}));const child=spawn(spec.program,spec.argv,{env:spec.env,cwd:spec.cwd,stdio:'inherit'});
 function relay(signal){try{const r=JSON.parse(fs.readFileSync(pidFile,'utf8'));if(ownedGuest(r,spec.expected)){process.kill(r.pid,signal);return true;}}catch{}console.error('[DSHM_MANAGED_STOP] No matching guest identity; refusing unknown PID');return false;}
 const term=()=>relay('SIGTERM'),intr=()=>relay('SIGINT');process.on('SIGTERM',term);process.on('SIGINT',intr);
 return await new Promise((resolve,reject)=>{function clean(){process.removeListener('SIGTERM',term);process.removeListener('SIGINT',intr);for(const p of [pidFile,recordFile])try{if(JSON.parse(fs.readFileSync(p,'utf8')).tag===i.tag)fs.unlinkSync(p);}catch{}}child.once('error',e=>{clean();reject(e);});child.once('close',(code,signal)=>{clean();resolve({exitCode:code??(signal==='SIGTERM'?143:1),signal});});});
}
module.exports={launch};if(require.main===module)launch(process.argv[2]).then(r=>{process.exitCode=r.exitCode;}).catch(e=>{console.error('[DSHM_MANAGED_ERROR] '+e.message);process.exitCode=1;});
