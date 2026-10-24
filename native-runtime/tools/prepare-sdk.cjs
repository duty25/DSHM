const fs=require('node:fs');const path=require('node:path');const {apply}=require('./apply-flock.cjs');const sdk=path.resolve(__dirname,'../sdk');fs.writeFileSync(path.join(sdk,'.dshm-native017-candidate.json'),JSON.stringify({purpose:'dshm-native017-candidate',production:false}));console.log(JSON.stringify(apply(sdk)));
console.log(JSON.stringify(require('./apply-session.cjs').apply(sdk)));
console.log(JSON.stringify(require('./apply-builtin.cjs').apply(sdk)));
