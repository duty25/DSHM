from pathlib import Path
import re

build = Path('build')
logs = sorted(build.glob('*.log'), key=lambda p: p.stat().st_mtime, reverse=True)
pattern = re.compile(r'error|exception|assert|ERR_|not found|mismatch|failed|fatal|denied|exit code', re.I)

hits = []
for p in logs:
    for s in p.read_text(errors='replace').splitlines():
        if pattern.search(s):
            hits.append(f'{p.name}: {s.strip()[:700]}')

for s in hits[-6:]:
    print('::error title=Native017 matched::' + s.replace('%', '%25').replace('\r', '').replace('\n', '%0A'))

if logs:
    newest = logs[0]
    tail = [s.strip() for s in newest.read_text(errors='replace').splitlines() if s.strip()][-20:]
    print(f'::error title=Native017 newest log::{newest.name} ({newest.stat().st_size} bytes)')
    for s in tail:
        print('::error title=Native017 tail::' + s[:700].replace('%', '%25').replace('\r', '').replace('\n', '%0A'))
