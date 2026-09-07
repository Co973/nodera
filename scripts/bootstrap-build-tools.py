"""Fetch portable build dependencies from their official distribution repositories."""
from pathlib import Path
import concurrent.futures, hashlib, json, shutil, urllib.request, zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'
DOWNLOADS = TOOLS / 'downloads'
DOWNLOADS.mkdir(parents=True, exist_ok=True)

def fetch(name, url, digest=None, algorithm='sha256'):
    target = DOWNLOADS / name
    if not target.exists():
        with urllib.request.urlopen(url, timeout=60) as src, target.with_suffix('.part').open('wb') as out:
            shutil.copyfileobj(src, out)
        target.with_suffix('.part').replace(target)
    if digest and hashlib.new(algorithm, target.read_bytes()).hexdigest() != digest:
        raise RuntimeError('Checksum mismatch: ' + name)
    print('Verified/downloaded ' + name, flush=True)
    return target

def unpack(path, destination, strip=False):
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path) as z:
        for item in z.infolist():
            parts = Path(item.filename).parts
            if strip: parts = parts[1:]
            if not parts: continue
            target = destination.joinpath(*parts).resolve()
            if not target.is_relative_to(destination.resolve()): raise RuntimeError('Unsafe archive path')
            if item.is_dir(): target.mkdir(parents=True,exist_ok=True)
            else:
                target.parent.mkdir(parents=True,exist_ok=True)
                with z.open(item) as src, target.open('wb') as out: shutil.copyfileobj(src,out)

jobs = [
    ('android-platform.zip','https://dl.google.com/android/repository/platform-35_r02.zip','0bb560a90a7a2cbd0dd8348224d518b638fe7949','sha1',TOOLS/'android-sdk/platforms/android-35',True),
    ('android-build-tools.zip','https://dl.google.com/android/repository/build-tools_r35_windows.zip','af059bb67cf7786f45ee0db85e2d24985df1b4b6','sha1',TOOLS/'android-sdk/build-tools/35.0.0',True),
    ('wix314-binaries.zip','https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip',None,'sha256',TOOLS/'wix',False),
]
def run(job):
    name,url,digest,algorithm,destination,strip=job
    path=fetch(name,url,digest,algorithm)
    unpack(path,destination,strip)
    print('Ready: '+str(destination),flush=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    list(pool.map(run,jobs))
for group,artifact,version in [('org/bouncycastle','bcprov-jdk18on','1.85.2'),('com/google/code/gson','gson','2.13.2')]:
    name=f'{artifact}-{version}.jar'
    url=f'https://repo.maven.apache.org/maven2/{group}/{artifact}/{version}/{name}'
    checksum=urllib.request.urlopen(url+'.sha256',timeout=30).read().decode().strip().split()[0]
    path=fetch(name,url,checksum)
    (TOOLS/'java-libs').mkdir(exist_ok=True)
    shutil.copy2(path,TOOLS/'java-libs'/name)
print('Portable build tools ready.',flush=True)
