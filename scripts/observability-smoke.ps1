<#
.SYNOPSIS
    Nexus Agent 可观测性全链路冒烟验收脚本。

.DESCRIPTION
    依次执行：
      1. 校验 metrics 抓取密码文件存在、单行、无尾随换行且 >= 32 位；
      2. 启动 app/mysql/prometheus/grafana（observability profile）；
      3. 等待全部容器 healthcheck 通过；
      4. 未认证访问 /actuator/prometheus 必须 401；
      5. 正确机器凭据访问必须 200；
      6. 跑一次业务流量（租户引导 -> 登录 -> 建 Agent -> 开会话 -> SSE turn）
         生成 nexus_* 自定义指标（模型网关不可用时 turn 仍会以
         MODEL_FAILED 完成并记录指标）；
      7. 通过 Prometheus Query API 断言 up=1、turn 计数与 SSE 连接
         计数已入库；
      8. 断言 TSDB 不存在 tenantId/userId/conversationId 标签；
      9. 断言 Grafana /api/health 为 200。
    失败时输出容器状态与最近日志；finally 中按 -KeepEnvironment
    决定是否关闭环境（默认关闭，保留数据卷）。

.PARAMETER AppPort / PrometheusPort / GrafanaPort
    本地探测端口；缺省时依次读取环境变量 SERVER_PORT / PROMETHEUS_PORT /
    GRAFANA_PORT，最终回落到 compose 默认值 8080 / 9090 / 3000。

.PARAMETER HealthTimeoutSeconds
    等待健康检查的总超时（秒），默认 600。

.PARAMETER KeepEnvironment
    结束后不执行 docker compose down（保留现场排查）。

.PARAMETER ForceBuild
    强制重建 app 镜像（默认仅在镜像缺失时构建）。

.PARAMETER AdditionalComposeFiles
    追加的 compose override 文件（相对 deploy/ 目录解析），
    例如测试用数据卷覆盖，绝不改动 compose.yaml 本身。

.EXAMPLE
    pwsh scripts/observability-smoke.ps1

.EXAMPLE
    pwsh scripts/observability-smoke.ps1 -KeepEnvironment -ForceBuild
#>

[CmdletBinding()]
param(
    [int]$AppPort = 0,
    [int]$PrometheusPort = 0,
    [int]$GrafanaPort = 0,
    [int]$HealthTimeoutSeconds = 600,
    [switch]$KeepEnvironment,
    [switch]$ForceBuild,
    [string[]]$AdditionalComposeFiles = @()
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# 路径与常量
# ---------------------------------------------------------------------------

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$DeployDir = Join-Path $RepoRoot 'deploy'
$SecretFile = Join-Path $DeployDir 'secrets\metrics_scrape_password'
$GrafanaSecretFile = Join-Path $DeployDir 'secrets\grafana_admin_password'

$MIN_SECRET_LENGTH = 32

$ContainerNames = @(
    'nexus-agent-mysql',
    'nexus-agent-app',
    'nexus-agent-prometheus',
    'nexus-agent-grafana'
)

$ForbiddenLabelPattern = @(
    '(?i)tenant.?id',
    '(?i)user.?id',
    '(?i)conversation.?id',
    '(?i)message.?id',
    '(?i)ticket.?id',
    '(?i)request.?id',
    '(?i)trace.?id',
    '(?i)client.?ip',
    '(?i)ip.?address'
) -join '|'

$Script:ComposeFiles = @((Join-Path $DeployDir 'compose.yaml'))
foreach ($extra in $AdditionalComposeFiles) {
    $Script:ComposeFiles += (Resolve-Path (Join-Path $DeployDir $extra)).Path
}

# ---------------------------------------------------------------------------
# 通用工具
# ---------------------------------------------------------------------------

function Invoke-Compose {
    param([Parameter(Mandatory)][string[]]$ComposeArgs)

    $allArgs = @()
    foreach ($file in $Script:ComposeFiles) {
        $allArgs += '-f', $file
    }

    & docker compose @allArgs @ComposeArgs
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose $($ComposeArgs -join ' ') failed (exit $LASTEXITCODE)"
    }
}

function Write-Check {
    param([Parameter(Mandatory)][string]$Name)

    Write-Host "[CHECK] $Name" -ForegroundColor Cyan
}

function Write-Pass {
    param([Parameter(Mandatory)][string]$Name)

    Write-Host "[PASS]  $Name" -ForegroundColor Green
}

function Import-DeployDotEnv {
    $dotEnv = Join-Path $DeployDir '.env'

    if (-not (Test-Path $dotEnv)) {
        return
    }

    Get-Content $dotEnv | ForEach-Object {
        $line = $_.Trim()

        if ($line -and -not $line.StartsWith('#') -and
                $line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
            $name = $Matches[1]
            $value = $Matches[2].Trim('"').Trim("'")

            if ([string]::IsNullOrEmpty(
                    [Environment]::GetEnvironmentVariable(
                        $name,
                        'Process'
                    )
            )) {
                [Environment]::SetEnvironmentVariable(
                    $name,
                    $value,
                    'Process'
                )
            }
        }
    }
}

function Show-Diagnostics {
    Write-Host "`n===== DIAGNOSTICS: container status =====" -ForegroundColor Yellow

    try {
        & docker compose @($Script:ComposeFiles | ForEach-Object { '-f', $_ }) `
            --profile observability ps -a 2>&1 |
            Write-Host
    } catch {
        Write-Warning "compose ps failed: $($_.Exception.Message)"
    }

    foreach ($container in $ContainerNames) {
        Write-Host "`n===== DIAGNOSTICS: recent logs [$container] =====" -ForegroundColor Yellow

        try {
            & docker logs --tail 100 $container 2>&1 |
                Select-Object -Last 100 |
                ForEach-Object { Write-Host $_ }
        } catch {
            Write-Warning "logs for $container failed: $($_.Exception.Message)"
        }
    }
}

# ---------------------------------------------------------------------------
# 步骤 1：校验 metrics 抓取密码文件
# ---------------------------------------------------------------------------

function Assert-MetricsSecretFile {
    Write-Check 'metrics secret file'

    if (-not (Test-Path $SecretFile)) {
        throw @"
Metrics scrape secret file missing: $SecretFile
Create it with (no trailing newline, >= $MIN_SECRET_LENGTH chars):
    mkdir deploy/secrets
    printf '%s' "`$NEXUS_METRICS_PASSWORD" > deploy/secrets/metrics_scrape_password
"@
    }

    $raw = [System.IO.File]::ReadAllText($SecretFile)
    $value = $raw.TrimEnd("`r", "`n")

    if ($raw.Length -ne $value.Length) {
        throw 'metrics secret file must not end with a newline ' +
            '(a trailing newline becomes part of the password ' +
            'and breaks Basic auth against the app)'
    }

    if ($value.Length -gt 0 -and $value[0] -eq [char]0xFEFF) {
        Write-Warning 'metrics secret file starts with a UTF-8 BOM; stripping it'
        $value = $value.Substring(1)
    }

    if ($value -match "[\r\n]") {
        throw 'metrics secret file must contain a single line only'
    }

    if ($value.Length -lt $MIN_SECRET_LENGTH) {
        throw "metrics secret too short: $($value.Length) < $MIN_SECRET_LENGTH"
    }

    $envPassword = [Environment]::GetEnvironmentVariable(
        'NEXUS_METRICS_PASSWORD',
        'Process'
    )

    if (-not [string]::IsNullOrEmpty($envPassword) -and
            $envPassword -ne $value) {
        throw 'NEXUS_METRICS_PASSWORD does not match ' +
            "$SecretFile (they must be identical)"
    }

    $env:NEXUS_METRICS_PASSWORD = $value

    $envUsername = [Environment]::GetEnvironmentVariable(
        'NEXUS_METRICS_USERNAME',
        'Process'
    )

    if ([string]::IsNullOrWhiteSpace($envUsername)) {
        $envUsername = 'prometheus'
    }

    if ($envUsername -ne 'prometheus') {
        throw "NEXUS_METRICS_USERNAME must be 'prometheus' " +
            '(prometheus.yml hardcodes the username)'
    }

    $env:NEXUS_METRICS_USERNAME = 'prometheus'
    $env:NEXUS_METRICS_SCRAPE_ENABLED = 'true'

    Write-Pass 'metrics secret file (>= 32 chars, single line, no trailing newline)'
}

function Assert-GrafanaSecretFile {
    Write-Check 'Grafana admin secret file'

    if (-not (Test-Path $GrafanaSecretFile)) {
        throw @"
Grafana admin secret file missing: $GrafanaSecretFile
Create it with a strong single-line password (no trailing newline):
    printf '%s' "`$GRAFANA_ADMIN_PASSWORD" > deploy/secrets/grafana_admin_password
"@
    }

    $raw = [System.IO.File]::ReadAllText($GrafanaSecretFile)
    $value = $raw.TrimEnd("`r", "`n")

    if ($raw.Length -ne $value.Length) {
        throw 'Grafana admin secret file must not end with a newline'
    }

    if ($value.Length -gt 0 -and $value[0] -eq [char]0xFEFF) {
        throw 'Grafana admin secret file must not contain a UTF-8 BOM'
    }

    if ($value -match "[\r\n]") {
        throw 'Grafana admin secret file must contain one line only'
    }

    if ($value.Length -lt 16) {
        throw "Grafana admin password too short: $($value.Length) < 16"
    }

    Write-Pass 'Grafana admin secret file (>= 16 chars, single line)'
}

# ---------------------------------------------------------------------------
# 步骤 2-3：启动并等待健康检查
# ---------------------------------------------------------------------------

function Test-AppImageExists {
    & docker image inspect nexus-agent-app *> $null
    return ($LASTEXITCODE -eq 0)
}

function Start-ObservabilityStack {
    Write-Check 'start app/mysql/prometheus/grafana'

    $upArgs = @('--profile', 'observability', 'up', '-d')

    if ($ForceBuild -or -not (Test-AppImageExists)) {
        $upArgs += '--build'
    }

    Invoke-Compose $upArgs

    Write-Pass 'stack started'
}

function Wait-ForAllHealthy {
    Write-Check 'wait for all healthchecks'

    $deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)

    while ((Get-Date) -lt $deadline) {
        $statuses = @{}
        $allHealthy = $true

        foreach ($container in $ContainerNames) {
            $state = (& docker inspect `
                    --format '{{.State.Status}}' $container 2>$null) -join ''

            if ($state -in @('exited', 'dead')) {
                throw "container $container exited before becoming healthy; " +
                    'see diagnostics below'
            }

            $health = (& docker inspect `
                    --format '{{.State.Health.Status}}' $container 2>$null) -join ''

            $statuses[$container] = if ($health) { $health } else { $state }

            if ($health -ne 'healthy') {
                $allHealthy = $false
            }
        }

        if ($allHealthy) {
            foreach ($container in $ContainerNames) {
                Write-Host "  $container : $($statuses[$container])"
            }

            Write-Pass 'all containers healthy'
            return
        }

        $lines = ($statuses.GetEnumerator() |
                ForEach-Object { "  $($_.Key) : $($_.Value)" }) -join "`n"

        Write-Host "waiting for health...`n$lines"
        Start-Sleep -Seconds 5
    }

    throw "healthchecks did not pass within ${HealthTimeoutSeconds}s"
}

# ---------------------------------------------------------------------------
# 步骤 4-5：metrics 端点认证矩阵
# ---------------------------------------------------------------------------

function Get-PrometheusAuthHeader {
    $pair = "prometheus`:$env:NEXUS_METRICS_PASSWORD"
    $encoded = [Convert]::ToBase64String(
        [Text.Encoding]::UTF8.GetBytes($pair)
    )

    return "Basic $encoded"
}

function Assert-MetricsEndpointAuth {
    Write-Check 'metrics endpoint auth matrix'

    $uri = "http://127.0.0.1:$AppPort/actuator/prometheus"

    $unauthorized = Invoke-WebRequest `
        -Uri $uri -SkipHttpErrorCheck -TimeoutSec 30

    if ($unauthorized.StatusCode -ne 401) {
        throw "expected 401 without credentials, " +
            "got $($unauthorized.StatusCode)"
    }

    Write-Pass 'unauthenticated metrics -> 401'

    $authorized = Invoke-WebRequest `
        -Uri $uri `
        -Headers @{ Authorization = (Get-PrometheusAuthHeader) } `
        -SkipHttpErrorCheck -TimeoutSec 30

    if ($authorized.StatusCode -ne 200) {
        throw "expected 200 with machine credentials, " +
            "got $($authorized.StatusCode)"
    }

    $rawContent = $authorized.Content

    if ($rawContent -is [byte[]]) {
        $body = [Text.Encoding]::UTF8.GetString($rawContent)
    } else {
        $body = [string]$rawContent
    }

    if ($body -notmatch 'nexus_') {
        throw 'metrics body does not contain any nexus_ metric'
    }

    Write-Pass 'machine credentials -> 200 (body contains nexus_ metrics)'
}

# ---------------------------------------------------------------------------
# 步骤 6：业务流量
# ---------------------------------------------------------------------------

function Invoke-BusinessTraffic {
    Write-Check 'run business traffic to generate nexus_* metrics'

    $appBase = "http://127.0.0.1:$AppPort"
    $suffix = [DateTimeOffset]::Now.ToUnixTimeSeconds()
    $tenantCode = "smoke$suffix"
    $adminPassword = 'SmokePassword123!'
    $bearer = @{ Authorization = '' }

    # 租户引导。
    $bootstrap = Invoke-RestMethod `
        -Uri "$appBase/api/v1/tenants/bootstrap" `
        -Method Post `
        -ContentType 'application/json' `
        -Body (@{
            tenantCode    = $tenantCode
            tenantName    = 'Smoke Tenant'
            adminUsername = 'smokeadmin'
            adminEmail    = 'smoke-admin@example.com'
            adminPassword = $adminPassword
        } | ConvertTo-Json -Compress) `
        -TimeoutSec 30

    Write-Host "  tenant bootstrapped: $tenantCode"

    # 登录拿 ADMIN token。
    $login = Invoke-RestMethod `
        -Uri "$appBase/api/v1/auth/login" `
        -Method Post `
        -ContentType 'application/json' `
        -Body (@{
            tenantCode = $tenantCode
            username   = 'smokeadmin'
            password   = $adminPassword
        } | ConvertTo-Json -Compress) `
        -TimeoutSec 30

    if ([string]::IsNullOrEmpty($login.accessToken)) {
        throw 'login did not return an access token'
    }

    $bearer.Authorization = "Bearer $($login.accessToken)"

    # 建 Agent（ADMIN 权限）。新 Agent 初始为 DRAFT，
    # 开会话要求 ACTIVE，因此创建后立即激活。
    $agent = Invoke-RestMethod `
        -Uri "$appBase/api/v1/agents" `
        -Method Post `
        -Headers $bearer `
        -ContentType 'application/json' `
        -Body (@{
            code         = "smoke-agent-$suffix"
            name         = 'Smoke Agent'
            systemPrompt = 'You are a smoke test agent.'
            modelProvider = 'OPENAI'
            modelName    = 'gpt-5-mini'
            modelConfig  = $null
        } | ConvertTo-Json -Compress) `
        -TimeoutSec 30

    Invoke-RestMethod `
        -Uri "$appBase/api/v1/agents/smoke-agent-$suffix/status" `
        -Method Patch `
        -Headers $bearer `
        -ContentType 'application/json' `
        -Body (@{
            targetStatus    = 'ACTIVE'
            expectedVersion = $agent.version
        } | ConvertTo-Json -Compress) `
        -TimeoutSec 30 | Out-Null

    # 开会话并追加消息。
    $conversation = Invoke-RestMethod `
        -Uri "$appBase/api/v1/conversations" `
        -Method Post `
        -Headers $bearer `
        -ContentType 'application/json' `
        -Body (@{
            agentCode      = "smoke-agent-$suffix"
            title          = 'Smoke conversation'
            initialMessage = 'Smoke hello'
        } | ConvertTo-Json -Compress) `
        -TimeoutSec 30

    $conversationId = $conversation.conversationId

    Invoke-RestMethod `
        -Uri "$appBase/api/v1/conversations/$conversationId/messages" `
        -Method Post `
        -Headers $bearer `
        -ContentType 'application/json' `
        -Body (@{ content = 'Smoke follow-up' } | ConvertTo-Json -Compress) `
        -TimeoutSec 30 | Out-Null

    # SSE turn：即使模型网关不可用，turn 也会以 MODEL_FAILED 结束
    # 并记录 nexus_conversation_turn_seconds_count。
    $sse = Invoke-WebRequest `
        -Uri "$appBase/api/v1/conversations/$conversationId/turns:stream" `
        -Method Post `
        -Headers $bearer `
        -ContentType 'application/json' `
        -Body (@{ content = 'Smoke turn question' } | ConvertTo-Json -Compress) `
        -TimeoutSec 240 -SkipHttpErrorCheck

    if ($sse.StatusCode -ne 200) {
        throw "SSE turn returned $($sse.StatusCode)"
    }

    Write-Host "  conversation=$conversationId SSE turn finished"

    Write-Pass 'business traffic completed'
}

# ---------------------------------------------------------------------------
# 步骤 7-8：Prometheus Query API 断言
# ---------------------------------------------------------------------------

function Invoke-PromQuery {
    param([Parameter(Mandatory)][string]$Query)

    $uri = "http://127.0.0.1:$PrometheusPort/api/v1/query?query=" +
        [uri]::EscapeDataString($Query)

    $response = Invoke-RestMethod -Uri $uri -TimeoutSec 30

    if ($response.status -ne 'success') {
        throw "Prometheus query failed: $Query"
    }

    return @($response.data.result)
}

function Wait-ForPrometheusCounter {
    param(
        [Parameter(Mandatory)][string]$Query,
        [Parameter(Mandatory)][string]$Label,
        [int]$TimeoutSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)

    while ((Get-Date) -lt $deadline) {
        try {
            $results = Invoke-PromQuery $Query

            if ($results.Count -gt 0) {
                $value = [double]$results[0].value[1]

                if ($value -gt 0) {
                    Write-Host "  $Label = $value"
                    return $value
                }
            }
        } catch {
            # 指标尚未入库，继续轮询。
        }

        Start-Sleep -Seconds 5
    }

    throw "$Label did not appear in Prometheus within ${TimeoutSeconds}s"
}

function Assert-PrometheusMetrics {
    Write-Check 'Prometheus Query API'

    $up = Invoke-PromQuery 'up{job="nexus-agent"}'

    if ($up.Count -ne 1 -or [double]$up[0].value[1] -ne 1) {
        throw "expected up{job='nexus-agent'} == 1"
    }

    Write-Pass 'up{job="nexus-agent"} == 1'

    Wait-ForPrometheusCounter `
        -Query 'sum(nexus_conversation_turn_seconds_count)' `
        -Label 'nexus_conversation_turn_seconds_count' |
        Out-Null

    Write-Pass 'turn timer recorded (any outcome)'

    Wait-ForPrometheusCounter `
        -Query 'sum(nexus_sse_connections_established_total)' `
        -Label 'nexus_sse_connections_established_total' |
        Out-Null

    Write-Pass 'SSE established counter recorded'

    # Dashboard 与 recording rules 使用 histogram_quantile；必须真实
    # 看到 *_bucket，而不只是 Timer 的 count/sum。
    Wait-ForPrometheusCounter `
        -Query 'count(http_server_requests_seconds_bucket{job="nexus-agent"})' `
        -Label 'HTTP histogram bucket series' |
        Out-Null

    Wait-ForPrometheusCounter `
        -Query 'count(nexus_conversation_turn_seconds_bucket)' `
        -Label 'turn histogram bucket series' |
        Out-Null

    Wait-ForPrometheusCounter `
        -Query 'count(nexus_model_call_seconds_bucket)' `
        -Label 'model histogram bucket series' |
        Out-Null

    Write-Pass 'all histogram bucket families are queryable'

    # Prometheus 每 15 秒计算 recording rules。等待真实业务流量进入
    # 至少两个 scrape sample 后，P95 规则必须产出数值。
    Wait-ForPrometheusCounter `
        -Query 'nexus:http_p95_seconds:5m' `
        -Label 'nexus:http_p95_seconds:5m' `
        -TimeoutSeconds 180 |
        Out-Null

    Wait-ForPrometheusCounter `
        -Query 'nexus:turn_p95_seconds:5m' `
        -Label 'nexus:turn_p95_seconds:5m' `
        -TimeoutSeconds 180 |
        Out-Null

    Wait-ForPrometheusCounter `
        -Query 'nexus:model_p95_seconds:5m' `
        -Label 'nexus:model_p95_seconds:5m' `
        -TimeoutSeconds 180 |
        Out-Null

    Write-Pass 'recorded P95 series are queryable'
}

function Assert-NoForbiddenLabels {
    Write-Check 'no request/resource identity labels in TSDB'

    $uri = "http://127.0.0.1:$PrometheusPort/api/v1/labels"
    $response = Invoke-RestMethod -Uri $uri -TimeoutSec 30

    if ($response.status -ne 'success') {
        throw 'Prometheus /api/v1/labels failed'
    }

    $forbidden = @(
        $response.data |
            Where-Object { $_ -match $ForbiddenLabelPattern }
    )

    if ($forbidden.Count -gt 0) {
        throw 'forbidden high-cardinality labels found in TSDB: ' +
            ($forbidden -join ', ')
    }

    Write-Pass 'label names contain no high-cardinality identity fields'
}

# ---------------------------------------------------------------------------
# 步骤 9：Grafana 健康
# ---------------------------------------------------------------------------

function Assert-GrafanaHealthy {
    Write-Check 'Grafana /api/health'

    $uri = "http://127.0.0.1:$GrafanaPort/api/health"
    $response = Invoke-WebRequest `
        -Uri $uri -SkipHttpErrorCheck -TimeoutSec 30

    if ($response.StatusCode -ne 200) {
        throw "Grafana /api/health returned $($response.StatusCode)"
    }

    Write-Pass 'Grafana /api/health -> 200'
}

# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

# 端口解析：参数 > 环境变量 > compose 默认值。
$AppPort = if ($AppPort -gt 0) {
    $AppPort
} elseif ($env:SERVER_PORT) {
    [int]$env:SERVER_PORT
} else {
    8080
}

$PrometheusPort = if ($PrometheusPort -gt 0) {
    $PrometheusPort
} elseif ($env:PROMETHEUS_PORT) {
    [int]$env:PROMETHEUS_PORT
} else {
    9090
}

$GrafanaPort = if ($GrafanaPort -gt 0) {
    $GrafanaPort
} elseif ($env:GRAFANA_PORT) {
    [int]$env:GRAFANA_PORT
} else {
    3000
}

Write-Host ''
Write-Host '===== Nexus Agent Observability Smoke =====' -ForegroundColor Magenta
Write-Host "  app       http://127.0.0.1:$AppPort"
Write-Host "  prometheus http://127.0.0.1:$PrometheusPort"
Write-Host "  grafana   http://127.0.0.1:$GrafanaPort"
Write-Host ''

try {
    Import-DeployDotEnv

    $required = @(
        'MYSQL_DATABASE',
        'MYSQL_USER',
        'MYSQL_PASSWORD',
        'MYSQL_ROOT_PASSWORD',
        'NEXUS_JWT_SECRET'
    )

    $missing = @(
        $required | Where-Object {
            [string]::IsNullOrWhiteSpace(
                [Environment]::GetEnvironmentVariable(
                    $_,
                    'Process'
                )
            )
        }
    )

    if ($missing.Count -gt 0) {
        throw 'required environment variables missing: ' +
            ($missing -join ', ') +
            ' (set them in the shell or in deploy/.env)'
    }

    Assert-MetricsSecretFile
    Assert-GrafanaSecretFile
    Start-ObservabilityStack
    Wait-ForAllHealthy
    Assert-MetricsEndpointAuth
    Invoke-BusinessTraffic
    Assert-PrometheusMetrics
    Assert-NoForbiddenLabels
    Assert-GrafanaHealthy

    Write-Host ''
    Write-Host '===== SMOKE PASSED =====' -ForegroundColor Green
} catch {
    Write-Host ''
    Write-Error "SMOKE FAILED: $($_.Exception.Message)"
    Show-Diagnostics
    throw
} finally {
    Write-Host ''

    if (-not $KeepEnvironment) {
        Write-Host '[TEARDOWN] docker compose down (volumes preserved)'
        try {
            Invoke-Compose @('--profile', 'observability', 'down')
            Write-Host '[TEARDOWN] environment stopped'
        } catch {
            Write-Warning "teardown failed: $($_.Exception.Message)"
        }
    } else {
        Write-Host '[TEARDOWN] skipped (-KeepEnvironment): environment left running'
    }
}
