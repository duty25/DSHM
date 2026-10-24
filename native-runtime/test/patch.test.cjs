const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const os=require('node:os');
const {apply}=require('../tools/apply-flock.cjs');
const loader=require('../lib/android-binding.cjs');
const source=fs.readFileSync(path.join(__dirname,'../vendor/upstream-flock.js'));
function fixture(t){const root=fs.mkdtempSync(path.join(os.tmpdir(),'dshm-flock-patch-'));t.after(()=>fs.rmSync(root,{recursive:true,force:true}));const nm=path.join(root,'node_modules/@deepseek-ai');fs.mkdirSync(path.join(nm,'dsh'),{recursive:true});fs.mkdirSync(path.join(nm,'node-addon-system/lib'),{recursive:true});fs.writeFileSync(path.join(root,'.dshm-native017-candidate.json'),JSON.stringify({purpose:'dshm-native017-candidate',production:false}));fs.writeFileSync(path.join(nm,'dsh/package.json'),JSON.stringify({version:'0.1.7-rc.1'}));fs.writeFileSync(path.join(nm,'node-addon-system/package.json'),JSON.stringify({version:'0.1.2'}));fs.writeFileSync(path.join(nm,'node-addon-system/lib/flock.js'),source);return root;}
test('pinned candidate patch and idempotent replay',t=>{const root=fixture(t);assert.equal(apply(root).noOpFallback,false);assert.equal(apply(root).alreadyApplied,true);});
test('unreviewed source is never overwritten',t=>{const root=fixture(t);const file=path.join(root,'node_modules/@deepseek-ai/node-addon-system/lib/flock.js');fs.appendFileSync(file,'// changed');const before=fs.readFileSync(file);assert.throws(()=>apply(root),/Unreviewed/);assert.deepEqual(fs.readFileSync(file),before);});
test('live or unmarked directories are rejected',t=>{const root=fixture(t);fs.writeFileSync(path.join(root,'.dshm-native017-candidate.json'),JSON.stringify({purpose:'dshm-native017-candidate',production:true}));assert.throws(()=>apply(root),/Candidate-only/);});
test('wrong SDK version is rejected',t=>{const root=fixture(t);fs.writeFileSync(path.join(root,'node_modules/@deepseek-ai/dsh/package.json'),JSON.stringify({version:'0.2.0-rc.2'}));assert.throws(()=>apply(root),/pinned DSH/);});
test('missing native library is an explicit error, not lock success',()=>{const old=process.env.DSHM_ANDROID_FLOCK_PATH;delete process.env.DSHM_ANDROID_FLOCK_PATH;try{assert.throws(()=>loader.loadBinding(),{code:'ERR_DSHM_FLOCK_PATH'});}finally{if(old!==undefined)process.env.DSHM_ANDROID_FLOCK_PATH=old;}});
test('Android branch does not replace Linux or Darwin platform checks',()=>{const overlay=fs.readFileSync(path.join(__dirname,'../lib/flock.js'),'utf8');assert.match(overlay,/platform === 'android'/);assert.match(overlay,/platform !== 'linux' && platform !== 'darwin'/);assert.doesNotMatch(overlay,/noopBinding|callback\(0\)|defineProperty\(process/);});

const {transform}=require('../tools/apply-session.cjs');
test('session publication adapter is narrowly anchored and preserves collision semantics',()=>{
 const input=['import { tryLockExclusive } from "@deepseek-ai/node-addon-system/flock";','await link(tmp, finalPath);','await internals.fs.link(staged, currentPath);','published && internals.platform === "win32"'].join('\n');
 const out=transform(input);assert.match(out,/publishNoReplaceAndroid\(tmp, finalPath\)/);assert.match(out,/publishNoReplaceAndroid\(staged, currentPath\)/);assert.match(out,/else await link\(tmp, finalPath\)/);assert.match(out,/internals.platform === "android"/);assert.throws(()=>transform(input+'\nawait link(tmp, finalPath);'),/anchor mismatch/);
});

const vm=require('node:vm');
function builtinFixture(flags){const box={exports:{},__filename:'/owned/index.js',process:{platform:'android',execArgv:flags},require:name=>{assert.equal(name,'node:module');return{createRequire:()=>id=>({id})};}};vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../lib/require-builtin.cjs'),'utf8'),box);return box.exports;}
test('builtin adapter only admits reviewed internal modules with explicit flag',()=>{const api=builtinFixture(['--expose-internals']);assert.equal(api.requireBuiltin('internal/modules/esm/loader').id,'internal/modules/esm/loader');assert.equal(api.isAllowedInternalId('fs'),false);assert.throws(()=>api.requireBuiltin('/private/file'),{code:'ERR_DSHM_INTERNAL_MODULE'});});
test('builtin adapter fails without explicit Node startup flag',()=>{assert.throws(()=>builtinFixture([]).requireBuiltin('internal/modules/helpers'),{code:'ERR_DSHM_INTERNALS_FLAG'});});
