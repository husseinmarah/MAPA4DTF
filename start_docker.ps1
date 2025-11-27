# start-env.ps1

# Check if running as administrator
if (-NOT ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole] "Administrator")) {
    Write-Host "Restarting script as administrator..." -ForegroundColor Yellow
    Start-Process pwsh -Verb RunAs -ArgumentList "-NoExit", "-File", "`"$PSCommandPath`""
    exit
}

# Close ports used by OPA and Keycloak (8080, 8181) before starting Docker
Write-Host "Closing processes listening on ports 8080 and 8181..." -ForegroundColor Cyan
$ports = 8080,8181
$pids = (Get-NetTCPConnection -LocalPort $ports -State Listen -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique)
if ($pids) {
    foreach ($procId in $pids) {
    try {
        Stop-Process -Id $procId -Force -ErrorAction Stop
        Write-Host "Stopped process PID $procId" -ForegroundColor Yellow
    } catch {
        Write-Host "Failed to stop PID ${procId}: $($_.Exception.Message)" -ForegroundColor Red
    }
    }
} else {
    Write-Host "No processes listening on ports $($ports -join ', ')." -ForegroundColor Green
}

Write-Host "Restarting Docker Desktop..." -ForegroundColor Cyan
wsl --shutdown
Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"
Start-Sleep -Seconds 15

Write-Host "Checking Docker connection..." -ForegroundColor Cyan
docker version

Write-Host "Starting Keycloak + OPA..." -ForegroundColor Cyan
docker-compose down -v; docker-compose up -d

Write-Host "All services started successfully!" -ForegroundColor Green

Start-Sleep -Seconds 10
exit 0
# End of script