#!/usr/bin/env python3
"""Download a pinned Android test Node closure. Extract only; never run package scripts."""
from pathlib import Path
import hashlib,json,subprocess,sys,urllib.request,urllib.parse
out=Path(sys.argv[1]).resolve()
if out.exists() and any(out.iterdir()):raise SystemExit('Refusing to overwrite a nonempty test runtime')
out.mkdir(parents=True,exist_ok=True)
lock=json.loads((Path(__file__).resolve().parents[1]/'termux-x86-lock.json').read_text())
cache=out/'debs';cache.mkdir()
for item in lock['packages']:
 filename=item['Filename']
 if not filename.startswith('pool/') or '..' in Path(filename).parts:raise SystemExit('Unsafe package path')
 url=lock['repository']+urllib.parse.quote(filename,safe='/+:._-')
 data=urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'DSHM-native017-proof'}),timeout=90).read()
 if len(data)!=int(item['Size']) or hashlib.sha256(data).hexdigest()!=item['SHA256']:raise SystemExit('Package integrity mismatch: '+item['Package'])
 deb=cache/(item['Package']+'.deb');deb.write_bytes(data)
 subprocess.run(['dpkg-deb','-x',str(deb),str(out/'extract')],check=True)
prefix=out/'extract/data/data/com.termux/files/usr'
node=prefix/'bin/node'
header=node.read_bytes()[:20]
if header[:6]!=bytes([127,69,76,70,2,1]) or int.from_bytes(header[18:20],'little')!=62:raise SystemExit('Not an Android x86_64 Node ELF')
if b'/system/bin/linker64' not in node.read_bytes():raise SystemExit('Node does not declare the Android linker')
(out/'test-node-provenance.json').write_text(json.dumps({'prefix':str(prefix),'purpose':lock['purpose'],'packages':lock['packages'],'scriptsExecuted':False},indent=2)+'\n')
print(str(prefix))
