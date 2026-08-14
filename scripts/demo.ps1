[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080",

    [string]$AgentCode = "support-agent",

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$ModelName,

    [switch]$SkipStream
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$utf8NoBom = [Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $utf8NoBom
$OutputEncoding = $utf8NoBom

function Assert-Command {
    param([Parameter(Mandatory = $true)][string]$Name)

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found."
    }
}

function Invoke-NexusJson {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [AllowNull()][object]$Body,
        [AllowNull()][string]$Token
    )

    $headers = @{ Accept = "application/json" }
    if (-not [string]::IsNullOrWhiteSpace($Token)) {
        $headers.Authorization = "Bearer $Token"
    }

    $request = @{
        Method      = $Method
        Uri         = "$BaseUrl$Path"
        Headers     = $headers
        ErrorAction = "Stop"
    }

    if ($null -ne $Body) {
        $request.ContentType = "application/json"
        $request.Body = $Body | ConvertTo-Json -Depth 20 -Compress
    }

    Invoke-RestMethod @request
}

function Wait-NexusReadiness {
    param([int]$TimeoutSeconds = 120)

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        try {
            $health = Invoke-RestMethod `
                -Uri "$BaseUrl/actuator/health/readiness" `
                -Method Get `
                -TimeoutSec 5 `
                -ErrorAction Stop

            if ($health.status -eq "UP") {
                return
            }
        }
        catch {
            # The container may still be starting.
        }

        Start-Sleep -Seconds 2
    } while ([DateTimeOffset]::UtcNow -lt $deadline)

    throw "NexusAgent readiness did not become UP within $TimeoutSeconds seconds."
}

Assert-Command -Name "docker"
if (-not $SkipStream) {
    Assert-Command -Name "curl.exe"
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$composeFile = Join-Path $repoRoot "deploy/compose.yaml"
$envFile = Join-Path $repoRoot ".env"

if (-not (Test-Path -LiteralPath $envFile)) {
    throw ".env was not found. Copy .env.example to .env and configure it first."
}

$BaseUrl = $BaseUrl.TrimEnd("/")
$suffix = "{0}-{1}" -f `
    [DateTimeOffset]::UtcNow.ToUnixTimeSeconds(), `
    ([Guid]::NewGuid().ToString("N").Substring(0, 6).ToLowerInvariant())
$tenantCode = "nexus-demo-$suffix"
$adminUsername = "demo-admin"
$adminEmail = "$tenantCode@example.com"
$adminPassword = "NexusDemo!" + [Guid]::NewGuid().ToString("N").Substring(0, 24)

Push-Location $repoRoot
try {
    Write-Host "[1/8] Starting MySQL and NexusAgent containers..."
    & docker compose `
        --env-file $envFile `
        -f $composeFile `
        up -d --build --wait --wait-timeout 240

    if ($LASTEXITCODE -ne 0) {
        throw "docker compose up failed with exit code $LASTEXITCODE."
    }

    Wait-NexusReadiness
    Write-Host "      readiness=UP"

    Write-Host "[2/8] Bootstrapping an isolated demo tenant..."
    $bootstrap = Invoke-NexusJson `
        -Method "Post" `
        -Path "/api/v1/tenants/bootstrap" `
        -Body @{
            tenantCode    = $tenantCode
            tenantName    = "NexusAgent Demo"
            adminUsername = $adminUsername
            adminEmail    = $adminEmail
            adminPassword = $adminPassword
        } `
        -Token $null

    Write-Host "      tenantId=$($bootstrap.tenantId) adminUserId=$($bootstrap.adminUserId)"

    Write-Host "[3/8] Logging in (the JWT is kept in memory and is not printed)..."
    $login = Invoke-NexusJson `
        -Method "Post" `
        -Path "/api/v1/auth/login" `
        -Body @{
            tenantCode = $tenantCode
            username   = $adminUsername
            password   = $adminPassword
        } `
        -Token $null

    $accessToken = [string]$login.accessToken
    if ([string]::IsNullOrWhiteSpace($accessToken)) {
        throw "Login response did not contain an access token."
    }

    Write-Host "[4/8] Creating and activating Agent '$AgentCode'..."
    $agent = Invoke-NexusJson `
        -Method "Post" `
        -Path "/api/v1/agents" `
        -Body @{
            code          = $AgentCode
            name          = "Enterprise Support Agent"
            description   = "Creates auditable tickets from production incidents."
            systemPrompt  = "You are an enterprise support agent. When a user reports an incident and asks for a ticket, you MUST call create_ticket exactly once using the supplied facts. Set the requested priority exactly. Never invent a ticket number. After the tool succeeds, answer with the returned ticket number."
            modelProvider = "OPENAI"
            modelName     = $ModelName
            modelConfig   = @{
                temperature    = 0.2
                maxOutputTokens = 1024
            }
        } `
        -Token $accessToken

    if ($agent.status -ne "DRAFT" -or $agent.version -ne 0) {
        throw "New Agent did not start in DRAFT version 0."
    }

    $activeAgent = Invoke-NexusJson `
        -Method "Patch" `
        -Path "/api/v1/agents/$AgentCode/status" `
        -Body @{
            targetStatus   = "ACTIVE"
            expectedVersion = 0
        } `
        -Token $accessToken

    Write-Host "      agentId=$($agent.agentId) status=$($activeAgent.currentStatus) version=$($activeAgent.version)"

    Write-Host "[5/8] Creating a Conversation and initial USER message..."
    $conversation = Invoke-NexusJson `
        -Method "Post" `
        -Path "/api/v1/conversations" `
        -Body @{
            agentCode     = $AgentCode
            title         = "Production connectivity incident"
            initialMessage = "I need help with a production connectivity incident."
        } `
        -Token $accessToken

    $conversationId = [string]$conversation.conversationId
    if ([string]::IsNullOrWhiteSpace($conversationId)) {
        throw "Conversation response did not contain conversationId."
    }
    Write-Host "      conversationId=$conversationId initialSequence=$($conversation.initialMessage.sequenceNo)"

    if ($SkipStream) {
        Write-Host "[6/8] Skipping external model/SSE call (-SkipStream)."
    }
    else {
        Write-Host "[6/8] Streaming the Agent turn. SSE frames follow:"
        $turnJson = @{
            content = "服务器无法连接，请创建一个高优先级工单。标题使用 Production server unreachable，并在描述中保留 connectivity timeout。"
        } | ConvertTo-Json -Compress

        $turnFile = [IO.Path]::GetTempFileName()
        try {
            # Passing JSON directly to native commands is unreliable in
            # Windows PowerShell 5.1. A UTF-8 file preserves quotes and CJK.
            [IO.File]::WriteAllText(
                $turnFile,
                $turnJson,
                $utf8NoBom
            )

            & curl.exe `
                -N `
                --silent `
                --show-error `
                --fail-with-body `
                -X POST `
                "$BaseUrl/api/v1/conversations/$conversationId/turns:stream" `
                -H "Authorization: Bearer $accessToken" `
                -H "Content-Type: application/json" `
                -H "Accept: text/event-stream" `
                --data-binary "@$turnFile"

            if ($LASTEXITCODE -ne 0) {
                throw "SSE request failed with exit code $LASTEXITCODE."
            }
        }
        finally {
            Remove-Item -LiteralPath $turnFile -Force `
                -ErrorAction SilentlyContinue
        }
        Write-Host ""
    }

    Write-Host "[7/8] Reading persisted message history..."
    $messages = Invoke-NexusJson `
        -Method "Get" `
        -Path "/api/v1/conversations/$conversationId/messages?limit=50" `
        -Body $null `
        -Token $accessToken

    $messages.items |
        Select-Object sequenceNo, role, status, content |
        Format-Table -AutoSize

    Write-Host "[8/8] Reading HIGH-priority tickets created in this tenant..."
    $tickets = Invoke-NexusJson `
        -Method "Get" `
        -Path "/api/v1/tickets?priority=HIGH&limit=20" `
        -Body $null `
        -Token $accessToken

    if ($tickets.items.Count -eq 0) {
        if ($SkipStream) {
            Write-Host "      No ticket expected because the model turn was skipped."
        }
        else {
            Write-Warning "No HIGH ticket was found. Inspect the SSE error/model response and app logs."
        }
    }
    else {
        $tickets.items |
            Select-Object ticketNo, title, priority, status, source |
            Format-Table -AutoSize
    }

    Write-Host "Demo completed. Full JWT, password and API key were not printed."
}
finally {
    Pop-Location
}
