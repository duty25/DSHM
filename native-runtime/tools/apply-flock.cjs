#!/usr/bin/env node
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const own = path.resolve(__dirname, '..');
const hash = b => crypto.createHash('sha256').update(b).digest('hex');
function apply(root) {
  root = fs.realpathSync(root);
  const marker = JSON.parse(fs.readFileSync(path.join(root, '.dshm-native017-candidate.json'), 'utf8'));
  if (marker.purpose !== 'dshm-native017-candidate' || marker.production !== false) throw new Error('Candidate-only marker required');
  const nm = path.join(root, 'node_modules');
  const pkg = JSON.parse(fs.readFileSync(path.join(nm, '@deepseek-ai/dsh/package.json'), 'utf8'));
  if (pkg.version !== '0.1.7-rc.1') throw new Error('Only pinned DSH 0.1.7-rc.1 is supported');
  const base = path.join(nm, '@deepseek-ai/node-addon-system');
  const addon = JSON.parse(fs.readFileSync(path.join(base, 'package.json'), 'utf8'));
  if (addon.version !== '0.1.2') throw new Error('Unexpected node-addon-system version');
  const target = path.join(base, 'lib/flock.js');
  const meta = JSON.parse(fs.readFileSync(path.join(own, 'upstream.json'), 'utf8'));
  const replacement = fs.readFileSync(path.join(own, 'lib/flock.js'));
  const current = fs.readFileSync(target);
  const before = hash(current);
  if (before !== meta.files['upstream-flock.js'] && before !== hash(replacement)) throw new Error('Unreviewed flock source; refusing to overwrite');
  const helper = fs.readFileSync(path.join(own, 'lib/android-binding.cjs'));
  fs.writeFileSync(path.join(base, 'lib/dshm-android-flock.cjs'), helper);
  fs.writeFileSync(target, replacement);
  const result = { coreVersion:pkg.version, addonVersion:addon.version, before, after:hash(replacement), loader:hash(helper), alreadyApplied:before===hash(replacement), nativeBackend:'posix-flock', noOpFallback:false };
  fs.writeFileSync(path.join(root, 'native-flock-patch.json'), JSON.stringify(result,null,2)+'\n');
  return result;
}
if (require.main === module) console.log(JSON.stringify(apply(process.argv[2]),null,2));
module.exports = { apply };
