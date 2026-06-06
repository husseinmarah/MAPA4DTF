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

# ============================================
# COMPOSE PROJECT NAME
# ============================================
$project = "mas-fed-dt"

Write-Host "Restarting Docker Desktop..." -ForegroundColor Cyan
wsl --shutdown
Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"
Start-Sleep -Seconds 15

Write-Host "Checking Docker connection..." -ForegroundColor Cyan
docker version


# ============================================
# FIND ALL CONTAINERS FOR THE COMPOSE PROJECT
# ============================================
Write-Host "Finding containers for project '$project'..." -ForegroundColor Cyan

$containers = docker ps -a --filter "label=com.docker.compose.project=$project" --format "{{.ID}}"

if (-not $containers) {
    Write-Host "No containers found for project '$project'." -ForegroundColor Yellow
} else {
    Write-Host "Containers found: $containers" -ForegroundColor Yellow
}


# ============================================
# STOP & REMOVE CONTAINERS
# ============================================
Write-Host "Stopping and removing containers..." -ForegroundColor Cyan

foreach ($c in $containers) {
    docker stop $c | Out-Null
    docker rm $c | Out-Null
    Write-Host "Removed container: $c" -ForegroundColor Yellow
}


# ============================================
# REMOVE IMAGES USED BY THESE CONTAINERS
# ============================================
Write-Host "Removing images for project '$project'..." -ForegroundColor Cyan

$images = docker images --filter "label=com.docker.compose.project=$project" --format "{{.ID}}" | Sort-Object -Unique

foreach ($img in $images) {
    docker rmi -f $img
    Write-Host "Deleted image: $img" -ForegroundColor Yellow
}


# ============================================
# CLEAN START OF THE COMPOSE STACK
# ============================================
Write-Host "Rebuilding and starting the '$project' stack..." -ForegroundColor Cyan

docker compose -p $project down -v
docker compose -p $project up -d --build

Write-Host "All services for '$project' started successfully!" -ForegroundColor Green
Write-Host "You can check the status with 'docker ps'." -ForegroundColor Green
# wait a few seconds for services to stabilize
Start-Sleep -Seconds 5
Write-Host "Current status of the containers:" -ForegroundColor Cyan
docker ps
Start-Sleep -Seconds 10
exit 0
# End of script

