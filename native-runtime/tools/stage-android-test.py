#!/usr/bin/env python3
"""Build a regular-file-only test payload; never change Android permissions."""
from pathlib import Path
import os,shutil,sys,json,hashlib
prefix=Path(sys.argv[1]).resolve();sdk=Path(sys.argv[2]).resolve();out=Path(sys.argv[3]).resolve()
if out.exists():raise SystemExit('Test stage already exists; refusing overwrite')
(out/'node/bin').mkdir(parents=True);(out/'node/lib').mkdir();(out/'core').mkdir()
records=[]
def copy(source,target,boundary):
 original=source
 if source.is_symlink():
  name=os.readlink(source)
  termux='/data/data/com.termux/files/usr/'
  if name.startswith(termux):source=prefix/name[len(termux):]
  elif Path(name).is_absolute():raise RuntimeError('Unreviewed absolute symlink: '+str(original))
  else:source=source.parent/name
 source=source.resolve(strict=True)
 if not source.is_relative_to(boundary) or not source.is_file():raise RuntimeError('Test asset escapes its source tree: '+str(original))
 target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)
 if target.is_symlink():raise RuntimeError('Test stage unexpectedly contains symlink')
 if boundary==prefix:records.append({'path':str(target.relative_to(out)),'sha256':hashlib.sha256(target.read_bytes()).hexdigest()})
copy(prefix/'bin/node',out/'node/bin/node',prefix)
for source in (prefix/'lib').rglob('*'):
 if '.so' in source.name and (source.is_file() or source.is_symlink()):copy(source,out/'node/lib'/source.relative_to(prefix/'lib'),prefix)
count=0
for base,dirs,files in os.walk(sdk,followlinks=False):
 dirs[:]=[d for d in dirs if d not in ['.bin','__pycache__']]
 for d in dirs:
  if (Path(base)/d).is_symlink():raise RuntimeError('Unexpected SDK directory symlink')
 for f in files:
  source=Path(base)/f;copy(source,out/'core'/source.relative_to(sdk),sdk);count+=1
(out/'stage-manifest.json').write_text(json.dumps({'sdkFiles':count,'nativeFiles':records,'symlinksInPayload':False,'androidPermissionsChanged':False},indent=2)+'\n')
print('Prepared regular-file test input:',count,'SDK files and',len(records),'native files')
