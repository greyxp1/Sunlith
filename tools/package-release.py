"""Packages the built release APK and the computer companion into dist/ with checksums."""
from pathlib import Path
import hashlib, json, shutil, zipfile

root = Path(__file__).resolve().parents[1]
version = json.loads((root / 'integrations/mcp/package.json').read_text())['version']
if f'versionName = "{version}"' not in (root / 'app/build.gradle.kts').read_text():
    raise SystemExit('Android and companion versions must match')
dist = root / 'dist'
dist.mkdir(exist_ok=True)
apk = dist / f'Sunlith-v{version}.apk'
shutil.copy2(root / 'app/build/outputs/apk/release/app-release.apk', apk)
archive = dist / f'Sunlith-companion-v{version}.zip'
prefix = f'Sunlith-companion-v{version}/'
files = ['LICENSE', 'NOTICE', 'SECURITY.md', 'docs/AI-INTEGRATION.md',
         'integrations/mcp/package.json', 'integrations/mcp/package-lock.json', 'integrations/mcp/README.md']
files += [p.relative_to(root).as_posix() for p in sorted((root / 'integrations/mcp/src').glob('*.js'))]
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as z:
    for f in files: z.write(root / f, prefix + f)
sums = ''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in [apk, archive])
(dist / 'SHA256SUMS.txt').write_text(sums)
print(sums, end='')
