"""Build a signed preview APK using portable Android build-tools (no global SDK install)."""
from pathlib import Path
import hashlib, json, os, re, shutil, subprocess, zipfile, xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
TOOLS=ROOT/'.tools'
SDK=TOOLS/'android-sdk'
BT=SDK/'build-tools/35.0.0'
ANDROID=SDK/'platforms/android-35/android.jar'
BUILD=ROOT/'android/build/manual'
DIST=ROOT/'dist/android'
for directory in [BUILD,DIST]: directory.mkdir(parents=True,exist_ok=True)
for name in ['classes','generated','dex']:
    target=(BUILD/name).resolve()
    if not target.is_relative_to(BUILD.resolve()): raise RuntimeError('Unsafe build path')
    if target.exists(): shutil.rmtree(target)
    target.mkdir()

def run(args,**kwargs):
    print('Running '+Path(str(args[0])).name,flush=True)
    subprocess.run([str(a) for a in args],cwd=ROOT,check=True,**kwargs)

if not ANDROID.exists(): raise SystemExit('Run python scripts/bootstrap-build-tools.py first.')
libraries=sorted((ROOT/'android/build/libs').glob('*.jar'))
if len(libraries)<2: raise SystemExit('Java dependencies are missing.')
manifest=ET.parse(ROOT/'android/app/src/main/AndroidManifest.xml')
manifest.getroot().set('package','chat.mesh.android')
ET.register_namespace('android','http://schemas.android.com/apk/res/android')
manifest.write(BUILD/'AndroidManifest.xml',encoding='utf-8',xml_declaration=True)
run([BT/'aapt2.exe','compile','--dir',ROOT/'android/app/src/main/res','-o',BUILD/'resources.zip'])
run([BT/'aapt2.exe','link','-I',ANDROID,'--manifest',BUILD/'AndroidManifest.xml','--java',BUILD/'generated','--min-sdk-version','31','--target-sdk-version','35','--version-code','2','--version-name','0.2.0-preview','-A',ROOT/'public','-o',BUILD/'unsigned.apk',BUILD/'resources.zip'])
sources=list((ROOT/'android/app/src/main/java').rglob('*.java'))+list((BUILD/'generated').rglob('*.java'))
args=['-encoding','UTF-8','--release','17','-cp',os.pathsep.join(str(p) for p in [ANDROID,*libraries]),'-d',str(BUILD/'classes')]+[str(p) for p in sources]
# javac's argument file avoids the Windows command line size limit.
(BUILD/'javac.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
try: run(['javac','@'+str(BUILD/'javac.args')])
except subprocess.CalledProcessError:
    # Java 26 can report an archive-close error on OneDrive after valid classes are emitted.
    if not any((BUILD/'classes').rglob('*.class')): raise
with zipfile.ZipFile(BUILD/'app.jar','w',zipfile.ZIP_DEFLATED) as jar:
    for p in (BUILD/'classes').rglob('*.class'): jar.write(p,p.relative_to(BUILD/'classes').as_posix())
run(['java','-Xmx2g','-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--min-api','31','--lib',ANDROID,'--output',BUILD/'dex',BUILD/'app.jar',*libraries])
with zipfile.ZipFile(BUILD/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as apk:
    for dex in (BUILD/'dex').glob('*.dex'): apk.write(dex,dex.name)
run([BT/'zipalign.exe','-f','-p','4',BUILD/'unsigned.apk',BUILD/'aligned.apk'])
signing=TOOLS/'signing';signing.mkdir(exist_ok=True)
default_store=signing/'mesh-preview.p12'
credentials=signing/'preview-signing.json'
env=os.environ.copy()
if 'MESH_ANDROID_KEYSTORE' in env:
    store=Path(env['MESH_ANDROID_KEYSTORE'])
    if not env.get('MESH_ANDROID_STORE_PASS'): raise SystemExit('Set MESH_ANDROID_STORE_PASS for the supplied keystore.')
    alias=env.get('MESH_ANDROID_KEY_ALIAS','mesh')
else:
    store=default_store;alias='mesh-preview'
    if not credentials.exists(): credentials.write_text(json.dumps({'password':os.urandom(32).hex()}),encoding='utf-8')
    env['MESH_ANDROID_STORE_PASS']=json.loads(credentials.read_text())['password']
    if not store.exists():
        properties=subprocess.run(['java','-XshowSettings:properties','-version'],capture_output=True,text=True,check=True)
        java_home=Path(re.search(r'java.home = (.+)',properties.stderr).group(1).strip())
        run([java_home/'bin/keytool.exe','-genkeypair','-keystore',store,'-storetype','PKCS12','-storepass:env','MESH_ANDROID_STORE_PASS','-keypass:env','MESH_ANDROID_STORE_PASS','-alias',alias,'-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=Mesh Chat Preview'],env=env)
output=DIST/'Mesh-0.2.0-preview.apk'
run(['java','-jar',BT/'lib/apksigner.jar','sign','--ks',store,'--ks-key-alias',alias,'--ks-pass','env:MESH_ANDROID_STORE_PASS','--out',output,BUILD/'aligned.apk'],env=env)
run(['java','-jar',BT/'lib/apksigner.jar','verify','--verbose','--print-certs',output])
digest=hashlib.sha256(output.read_bytes()).hexdigest()
(DIST/'SHA256SUMS.txt').write_text(digest+'  '+output.name+'\n')
print(f'APK: {output} ({output.stat().st_size:,} bytes)',flush=True)
print('Keep .tools/signing/ private and backed up: future APK updates need the same signing key.',flush=True)
