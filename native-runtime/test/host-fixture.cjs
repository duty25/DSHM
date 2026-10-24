const path=require('node:path');
exports.makeHostPatch=observer=>{if(!path.isAbsolute(observer))throw new Error('Absolute owned observer path required');return [{id:'permission',config:{defaultPreset:'danger-full-access'}},{id:'sandbox-policy',config:{mode:'danger-full-access'}},{insert:[{id:'native017-startup-observer',name:observer}]}];};
