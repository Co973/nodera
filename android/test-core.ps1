$ErrorActionPreference = 'Stop'
$meshClasses = Join-Path $PSScriptRoot 'build\core-test'
New-Item -ItemType Directory -Force -Path $meshClasses | Out-Null
javac --release 17 -d $meshClasses (Join-Path $PSScriptRoot 'app\src\main\java\chat\mesh\core\Packet.java') (Join-Path $PSScriptRoot 'app\src\main\java\chat\mesh\core\Transport.java') (Join-Path $PSScriptRoot 'core-test\CoreTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Core compilation failed' }
java -cp $meshClasses CoreTest
if ($LASTEXITCODE -ne 0) { throw 'Core tests failed' }
