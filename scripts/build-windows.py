"""Build a self-contained, per-user x64 Windows MSI; never include workspace data."""
from pathlib import Path
import hashlib, os, shutil, subprocess, uuid, xml.etree.ElementTree as ET
from PIL import Image,ImageDraw,ImageFont

ROOT=Path(__file__).resolve().parents[1]
BUILD=ROOT/'windows/build';STAGE=BUILD/'payload';DIST=ROOT/'dist/windows'
for directory in [BUILD,STAGE,DIST]: directory.mkdir(parents=True,exist_ok=True)
for item in STAGE.iterdir():
    target=item.resolve()
    if not target.is_relative_to(STAGE.resolve()): raise RuntimeError('Unsafe staging path')
    if item.is_dir(): shutil.rmtree(item)
    else: item.unlink()
for folder in ['src','public']: shutil.copytree(ROOT/folder,STAGE/folder)
shutil.copy2(ROOT/'package.json',STAGE/'package.json')
(STAGE/'runtime').mkdir();node=Path(shutil.which('node'))
shutil.copy2(node,STAGE/'runtime/node.exe')
(STAGE/'licenses').mkdir();license=ROOT/'.tools/downloads/NODE-LICENSE.txt'
if license.exists(): shutil.copy2(license,STAGE/'licenses/NODE-LICENSE.txt')
else: (STAGE/'licenses/NODE-LICENSE.txt').write_text('Node.js runtime license and source: https://github.com/nodejs/node\n',encoding='utf-8')
(STAGE/'START-HERE.txt').write_text('Mesh Chat 0.2.0 preview\n\nOpen Mesh Chat from the Start menu. The tray menu can reopen the UI or quit and lock the node.\nAdd peers on your private LAN using port 4341. Users do not need Node.js installed.\nData is stored separately in %LOCALAPPDATA%\\Mesh Chat\\data and is preserved on uninstall.\nClosing the browser does not stop the node; choose Quit and lock from the tray.\n\nThis preview uses experimental encryption without forward secrecy. Windows Bluetooth, Wi-Fi Direct, and Meshtastic are not implemented.\n',encoding='utf-8')
image=Image.new('RGBA',(256,256),(0,0,0,0));draw=ImageDraw.Draw(image);draw.rounded_rectangle((8,8,248,248),radius=55,fill='#315c41');font=ImageFont.truetype('C:/Windows/Fonts/segoeuib.ttf',195);draw.text((128,111),'m',font=font,anchor='mm',fill='#fffefb');icon=BUILD/'mesh.ico';image.save(icon,sizes=[(16,16),(32,32),(48,48),(64,64),(128,128),(256,256)])
compiler=Path(os.environ['WINDIR'])/'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
subprocess.run([str(compiler),'/nologo','/target:winexe','/platform:x64','/optimize+','/reference:System.Windows.Forms.dll','/reference:System.Drawing.dll','/win32icon:'+str(icon),'/out:'+str(STAGE/'Mesh.exe'),str(ROOT/'windows/Launcher.cs')],check=True)
NS='http://schemas.microsoft.com/wix/2006/wi';ET.register_namespace('',NS)
def add(parent,tag,**attrs):return ET.SubElement(parent,'{'+NS+'}'+tag,{k:str(v) for k,v in attrs.items()})
wix=ET.Element('{'+NS+'}Wix')
product=add(wix,'Product',Id='*',Name='Mesh Chat Preview',Language='1033',Version='0.2.0',Manufacturer='Mesh Chat',UpgradeCode='4B08C7DA-C9B0-4C3C-87E0-9674D1C5F470')
add(product,'Package',InstallerVersion='500',Compressed='yes',InstallScope='perUser',Platform='x64',Description='Mesh Chat desktop preview')
add(product,'MajorUpgrade',DowngradeErrorMessage='A newer Mesh Chat version is already installed.')
add(product,'MediaTemplate',EmbedCab='yes',CompressionLevel='high')
add(product,'Property',Id='ARPCOMMENTS',Value='Experimental local-network messaging. User data is preserved on uninstall.')
add(product,'Property',Id='ARPNOMODIFY',Value='1')
add(product,'Icon',Id='MeshIcon',SourceFile=str(icon));add(product,'Property',Id='ARPPRODUCTICON',Value='MeshIcon')
directory=add(product,'Directory',Id='TARGETDIR',Name='SourceDir');local=add(directory,'Directory',Id='LocalAppDataFolder');programs=add(local,'Directory',Id='UserPrograms',Name='Programs');install=add(programs,'Directory',Id='INSTALLFOLDER',Name='Mesh Chat')
menu=add(directory,'Directory',Id='ProgramMenuFolder');shortcuts=add(menu,'Directory',Id='MeshMenu',Name='Mesh Chat')
feature=add(product,'Feature',Id='MainFeature',Title='Mesh Chat',Level='1')
namespace=uuid.UUID('bef4f2a4-7b72-4bc7-8ce8-53be3ac3e123')
directories={'.':install}
for path in sorted(STAGE.rglob('*')):
    relative=path.relative_to(STAGE).as_posix()
    if path.is_dir():directories[relative]=add(directories[path.parent.relative_to(STAGE).as_posix()],'Directory',Id='D'+hashlib.sha256(relative.encode()).hexdigest()[:20],Name=path.name);continue
    suffix=hashlib.sha256(relative.encode()).hexdigest()[:20];cid='C'+suffix
    component=add(directories[path.parent.relative_to(STAGE).as_posix()],'Component',Id=cid,Guid=str(uuid.uuid5(namespace,relative)),Win64='yes')
    add(component,'File',Id='LauncherFile' if relative=='Mesh.exe' else 'F'+suffix,Source=str(path),Name=path.name)
    add(component,'RegistryValue',Root='HKCU',Key='Software\\Mesh Chat\\Installer',Name=suffix,Type='integer',Value='1',KeyPath='yes')
    add(feature,'ComponentRef',Id=cid)
component=add(shortcuts,'Component',Id='StartMenuShortcut',Guid='B67FDC7A-6B7A-44A0-B7AF-98712A80B6B8',Win64='yes')
add(component,'Shortcut',Id='MeshStart',Name='Mesh Chat',Description='Open your local Mesh node',Target='[INSTALLFOLDER]Mesh.exe',WorkingDirectory='INSTALLFOLDER',Icon='MeshIcon')
add(component,'RemoveFolder',Id='RemoveMenu',On='uninstall');add(component,'RegistryValue',Root='HKCU',Key='Software\\Mesh Chat\\Installer',Name='StartMenu',Type='integer',Value='1',KeyPath='yes');add(feature,'ComponentRef',Id='StartMenuShortcut')
source=BUILD/'Package.wxs';ET.ElementTree(wix).write(source,encoding='utf-8',xml_declaration=True)
wixbin=ROOT/'.tools/wix';obj=BUILD/'Package.wixobj';output=DIST/'Mesh-0.2.0-x64.msi'
subprocess.run([str(wixbin/'candle.exe'),'-nologo','-arch','x64','-out',str(obj),str(source)],check=True)
subprocess.run([str(wixbin/'light.exe'),'-nologo','-out',str(output),str(obj)],check=True)
digest=hashlib.sha256(output.read_bytes()).hexdigest();(DIST/'SHA256SUMS.txt').write_text(digest+'  '+output.name+'\n')
print(f'MSI: {output} ({output.stat().st_size:,} bytes)')
print('The MSI and launcher are unsigned; no publisher certificate was supplied.')
