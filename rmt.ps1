param(
    [ValidateSet("build", "images", "infra", "dev", "all", "help")]
    [string]$Command = "all"
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

function Show-Usage {
    Write-Host @"
Usage: .\rmt.ps1 [command]

Commands:
  build     Build all Maven modules
  images    Build Docker images for all services
  infra     Start infrastructure (LocalStack + Redis) and provision AWS resources via Terraform
  dev       build + infra  (run services locally with mvn spring-boot:run)
  all       build + images + infra  (full Docker environment)  [default]

Examples:
  .\rmt.ps1           # same as .\rmt.ps1 all
  .\rmt.ps1 dev       # build and start infra, then run services manually
  .\rmt.ps1 build     # only compile and install Maven modules
"@
}

function Resolve-AiPython {
    $serviceDir = Join-Path $ScriptDir "rmt-ai-module\rmt-ai-service"
    $py312 = Join-Path $serviceDir ".venv-py312\Scripts\python.exe"
    $venv = Join-Path $serviceDir ".venv\Scripts\python.exe"

    if (Test-Path $py312) {
        return $py312
    }

    if (Test-Path $venv) {
        return $venv
    }

    $pyLauncher = Get-Command py -ErrorAction SilentlyContinue
    if ($pyLauncher) {
        return "py -3"
    }

    return "python"
}

function Show-DevCommands {
    $aiPython = Resolve-AiPython
    $shadowExportPath = Join-Path $ScriptDir "detection-and-refactoring\target\manual-shadow.jsonl"
    $serviceDir = Join-Path $ScriptDir "rmt-ai-module\rmt-ai-service"

    Write-Host "Infrastructure is up. Start each service with:"
    Write-Host "  mvn spring-boot:run -pl project-sync-bff"
    Write-Host "  `$env:RMT_AI_ENABLED=`"true`"; `$env:RMT_AI_SHADOW_EXPORT_PATH=`"$shadowExportPath`"; mvn spring-boot:run -pl detection-and-refactoring"
    Write-Host "  mvn spring-boot:run -pl metrics-calculator"
    Write-Host "  Set-Location `"$serviceDir`"; $aiPython -m uvicorn app.main:app --host 0.0.0.0 --port 8000"
    Write-Host ""
    Write-Host "AI service env is taken from your current shell. Set RMT_AI_BACKEND_MODE, RMT_AI_DEVICE_PREFERENCE,"
    Write-Host "RMT_AI_FINETUNED_ARTIFACT_PATH, RMT_AI_EXPERIMENT_PROFILE, and RMT_AI_READ_TIMEOUT before starting"
    Write-Host "the services when you want a non-default backend or slower real-model inference."
}

function Invoke-Build {
    Write-Host "==> Building all modules..."
    mvn clean install -f "$ScriptDir\pom.xml"
}

function Invoke-Images {
    Write-Host "==> Building Docker images..."
    docker build -t magnus/detection "$ScriptDir\detection-and-refactoring"
    docker build -t magnus/manager   "$ScriptDir\project-sync-bff"
    docker build -t magnus/metrics   "$ScriptDir\metrics-calculator"
    docker build -t magnus/rmt-ai-service "$ScriptDir\rmt-ai-module\rmt-ai-service"
}

function Invoke-Infra {
    Write-Host "==> Starting infrastructure..."
    docker compose -f "$ScriptDir\infra\local\docker-compose.yml" up -d

    Write-Host "==> Provisioning AWS resources..."
    tflocal -chdir="$ScriptDir\infra" apply -auto-approve
}

function Invoke-InfraFull {
    Write-Host "==> Starting full environment..."
    docker compose -f "$ScriptDir\infra\local\docker-compose-full.yml" up -d

    Write-Host "==> Provisioning AWS resources..."
    tflocal -chdir="$ScriptDir\infra" apply -auto-approve
}

function Verify-FullStack {
    $composeFile = Join-Path $ScriptDir "infra\local\docker-compose-full.yml"

    Write-Host "==> Verifying application containers..."
    Start-Sleep -Seconds 5

    $exitedServices = docker compose -f "$composeFile" ps --status exited --services 2>$null
    if ($LASTEXITCODE -ne 0) {
        $exitedServices = ""
    }

    if (-not [string]::IsNullOrWhiteSpace($exitedServices)) {
        Write-Host "One or more services exited during startup:"
        Write-Host $exitedServices
        Write-Host ""
        Write-Host "Recent logs:"
        foreach ($service in ($exitedServices -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })) {
            docker compose -f "$composeFile" logs --tail=100 $service
        }
        throw "Full stack verification failed."
    }

    $runningServices = docker compose -f "$composeFile" ps --status running --services
    if ($LASTEXITCODE -ne 0 -or -not ($runningServices -split "`r?`n" | Where-Object { $_ -eq "intermediary" })) {
        Write-Host "The intermediary service is not running, so the UI is not available on http://localhost:8080"
        docker compose -f "$composeFile" ps
        throw "Full stack verification failed."
    }
}

switch ($Command) {
    "build" {
        Invoke-Build
    }
    "images" {
        Invoke-Images
    }
    "infra" {
        Invoke-Infra
    }
    "dev" {
        Invoke-Build
        Invoke-Infra
        Write-Host ""
        Show-DevCommands
    }
    "all" {
        Invoke-Build
        Invoke-Images
        Invoke-InfraFull
        Verify-FullStack
        Write-Host ""
        Write-Host "Done. UI available at http://localhost:8080"
    }
    "help" {
        Show-Usage
    }
}
