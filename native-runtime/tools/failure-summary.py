from pathlib import Path
import re
lines=[]
for p in Path('build').glob('*.log'):
 for s in p.read_text(errors='replace').splitlines():
  if re.search(r'error:|Error:|AssertionError|ERR_|not found|mismatch|failed',s,re.I):lines.append(s[:900])
for s in lines[-4:]:print('::error title=Native017 proof failure::'+s.replace('%','%25').replace('\r','%0D').replace('\n','%0A'))
