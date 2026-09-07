"""Build and smoke-test the exact portable ZIP before promoting it to dist."""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / 'windows/build'
DIST = ROOT / 'dist/windows'
VERSION = '0.2.1'
BUILD.mkdir(parents=True, exist_ok=True)
DIST.mkdir(parents=True, exist_ok=True)
stage = Path(tempfile.mkdtemp(prefix='portable-', dir=BUILD))
payload = stage / 'payload'
payload.mkdir()
for folder in ('src', 'public'):
    shutil.copytree(ROOT / folder, payload / folder)
shutil.copy2(ROOT / 'package.json', payload / 'package.json')
(payload / 'runtime').mkdir()
node = Path(shutil.which('node'))
shutil.copy2(node, payload / 'runtime/node.exe')
node_version = subprocess.check_output([str(node), '--version'], text=True).strip()
(payload / 'licenses').mkdir()
# Ship the complete license belonging to the bundled Node version.
license_path = ROOT / '.tools/downloads' / ('NODE-' + node_version + '-LICENSE.txt')
if not license_path.exists():
    urllib.request.urlretrieve('https://raw.githubusercontent.com/nodejs/node/' + node_version + '/LICENSE', license_path)
if license_path.stat().st_size < 1000:
    raise RuntimeError('Full Node license missing')
shutil.copy2(license_path, payload / 'licenses/NODE-LICENSE.txt')
(payload / 'START-HERE.txt').write_text(
    'Nodera / Mesh Chat ' + VERSION + ' portable preview\n\n'
    'Extract the entire ZIP to a folder, then double-click Mesh.exe.\n'
    'No Node.js installation is needed. Keep runtime, src and public next to Mesh.exe.\n'
    'Your existing data stays in %LOCALAPPDATA%\\Mesh Chat\\data. Do not delete it.\n'
    'Quit the previous build using its tray menu before starting this version.\n'
    'Use the tray menu to reopen the browser or Quit and lock. Closing the browser does not stop the node.\n'
    'Peers use private LAN port 4341. Windows Bluetooth is not implemented.\n'
    'Experimental, unaudited encryption without forward secrecy. The launcher is unsigned.\n', encoding='utf-8')
compiler = Path(os.environ['WINDIR']) / 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
subprocess.run([str(compiler), '/nologo', '/target:winexe', '/platform:x64', '/optimize+',
                '/reference:System.Windows.Forms.dll', '/reference:System.Drawing.dll',
                '/out:' + str(payload / 'Mesh.exe'), str(ROOT / 'windows/Launcher.cs')], check=True)
archive = stage / ('Nodera-' + VERSION + '-x64-portable.zip')
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as zipped:
    for file in sorted(payload.rglob('*')):
        if file.is_file():
            zipped.write(file, file.relative_to(payload).as_posix())
extracted = stage / 'extracted'
with zipfile.ZipFile(archive) as zipped:
    if zipped.testzip() is not None:
        raise RuntimeError('ZIP integrity failure')
    zipped.extractall(extracted)
# Test extracted bytes, never an earlier staging directory. Use isolated data and
# an ephemeral LAN port so no existing node or personal vault is touched.
env = dict(os.environ)
env['MESH_DATA_DIR'] = str(stage / 'smoke-data')
env['MESH_TEST_PORT'] = '0'
result = stage / 'result.txt'
run = subprocess.run([str(extracted / 'Mesh.exe'), '--smoke-test', str(result)], env=env, timeout=40)
report = result.read_text() if result.exists() else 'No smoke-test result'
if run.returncode or not report.startswith('Packaged runtime started; authenticated readiness check passed.'):
    raise RuntimeError(report)
# Reproduce the case-duplicate environment seen in the original launcher test.
env['Path'] = env.get('PATH', env.get('Path', ''))
env['PATH'] = env['Path']
duplicate_result = stage / 'duplicate-env-result.txt'
duplicate_run = subprocess.run([str(extracted / 'Mesh.exe'), '--smoke-test', str(duplicate_result)], env=env, timeout=40)
if duplicate_run.returncode or not duplicate_result.read_text().startswith('Packaged runtime started; authenticated readiness check passed.'):
    raise RuntimeError('Case-duplicate environment smoke test failed')
print('Path/PATH duplicate environment: passed')
output = DIST / archive.name
shutil.copy2(archive, output)
digest = hashlib.sha256(output.read_bytes()).hexdigest()
(DIST / (archive.name + '.sha256')).write_text(digest + '  ' + archive.name + '\n')
print(report)
print('Verified ZIP: ' + str(output))
print('SHA256: ' + digest)
