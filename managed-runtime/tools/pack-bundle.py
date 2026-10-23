#!/usr/bin/env python3
"""Package a code-only runtime tree. Account/session directories must never be inputs."""
import argparse, hashlib, json, os, pathlib, stat, zipfile

def build(tree, output, slot_id):
    tree=pathlib.Path(tree).resolve(); output=pathlib.Path(output).resolve()
    if output.is_relative_to(tree): raise ValueError('output must be outside the source tree')
    files=[]; links=[]
    forbidden={'.env','.credentials.yaml','credentials.yaml','settings.yaml','selection.json','session.lock'}
    for parent, dirs, names in os.walk(tree, followlinks=False):
        for name in list(dirs)+names:
            p=pathlib.Path(parent)/name; rel=p.relative_to(tree).as_posix()
            if name in forbidden or name.startswith('.env.') or name.endswith('.keystore') or name.endswith('.jks') or '.git' in p.relative_to(tree).parts: raise ValueError('refuse private or VCS input: '+rel)
            if p.is_symlink():
                target=os.readlink(p)
                if os.path.isabs(target) or not p.resolve().is_relative_to(tree): raise ValueError('link escapes code bundle: '+rel)
                links.append({'path':rel,'target':target})
                if name in dirs: dirs.remove(name)
            elif p.is_file():
                digest=hashlib.sha256()
                with p.open('rb') as f:
                    for chunk in iter(lambda:f.read(1024*1024),b''): digest.update(chunk)
                st=p.stat();files.append({'path':rel,'size':st.st_size,'sha256':digest.hexdigest(),'executable':bool(st.st_mode&stat.S_IXUSR)})
    manifest={'schemaVersion':1,'id':slot_id,'backend':'linux-proot','arch':'arm64','dshVersion':'0.1.7-rc.1','nodeVersion':'24.22.0','dataSchema':'dsh-session-v4','node':'node/bin/node','cli':'node_modules/@deepseek-ai/dsh/lib/bin.js','files':sorted(files,key=lambda f:f['path']),'symlinks':sorted(links,key=lambda f:f['path']),'plugins':[]}
    required=[manifest['node'],manifest['cli'],'node_modules/@deepseek-ai/dsh/package.json','bootstrap/guest-guard.mjs']
    if not all(any(f['path']==n for f in files) for n in required): raise ValueError('source tree lacks critical runtime files')
    output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(output,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
        archive.writestr('manifest.json',json.dumps(manifest,separators=(',',':')))
        for f in manifest['files']:archive.write(tree/f['path'],f['path'])
    h=hashlib.sha256()
    with output.open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
    output.with_suffix(output.suffix+'.sha256').write_text(h.hexdigest()+'  '+output.name+'\n')
    print(json.dumps({'output':str(output),'files':len(files),'symlinks':len(links),'sha256':h.hexdigest(),'pluginsIncluded':0}))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--tree',required=True);p.add_argument('--output',required=True);p.add_argument('--id',required=True);a=p.parse_args();build(a.tree,a.output,a.id)
