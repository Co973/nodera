$ErrorActionPreference = 'Stop'
$meshRoot = Split-Path $PSScriptRoot -Parent
$meshClasses = Join-Path $meshRoot 'android\build\jvm'
New-Item -ItemType Directory -Force $meshClasses | Out-Null
$meshSources = @(Get-ChildItem (Join-Path $meshRoot 'android\app\src\main\java\chat\mesh\core\*.java') | ForEach-Object FullName)
$meshSources += @(Get-ChildItem (Join-Path $meshRoot 'android\core-test\*.java') | ForEach-Object FullName)
javac --release 17 -encoding UTF-8 -cp (Join-Path $meshRoot '.tools\java-libs\*') -d $meshClasses $meshSources
if ($LASTEXITCODE -ne 0) { throw 'Android core compilation failed' }
java -cp "$meshClasses;$meshRoot\.tools\java-libs\*" CoreTest
if ($LASTEXITCODE -ne 0) { throw 'Android core tests failed' }
