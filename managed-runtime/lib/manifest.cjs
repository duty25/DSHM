"use strict";
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const ID=/^[a-z0-9][a-z0-9._-]{0,95}$/,HASH=/^[a-f0-9]{64}$/;
function requireThat(ok,message){if(!ok)throw Error(message);}
function relativeName(s){requireThat(typeof s==='string'&&s.length>0&&!s.includes('\0')&&!s.includes('\\')&&!path.posix.isAbsolute(s)&&!s.split('/').some(p=>!p||p==='.'||p==='..'),'Invalid relative runtime path');return s;}
function inside(root,name){relativeName(name);const base=path.resolve(root),full=path.resolve(base,name);requireThat(full.startsWith(base+path.sep),'Path escapes slot');return full;}
function sha256(data){return crypto.createHash('sha256').update(data).digest('hex');}
function normalizeManifest(m){
 requireThat(m&&m.schemaVersion===1,'Unsupported manifest schema');
 requireThat(typeof m.id==='string'&&ID.test(m.id)&&!['.','..'].includes(m.id),'Invalid runtime id');
 requireThat(m.backend==='linux-proot'&&m.arch==='arm64','Actual Linux arm64 required');
 requireThat(m.dshVersion==='0.1.7-rc.1'&&m.nodeVersion==='24.22.0','Version differs from pinned baseline');
 requireThat(m.dataSchema==='dsh-session-v4','Explicit data migration required');
 const node=relativeName(m.node),cli=relativeName(m.cli),names=new Set();
 requireThat(Array.isArray(m.files)&&m.files.length>0,'Missing file table');
 const files=m.files.map(f=>{const p=relativeName(f.path);requireThat(!names.has(p),'Duplicate archive member');names.add(p);requireThat(HASH.test(f.sha256)&&Number.isSafeInteger(f.size)&&f.size>=0,'Invalid checksum or size');return {path:p,size:f.size,sha256:f.sha256,executable:f.executable===true};});
 for(const p of [node,cli,'node_modules/@deepseek-ai/dsh/package.json','bootstrap/guest-guard.mjs'])requireThat(files.some(f=>f.path===p),'Critical file must be regular and declared');
 requireThat(!m.symlinks||Array.isArray(m.symlinks),'Invalid symlinks');
 const symlinks=(m.symlinks||[]).map(l=>{const p=relativeName(l.path);requireThat(!names.has(p),'Duplicate archive member');names.add(p);requireThat(typeof l.target==='string'&&l.target&&!l.target.includes('\0')&&!l.target.includes('\\')&&!path.posix.isAbsolute(l.target),'Invalid link target');relativeName(path.posix.normalize(path.posix.join(path.posix.dirname(p),l.target)));return {path:p,target:l.target};});
 for(const l of symlinks)requireThat(![...names].some(p=>p.startsWith(l.path+'/')),'Link is ancestor of an archive member');
 const plugins=(m.plugins||[]).map(p=>{requireThat(typeof p.name==='string'&&typeof p.version==='string'&&HASH.test(p.contentSha256),'Invalid plugin pin');return {name:p.name,version:p.version,contentSha256:p.contentSha256};});
 requireThat(new Set(plugins.map(p=>p.name)).size===plugins.length,'Duplicate plugin providers');
 return {schemaVersion:1,id:m.id,backend:m.backend,arch:m.arch,dshVersion:m.dshVersion,nodeVersion:m.nodeVersion,dataSchema:m.dataSchema,node,cli,files,symlinks,plugins};
}
function verifySlot(root,{full=true}={}){
 requireThat(!fs.lstatSync(root).isSymbolicLink(),'Slot root may not be a symlink');
 const canonical=fs.realpathSync(root),p=inside(root,'manifest.json');requireThat(fs.lstatSync(p).isFile(),'Regular manifest required');
 const bytes=fs.readFileSync(p),manifest=normalizeManifest(JSON.parse(bytes)),critical=new Set([manifest.node,manifest.cli,'node_modules/@deepseek-ai/dsh/package.json','bootstrap/guest-guard.mjs']);
 for(const f of manifest.files.filter(f=>full||critical.has(f.path))){const dest=inside(root,f.path),s=fs.lstatSync(dest);requireThat(s.isFile()&&s.size===f.size&&fs.realpathSync(dest).startsWith(canonical+path.sep),'Runtime member differs: '+f.path);requireThat(sha256(fs.readFileSync(dest))===f.sha256,'Checksum mismatch: '+f.path);}
 for(const l of manifest.symlinks){const dest=inside(root,l.path);requireThat(fs.lstatSync(dest).isSymbolicLink()&&fs.readlinkSync(dest)===l.target&&fs.realpathSync(dest).startsWith(canonical+path.sep),'Invalid installed symlink');}
 requireThat(JSON.parse(fs.readFileSync(inside(root,'node_modules/@deepseek-ai/dsh/package.json'),'utf8')).version===manifest.dshVersion,'Installed DSH differs from manifest');
 return {manifest,manifestSha256:sha256(bytes),root:canonical};
}
module.exports={requireThat,relativeName,inside,sha256,normalizeManifest,verifySlot};
