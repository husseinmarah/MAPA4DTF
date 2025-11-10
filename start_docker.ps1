# start-env.ps1
Write-Host "Restarting Docker Desktop..." -ForegroundColor Cyan
wsl --shutdown
Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"
Start-Sleep -Seconds 15

Write-Host "Checking Docker connection..." -ForegroundColor Cyan
docker version

Write-Host "Starting Keycloak + OPA..." -ForegroundColor Cyan
docker-compose down -v; docker-compose up -d

Write-Host "All services started successfully!" -ForegroundColor Green
