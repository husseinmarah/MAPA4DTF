# OPA and Keycloak Startup Script
# This script starts and verifies OPA and Keycloak services for the federation system

Write-Host "🚀 Starting OPA and Keycloak Services for Federation System" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""

# Check if Docker is running
Write-Host "📋 Checking Docker status..." -ForegroundColor Yellow
try {
    docker version | Out-Null
    Write-Host "✅ Docker is running" -ForegroundColor Green
} catch {
    Write-Host "❌ Docker is not running. Please start Docker Desktop." -ForegroundColor Red
    exit 1
}

Write-Host ""

# Start services
Write-Host "🐳 Starting services with Docker Compose..." -ForegroundColor Yellow
docker-compose up -d

Write-Host ""

# Wait for services to be ready
Write-Host "⏳ Waiting for services to initialize..." -ForegroundColor Yellow
Start-Sleep -Seconds 5

# Check OPA
Write-Host ""
Write-Host "🔍 Checking OPA (Open Policy Agent)..." -ForegroundColor Yellow
$opaRetries = 0
$opaMaxRetries = 10
$opaReady = $false

while ($opaRetries -lt $opaMaxRetries -and -not $opaReady) {
    try {
        $response = Invoke-WebRequest -Uri "http://localhost:8181/health" -Method Get -TimeoutSec 2
        if ($response.StatusCode -eq 200) {
            $opaReady = $true
            Write-Host "✅ OPA is ready at http://localhost:8181" -ForegroundColor Green
            Write-Host "   Policy endpoint: http://localhost:8181/v1/data/authz/allow" -ForegroundColor Gray
        }
    } catch {
        $opaRetries++
        Write-Host "   Attempt $opaRetries/$opaMaxRetries - OPA not ready yet..." -ForegroundColor Gray
        Start-Sleep -Seconds 2
    }
}

if (-not $opaReady) {
    Write-Host "⚠️  OPA is not responding after $opaMaxRetries attempts" -ForegroundColor Yellow
    Write-Host "   The system will work with local policies only" -ForegroundColor Yellow
}

# Check Keycloak
Write-Host ""
Write-Host "🔍 Checking Keycloak (IAM)..." -ForegroundColor Yellow
$keycloakRetries = 0
$keycloakMaxRetries = 30
$keycloakReady = $false

while ($keycloakRetries -lt $keycloakMaxRetries -and -not $keycloakReady) {
    try {
        $response = Invoke-WebRequest -Uri "http://localhost:8080/realms/warehouse-federation" -Method Get -TimeoutSec 3
        if ($response.StatusCode -eq 200) {
            $keycloakReady = $true
            Write-Host "✅ Keycloak is ready at http://localhost:8080" -ForegroundColor Green
            Write-Host "   Realm: warehouse-federation" -ForegroundColor Gray
            Write-Host "   Admin Console: http://localhost:8080/admin" -ForegroundColor Gray
            Write-Host "   Admin Credentials: admin/admin" -ForegroundColor Gray
        }
    } catch {
        $keycloakRetries++
        if ($keycloakRetries % 5 -eq 0) {
            Write-Host "   Attempt $keycloakRetries/$keycloakMaxRetries - Keycloak is starting (this may take up to 60s)..." -ForegroundColor Gray
        }
        Start-Sleep -Seconds 2
    }
}

if (-not $keycloakReady) {
    Write-Host "⚠️  Keycloak is not responding after $keycloakMaxRetries attempts" -ForegroundColor Yellow
    Write-Host "   The system will work with local authentication only" -ForegroundColor Yellow
}

# Summary
Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "📊 Service Status Summary" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

if ($opaReady) {
    Write-Host "OPA:       ✅ READY" -ForegroundColor Green
} else {
    Write-Host "OPA:       ❌ NOT READY" -ForegroundColor Yellow
}

if ($keycloakReady) {
    Write-Host "Keycloak:  ✅ READY" -ForegroundColor Green
} else {
    Write-Host "Keycloak:  ❌ NOT READY" -ForegroundColor Yellow
}

Write-Host ""

# Show container status
Write-Host "🐳 Container Status:" -ForegroundColor Cyan
docker-compose ps

Start-Sleep -Seconds 2

Write-Host ""

# Provide next steps
if ($opaReady -and $keycloakReady) {
    Write-Host "✅ All services are ready!" -ForegroundColor Green
    Write-Host ""
} else {
    Write-Host "⚠️  Some services are not ready" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Troubleshooting:" -ForegroundColor Cyan
    Write-Host "  • Check logs: docker-compose logs -f" -ForegroundColor White
    Write-Host "  • Restart services: docker-compose restart" -ForegroundColor White
    Write-Host "  • Stop services: docker-compose down" -ForegroundColor White
    Write-Host ""
    Write-Host "The system will work with reduced functionality:" -ForegroundColor Yellow
    Write-Host "  • Local authentication instead of Keycloak" -ForegroundColor White
    Write-Host "  • Local policies instead of OPA" -ForegroundColor White
}

Write-Host ""
Write-Host "Useful commands:" -ForegroundColor Cyan
Write-Host "  • View logs:        docker-compose logs -f" -ForegroundColor White
Write-Host "  • Stop services:    docker-compose down" -ForegroundColor White
Write-Host "  • Restart services: docker-compose restart" -ForegroundColor White
Write-Host "  • Service status:   docker-compose ps" -ForegroundColor White
Write-Host ""
Write-Host "For detailed integration guide, see: docs/OPA_KEYCLOAK_INTEGRATION.md" -ForegroundColor Gray
Write-Host ""
