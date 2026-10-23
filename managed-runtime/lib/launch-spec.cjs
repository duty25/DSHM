"use strict";
const fs=require('node:fs'),path=require('node:path');const {normalizeManifest,requireThat}=require('./manifest.cjs');
function startTicks(raw){const i=raw.lastIndexOf(')');requireThat(i>=0,'Invalid process stat');const tick=raw.slice(i+1).trim().split(/\s+/)[19];requireThat(/^\d+$/.test(tick||''),'Missing start time');return tick;}
function ownedGuest(r,e,read=p=>fs.readFileSync(`/proc/${p}/stat`,'utf8')){try{return !!r&&Number.isSafeInteger(r.pid)&&r.pid>1&&r.pid!==e.nativePid&&r.nativeParent===e.nativePid&&r.tag===e.tag&&r.slotId===e.slotId&&r.cli===e.cli&&r.startTicks===startTicks(read(r.pid));}catch{return false;}}
function buildLaunchSpec(c,hostEnv,i){
 const m=normalizeManifest(c.manifest);requireThat(c.schemaVersion===1&&Number.isInteger(c.port)&&c.port>=1024&&c.port<=65535,'Invalid config or port');
 for(const k of ['rootfs','slotRoot','dataRoot','userHome','tmpRoot','workspace','pidRoot','proot','nativeLibDir','supportLibDir'])requireThat(typeof c[k]==='string'&&path.isAbsolute(c[k])&&!c[k].includes('\0'),'Invalid host path: '+k);
 requireThat(i&&Number.isInteger(i.nativePid)&&/^\d+$/.test(i.nativeStart||'')&&/^[a-f0-9]{32}$/.test(i.tag),'Invalid identity');
 const slot='/opt/dshm/current',node=slot+'/'+m.node,cli=slot+'/'+m.cli,guest={...hostEnv};
 for(const k of Object.keys(guest))if(/^(LD_|DSH_SUBSYSTEM_|DSHM_)/.test(k)||['PREFIX','PNPM_HOME','PNPM_NODE','PNPM_CJS','NODE_OPTIONS','NODE_PATH','NODE_EXTRA_CA_CERTS','DSH_ROOT_ARGV','DSH_BASH_PATH','DSH_SH_PATH','DSH_RG_PATH','NPM_CONFIG_GLOBALCONFIG','npm_config_globalconfig','npm_config_cache','npm_config_userconfig'].includes(k))delete guest[k];
 Object.assign(guest,{PATH:`${slot}/bin:${slot}/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`,HOME:'/root/dshm-user',DSH_HOME:'/root/dsh',TMPDIR:'/tmp/dshm',SHELL:'/bin/bash',PWD:'/workspace',LANG:'C.UTF-8',XDG_CONFIG_HOME:'/root/dshm-user/.config',XDG_CACHE_HOME:'/root/dshm-user/.cache',XDG_DATA_HOME:'/root/dshm-user/.local/share',NPM_CONFIG_CACHE:'/root/dshm-user/.npm',NPM_CONFIG_USERCONFIG:'/root/dshm-user/.npmrc',DSHM_MANAGED_RUNTIME:'1',DSHM_NATIVE_PARENT:String(i.nativePid),DSHM_PARENT_START:i.nativeStart,DSHM_LAUNCH_TAG:i.tag,DSHM_SLOT_ID:m.id,DSHM_GUEST_CLI:cli,DSHM_EXPECTED_DSH:m.dshVersion,DSHM_EXPECTED_NODE:m.nodeVersion,DSHM_LINUX_PID_FILE:'/run/dshm/'+i.tag+'.json'});
 const argv=['--link2symlink','-L','--kill-on-exit','-0','-r',c.rootfs,'--cwd=/workspace'];for(const p of ['/dev','/proc','/sys'])argv.push('-b',p);
 argv.push('-b','/dev/urandom:/dev/random','-b','/proc/self/fd:/dev/fd','-b',c.slotRoot+':'+slot,'-b',c.dataRoot+':/root/dsh','-b',c.userHome+':/root/dshm-user','-b',c.tmpRoot+':/tmp/dshm','-b',c.workspace+':/workspace','-b',c.pidRoot+':/run/dshm');
 if(c.resolvConf){requireThat(path.isAbsolute(c.resolvConf),'Invalid resolver path');argv.push('-b',c.resolvConf+':/etc/resolv.conf');}if(c.sharedStorage)argv.push('-b','/storage/emulated/0:/storage/emulated/0');
 argv.push('/usr/bin/env','-i',...Object.entries(guest).map(([k,v])=>`${k}=${v}`),node,'--import',slot+'/bootstrap/guest-guard.mjs',cli,'web','--host','127.0.0.1','--port',String(c.port),'--no-open');
 return {program:c.proot,argv,env:{...hostEnv,PROOT_TMP_DIR:c.tmpRoot,PROOT_LOADER:c.nativeLibDir+'/libprootloader.so',PROOT_LOADER_32:c.nativeLibDir+'/libprootloader32.so',LD_LIBRARY_PATH:c.nativeLibDir+':'+c.supportLibDir},cwd:c.workspace,expected:{nativePid:i.nativePid,tag:i.tag,slotId:m.id,cli}};
}
module.exports={startTicks,ownedGuest,buildLaunchSpec};
