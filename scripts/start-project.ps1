$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
function Port-Open([int]$port) {
    $client=[Net.Sockets.TcpClient]::new()
    try { $client.Connect('127.0.0.1',$port); return $true } catch { return $false } finally { $client.Dispose() }
}
if (-not (Test-Path (Join-Path $projectRoot 'target/hanyunjing-0.0.1-SNAPSHOT.jar'))) { throw 'Run mvn package in the project root first.' }
if (-not (Port-Open 8082)) {
    Start-Process -FilePath (Get-Command java).Source -ArgumentList '-jar','target/hanyunjing-0.0.1-SNAPSHOT.jar' -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $projectRoot '.local/backend-start.stdout.log') -RedirectStandardError (Join-Path $projectRoot '.local/backend-start.stderr.log')
}
if (-not (Port-Open 5173)) {
    Start-Process -FilePath 'cmd.exe' -ArgumentList '/d','/c','npm run dev' -WorkingDirectory (Join-Path $projectRoot 'frontend') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $projectRoot '.local/frontend-start.stdout.log') -RedirectStandardError (Join-Path $projectRoot '.local/frontend-start.stderr.log')
}
if (-not (Port-Open 5174)) {
    Start-Process -FilePath 'cmd.exe' -ArgumentList '/d','/c','npm run dev:admin' -WorkingDirectory (Join-Path $projectRoot 'frontend') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $projectRoot '.local/admin-start.stdout.log') -RedirectStandardError (Join-Path $projectRoot '.local/admin-start.stderr.log')
}
Write-Output 'Storefront: http://127.0.0.1:5173/ | Admin: http://127.0.0.1:5174/ | API: http://127.0.0.1:8082/api. Existing listeners are kept; verify the pages.'
