// A real Linux process: no platform spoofing and no disabled file lock.
import fs from 'node:fs';import {createRequire} from 'node:module';
const need=n=>{const v=process.env[n];if(!v)throw Error('Missing launch identity: '+n);return v;};
const parent=Number(need('DSHM_NATIVE_PARENT')),parentStart=need('DSHM_PARENT_START'),tag=need('DSHM_LAUNCH_TAG'),slotId=need('DSHM_SLOT_ID'),cli=need('DSHM_GUEST_CLI'),file=need('DSHM_LINUX_PID_FILE');
if(process.platform!=='linux'||process.version!=='v'+need('DSHM_EXPECTED_NODE'))throw Error('Linux Node differs from manifest');
const actual=createRequire(import.meta.url)('@deepseek-ai/dsh/package.json').version;if(actual!==need('DSHM_EXPECTED_DSH'))throw Error('DSH differs from manifest');
function ticks(pid){const raw=fs.readFileSync(`/proc/${pid}/stat`,'utf8');return raw.slice(raw.lastIndexOf(')')+2).trim().split(/\s+/)[19];}
fs.writeFileSync(file,JSON.stringify({pid:process.pid,startTicks:ticks(process.pid),nativeParent:parent,tag,slotId,cli}),{flag:'wx',mode:0o600});console.log('[DSHM_MANAGED_ACTUAL] '+JSON.stringify({slotId,platform:process.platform,node:process.version,dsh:actual}));
let ending=false;const watch=setInterval(()=>{let alive=false;try{alive=ticks(parent)===parentStart;}catch{}if(alive||ending)return;ending=true;clearInterval(watch);process.kill(process.pid,'SIGTERM');const deadline=setTimeout(()=>process.kill(process.pid,'SIGKILL'),8000);deadline.unref();},1500);watch.unref();
process.once('exit',()=>{clearInterval(watch);try{const r=JSON.parse(fs.readFileSync(file,'utf8'));if(r.tag===tag&&r.pid===process.pid)fs.unlinkSync(file);}catch{}});
