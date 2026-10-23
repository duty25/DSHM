'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const root=path.resolve(process.argv[2]);
const file=path.join(root,'node_modules/@deepseek-ai/dsh-fs-local/lib/index.js');
const old=fs.readFileSync(file,'utf8');
const expected='63fbb41d2c33e07111884b798be507e68c2752acab8249c821c20ade436e894f';
if(crypto.createHash('sha256').update(old).digest('hex')!==expected)throw Error('upstream fs-local differs from reviewed 0.1.7 baseline');
const source=`import {link} from 'node:fs/promises';
import {getSystemErrorName} from 'node:util';
let binding;
export async function publishNoReplace(source,destination){
 if(process.platform!=='linux'||process.env.DSHM_MANAGED_RUNTIME!=='1'){await link(source,destination);return;}
 if(!binding){const koffi=(await import('koffi')).default;const libc=koffi.load('libc.so.6');binding={koffi,libc,rename:libc.func('int renameat2(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, unsigned int flags)')};}
 if(binding.rename(-100,source,-100,destination,1)===0)return;
 const errno=binding.koffi.errno(),code=getSystemErrorName(-errno);const e=new Error(code+': no-replace publication failed');Object.assign(e,{code,errno:-errno,syscall:'renameat2',path:source,dest:destination});throw e;
}
`;
if(old.split('const linkFile = internals.linkFile ?? link;').length!==2)throw Error('publication hook is not unique');
const patched='import {publishNoReplace} from "./managed-publish.js";\n'+old.replace('const linkFile = internals.linkFile ?? link;','const linkFile = internals.linkFile ?? publishNoReplace;');
fs.writeFileSync(file,patched);fs.writeFileSync(path.join(path.dirname(file),'managed-publish.js'),source,{flag:'wx'});
console.log('Applied reviewed no-replace publication compatibility; native flock and native addons unchanged.');
