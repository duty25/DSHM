const fs=require('node:fs');const path=require('node:path');const crypto=require('node:crypto');
const own=path.resolve(__dirname,'..');const hash=b=>crypto.createHash('sha256').update(b).digest('hex');
function transform(source){
 const rules=[
 ['import { tryLockExclusive } from "@deepseek-ai/node-addon-system/flock";', 'import { tryLockExclusive, publishNoReplaceAndroid } from "@deepseek-ai/node-addon-system/flock";'],
 ['await link(tmp, finalPath);','if (process.platform === "android") await publishNoReplaceAndroid(tmp, finalPath);\n\t\t\telse await link(tmp, finalPath);'],
 ['await internals.fs.link(staged, currentPath);','if (internals.platform === "android") await publishNoReplaceAndroid(staged, currentPath);\n\t\telse await internals.fs.link(staged, currentPath);'],
 ['published && internals.platform === "win32"','published && (internals.platform === "win32" || internals.platform === "android")']
 ];
 for(const [a,b]of rules){if(source.split(a).length!==2)throw new Error('Session adapter anchor mismatch: '+a);source=source.replace(a,b);}
 return source;
}
function apply(root){
 root=fs.realpathSync(root);const marker=JSON.parse(fs.readFileSync(path.join(root,'.dshm-native017-candidate.json')));if(marker.production!==false||marker.purpose!=='dshm-native017-candidate')throw new Error('Candidate-only marker required');
 const pkg=path.join(root,'node_modules/@deepseek-ai/dsh-session-persistence-jsonl');if(JSON.parse(fs.readFileSync(path.join(pkg,'package.json'))).version!=='0.1.7-rc.1')throw new Error('Wrong persistence version');const file=path.join(pkg,'lib/index.js');const before=fs.readFileSync(file);const meta=JSON.parse(fs.readFileSync(path.join(own,'session-source.json')));if(hash(before)!==meta.sha256)throw new Error('Unreviewed session persistence source');const after=transform(before.toString());fs.writeFileSync(file,after);return {before:hash(before),after:hash(after),atomicPrimitive:'renameat2(RENAME_NOREPLACE)',overwritesExisting:false};
}
if(require.main===module)console.log(JSON.stringify(apply(process.argv[2])));module.exports={apply,transform};
