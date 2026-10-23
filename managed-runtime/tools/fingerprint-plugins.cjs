'use strict';
// Read code metadata only; never export credentials, sessions or private plugin packages.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const input=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
const hash=b=>crypto.createHash('sha256').update(b).digest('hex');
const excluded=name=>name.startsWith('.')||name==='node_modules'||name==='__pycache__'||/\.(?:jks|keystore|lock)$/.test(name)||/^(?:accounts?|credentials?|secrets?|settings)\.(?:json|ya?ml)$/i.test(name)||/^session\.v\d/.test(name);
const output={schemaVersion:1,dshVersion:'0.1.7-rc.1',capturedAt:new Date().toISOString(),privatePluginCodeIncluded:false,credentialsIncluded:false,plugins:[],excluded:['jumeng-media','dsh-debian-bridge-fix','@dsh-external/dsh-attachment-android-fix']};
for(const item of input.packages){
 const root=fs.realpathSync(item.dir),mf=path.join(root,'package.json'),raw=fs.readFileSync(mf),meta=JSON.parse(raw);const roots=new Set(['package.json']);
 for(const value of meta.files||['lib','dist','src','client']){const p=String(value).replace(/^\.\//,'').split(/[?*]/)[0].replace(/\/$/,'');if(p)roots.add(p);}
 if(typeof meta.main==='string')roots.add(meta.main.replace(/^\.\//,''));
 for(const e of fs.readdirSync(root,{withFileTypes:true}))if(e.isFile()&&!excluded(e.name)&&(/\.(?:mjs|cjs|js|json|ya?ml)$/.test(e.name)||/^(?:LICENSE|LICENCE|COPYING|NOTICE|README)/i.test(e.name)))roots.add(e.name);
 const entries=new Map();
 function add(relative){
  const p=path.join(root,relative);if(relative.split(path.sep).some(excluded))return;
  let before;try{before=fs.lstatSync(p);}catch(e){if(e.code==='ENOENT')return;throw e;}
  if(before.isSymbolicLink())throw Error('Selected plugin code contains a link requiring review: '+item.name+'/'+relative);
  if(before.isDirectory()){for(const e of fs.readdirSync(p))add(path.join(relative,e));return;}
  if(!before.isFile())return;const content=fs.readFileSync(p),after=fs.statSync(p);
  if(before.size!==after.size||before.mtimeMs!==after.mtimeMs)throw Error('Plugin source changed during fingerprint: '+item.name);
  entries.set(relative.split(path.sep).join('/'),{path:relative.split(path.sep).join('/'),bytes:content.length,sha256:hash(content)});
 }
 for(const r of roots)add(r);
 const files=[...entries.values()].sort((a,b)=>a.path.localeCompare(b.path));
 output.plugins.push({id:item.id,name:meta.name,version:meta.version,manifestSha256:hash(raw),changedManifestSinceInitialCapture:hash(raw)!==item.manifestSha256,contentSha256:hash(files.map(f=>f.path+'\0'+f.sha256+'\n').join('')),files});
}
if(new Set(output.plugins.map(p=>p.name)).size!==output.plugins.length)throw Error('Duplicate active plugin package');
fs.writeFileSync(process.argv[3],JSON.stringify(output,null,2)+'\n');
console.log(JSON.stringify({plugins:output.plugins.length,codeFiles:output.plugins.reduce((n,p)=>n+p.files.length,0),changedSinceInitialCapture:output.plugins.filter(p=>p.changedManifestSinceInitialCapture).map(p=>p.name),privateCodeUploaded:false}));
