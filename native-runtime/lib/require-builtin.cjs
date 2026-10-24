"use strict";
// Android already explicitly starts Node with --expose-internals. Preserve a narrow allowlist.
Object.defineProperty(exports,"__esModule",{value:true});
let api;
if(process.platform==='android'){
 const allowed=new Set(['internal/modules/esm/loader','internal/modules/cjs/loader','internal/modules/helpers','internal/modules/esm/utils','internal/modules/esm/resolve']);
 const req=require('node:module').createRequire(__filename);
 api={
  isAllowedInternalId:id=>typeof id==='string'&&allowed.has(id),
  requireBuiltin(id){
   if(!this.isAllowedInternalId(id))throw Object.assign(new Error('Internal module is outside the reviewed Android allowlist'),{code:'ERR_DSHM_INTERNAL_MODULE'});
   if(!process.execArgv.includes('--expose-internals'))throw Object.assign(new Error('Android runtime requires explicit --expose-internals'),{code:'ERR_DSHM_INTERNALS_FLAG'});
   return req(id);
  },
  getBindingInfo:()=>({backend:'android-exposed-internals',native:false,via:'--expose-internals'})
 };
}else api=require('node-addon-native-custom-loader').createEntryApi(require('node:path').resolve(__dirname,'..'));
exports.requireBuiltin=id=>api.requireBuiltin(id);
exports.isAllowedInternalId=id=>api.isAllowedInternalId(id);
exports.getBindingInfo=()=>api.getBindingInfo();
exports.default={requireBuiltin:exports.requireBuiltin,isAllowedInternalId:exports.isAllowedInternalId,getBindingInfo:exports.getBindingInfo};
