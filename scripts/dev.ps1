<#
.SYNOPSIS
    QE-RAG System — Developer CLI Script (Windows PowerShell)

.DESCRIPTION
    Provides single-command control for starting, stopping, testing, and checking
    the health of all system components (PostgreSQL/pgvector, Neo4j, Parser Service,
    Ollama, Backend, Frontend, and Observability).

.EXAMPLE
    .\scripts\dev.ps1 up          # Start all background containers via docker compose
    .\scripts\dev.ps1 down        # Stop all containers
    .\scripts\dev.ps1 status      # Show container and port status
    .\scripts\dev.ps1 health      # Query all service health probes
    .\scripts\dev.ps1 test        # Run all backend, sandbox, and frontend verification tests
#>

[CmdletBinding()]
param(
    [Parameter(Position = 0, Mandatory = $false)]
    [ValidateSet("up", "down", "restart", "status", "health", "test", "help")]
    [string]$Action = "help"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot

function Show-Header {
    param([string]$Title)
    Write-Host "`n========================================================" -ForegroundColor Cyan
    Write-Host "  $Title" -ForegroundColor Cyan
    Write-Host "========================================================`n" -ForegroundColor Cyan
}

switch ($Action) {
    "up" {
        Show-Header "Starting Docker Services"
        Set-Location $ProjectRoot
        docker compose up -d
        Write-Host "`nDocker services starting in background." -ForegroundColor Green
        Write-Host "Run '.\scripts\dev.ps1 health' to verify liveness." -ForegroundColor Yellow
    }

    "down" {
        Show-Header "Stopping Docker Services"
        Set-Location $ProjectRoot
        docker compose down
        Write-Host "All containers stopped." -ForegroundColor Green
    }

    "restart" {
        Show-Header "Restarting Docker Services"
        Set-Location $ProjectRoot
        docker compose down
        docker compose up -d
        Write-Host "Docker services restarted." -ForegroundColor Green
    }

    "status" {
        Show-Header "Container Status"
        Set-Location $ProjectRoot
        docker compose ps
    }

    "health" {
        Show-Header "Checking System Health Probes"
        $endpoints = @(
            @{ Name = "Spring Boot Actuator"; Url = "http://localhost:8080/actuator/health" },
            @{ Name = "Python Parser Service"; Url = "http://localhost:8000/health" },
            @{ Name = "Prometheus Probe"; Url = "http://localhost:9090/-/healthy" },
            @{ Name = "Grafana Health"; Url = "http://localhost:3001/api/health" },
            @{ Name = "Ollama Runtime"; Url = "http://localhost:11434/" },
            @{ Name = "Frontend Studio"; Url = "http://localhost:3000/" }
        )

        foreach ($ep in $endpoints) {
            try {
                $response = Invoke-WebRequest -Uri $ep.Url -UseBasicParsing -TimeoutSec 3 -ErrorAction SilentlyContinue
                if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400) {
                    Write-Host "  [OK]       $($ep.Name) ($($ep.Url)) - HTTP $($response.StatusCode)" -ForegroundColor Green
                } else {
                    Write-Host "  [WARN]     $($ep.Name) ($($ep.Url)) - HTTP $($response.StatusCode)" -ForegroundColor Yellow
                }
            } catch {
                Write-Host "  [OFFLINE]  $($ep.Name) ($($ep.Url)) - Unreachable" -ForegroundColor Red
            }
        }
    }

    "test" {
        Show-Header "Running Full Verification Test Suite"

        Write-Host "1/3. Testing Backend Orchestrator..." -ForegroundColor Cyan
        Set-Location "$ProjectRoot\backend"
        & .\mvnw.cmd test
        if ($LASTEXITCODE -ne 0) { throw "Backend tests failed!" }

        Write-Host "`n2/3. Testing Sandbox Project & JaCoCo..." -ForegroundColor Cyan
        Set-Location "$ProjectRoot\test-sandbox"
        & .\mvnw.cmd test jacoco:report
        if ($LASTEXITCODE -ne 0) { throw "Sandbox tests failed!" }

        Write-Host "`n3/3. Building Frontend Bundle..." -ForegroundColor Cyan
        Set-Location "$ProjectRoot\frontend"
        npm run build
        if ($LASTEXITCODE -ne 0) { throw "Frontend build failed!" }

        Set-Location $ProjectRoot
        Write-Host "`nALL VERIFICATION TESTS PASSED SUCCESSFULLY!" -ForegroundColor Green
    }

    default {
        Write-Host "Usage: .\scripts\dev.ps1 [command]" -ForegroundColor Yellow
        Write-Host "Commands:"
        Write-Host "  up       - Start Docker services (pgvector, neo4j, parser, ollama, prometheus, grafana)"
        Write-Host "  down     - Stop all Docker services"
        Write-Host "  restart  - Restart all Docker services"
        Write-Host "  status   - Show status of all containers"
        Write-Host "  health   - Query HTTP health probes across all endpoints"
        Write-Host "  test     - Run complete backend, sandbox, and frontend build verification"
    }
}
