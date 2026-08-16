#Requires -Version 5.1
<#
.SYNOPSIS
    End-to-end load test runner for PR "stability & performance".

.DESCRIPTION
    1. Builds and starts the load-test stack (app + MySQL + OpenAI
       SSE mock + Prometheus) via loadtest/compose.loadtest.yaml.
    2. Runs the k6 scenarios (smoke, ramp, idempotency) inside the
       grafana/k6 image on the compose network.
    3. Collects k6 summary exports and Prometheus-derived P95/P99,
       Hikari pool and resilience counters.
    4. Prints a compact summary block that feeds
       docs/performance-report.md.

.PARAMETER SkipUp
    Reuse an already-running stack (skip compose up).

.PARAMETER Stages
    Ramp stages, e.g. "2,15s:8,30s:16,60s:32,120s:8,30s".

.PARAMETER KeepStack
    Do not tear the stack down after the run.
#>
param(
    [switch]$SkipUp,
    [string]$Stages = "2,15s:8,30s:16,60s:32,120s:8,30s",
    [int]$Replays = 20,
    [switch]$KeepStack
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $repoRoot "loadtest/compose.loadtest.yaml"
$resultsDir = Join-Path $repoRoot "loadtest/results"

New-Item -ItemType Directory -Force -Path $resultsDir | Out-Null

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"

function Invoke-Capture {
    param([string]$LogFile, [scriptblock]$Action)
    & $Action 2>&1 | Tee-Object -FilePath $LogFile
    if ($LASTEXITCODE -ne 0) {
        throw "command failed (exit $LASTEXITCODE); see $LogFile"
    }
}

if (-not $SkipUp) {
    Write-Host "[1/5] Building and starting the load-test stack..."
    Invoke-Capture "$resultsDir\compose-$timestamp.log" {
        docker compose -f $composeFile up -d --build --wait --wait-timeout 600
    }
}

Write-Host "[2/5] Waiting for app readiness..."
$deadline = (Get-Date).AddSeconds(120)
do {
    try {
        $health = Invoke-RestMethod `
            -Uri "http://127.0.0.1:18090/actuator/health" `
            -TimeoutSec 3
        if ($health.status -eq "UP") { break }
    }
    catch { Start-Sleep -Seconds 2 }
    Start-Sleep -Seconds 1
} while ((Get-Date) -lt $deadline)

if ($health.status -ne "UP") {
    throw "app did not become ready in time"
}
Write-Host "      readiness=UP"

$k6Common = @(
    "run",
    "--network", "nexus-loadtest-network",
    "--rm",
    "-v", "$repoRoot/loadtest/k6:/scripts:ro",
    "-v", "$resultsDir:/results",
    "-e", "BASE_URL=http://app:8080"
)

Write-Host "[3/5] k6 smoke..."
Invoke-Capture "$resultsDir\k6-smoke-$timestamp.log" {
    docker run @k6Common grafana/k6:0.54.0 `
        run /scripts/smoke-turn.js `
        --summary-export=/results/smoke-summary.json
}

Write-Host "[4/5] k6 ramp (stages: $Stages)..."
Invoke-Capture "$resultsDir\k6-ramp-$timestamp.log" {
    docker run @k6Common `
        -e "RAMP_STAGES=$Stages" `
        grafana/k6:0.54.0 `
        run /scripts/ramp-turns.js `
        --summary-export=/results/ramp-summary.json
}

Write-Host "[5/5] k6 idempotency (replays: $Replays)..."
Invoke-Capture "$resultsDir\k6-idem-$timestamp.log" {
    docker run @k6Common `
        -e "IDEMPOTENCY_REPLAYS=$Replays" `
        grafana/k6:0.54.0 `
        run /scripts/idempotency.js `
        --summary-export=/results/idempotency-summary.json
}

# Let Prometheus scrape the final samples before querying.
Start-Sleep -Seconds 15

Write-Host ""
Write-Host "=== Prometheus-derived results ==="

function Invoke-PromQuery {
    param([string]$Query)
    $encoded = [Uri]::EscapeDataString($Query)
    try {
        $res = Invoke-RestMethod `
            -Uri "http://127.0.0.1:19090/api/v1/query?query=$encoded" `
            -TimeoutSec 10
        return $res.data.result
    }
    catch {
        return @()
    }
}

function Show-PromValue {
    param([string]$Label, [string]$Query, [string]$Formatter)
    $result = Invoke-PromQuery -Query $Query
    foreach ($series in $result) {
        $value = [double]$series.value[1]
        $formatted = if ($Formatter -eq "ms") {
            [Math]::Round($value * 1000, 1)
        } else {
            [Math]::Round($value, 2)
        }
        Write-Host ("  {0} {1} = {2}" -f $Label, ($series.metric | ConvertTo-Json -Compress -Depth 2), $formatted)
    }
}

Show-PromValue "turn P95 (s)" 'histogram_quantile(0.95, sum by (le) (rate(nexus_conversation_turn_seconds_bucket[10m])))'
Show-PromValue "turn P99 (s)" 'histogram_quantile(0.99, sum by (le) (rate(nexus_conversation_turn_seconds_bucket[10m])))'
Show-PromValue "model P95 (s)" 'histogram_quantile(0.95, sum by (le) (rate(nexus_model_call_seconds_bucket[10m])))'
Show-PromValue "turn rate/s" 'sum(rate(nexus_conversation_turn_seconds_count[10m]))'
Show-PromValue "turn failure rate/s" 'sum(rate(nexus_conversation_turn_seconds_count{outcome!="COMPLETED_TEXT", outcome!="COMPLETED_TOOL"}[10m]))'
Show-PromValue "hikari max active" 'max(hikaricp_connections_active)'
Show-PromValue "hikari max pending" 'max(hikaricp_connections_pending)'
Show-PromValue "sse active (peak)" 'max(nexus_sse_connections_active)'
Show-PromValue "rate limited total" 'sum(nexus_sse_connections_rate_limited_total)'
Show-PromValue "model retries" 'sum by (outcome) (nexus_model_retry_total)'
Show-PromValue "circuit state" 'resilience4j_circuitbreaker_state{name=~"model:.*"}'

Write-Host ""
Write-Host "k6 summaries: $resultsDir"

if (-not $KeepStack) {
    Write-Host "Tearing the stack down (use -KeepStack to keep it)..."
    docker compose -f $composeFile down -v | Out-Null
}
