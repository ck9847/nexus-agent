#Requires -Version 5.1
<#
.SYNOPSIS
    Deploy (or update) the public demo environment on the user's server.

.DESCRIPTION
    SSH 到演示服务器，拉取目标镜像并滚动更新 compose.demo 栈，
    然后做健康检查 smoke。服务器侧的前置准备（Docker 安装、.env）
    见 deploy/demo/README.md，本脚本只做部署动作。

.PARAMETER SshHost
    目标服务器（user@host 形式）。

.PARAMETER RemoteDir
    服务器上 deploy/demo 的目录路径。

.PARAMETER Image
    要部署的镜像引用（默认 ghcr.io/ck9847/nexus-agent:0.1.0）。

.PARAMETER Domain
    可选：公网域名（用于 smoke 检查 URL）。

.EXAMPLE
    ./scripts/deploy-demo.ps1 -SshHost root@demo.example.com `
        -RemoteDir /opt/nexus-agent/deploy/demo `
        -Domain demo.example.com
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$SshHost,

    [Parameter(Mandatory = $true)]
    [string]$RemoteDir,

    [string]$Image = "ghcr.io/ck9847/nexus-agent:0.1.0",

    [string]$Domain
)

$ErrorActionPreference = "Stop"

Write-Host "[1/3] Pulling $Image on $SshHost..."
ssh $SshHost "cd $RemoteDir && DEMO_IMAGE=$Image docker compose pull app"

if ($LASTEXITCODE -ne 0) {
    throw "image pull failed"
}

Write-Host "[2/3] Recreating the demo stack..."
ssh $SshHost "cd $RemoteDir && DEMO_IMAGE=$Image docker compose up -d --wait --wait-timeout 300"

if ($LASTEXITCODE -ne 0) {
    throw "stack update failed"
}

$baseUrl = if ($Domain) { "https://$Domain" } else {
    # 无域名时从 SSH 主机推断 HTTP 端点。
    $hostOnly = $SshHost.Split('@')[-1]
    "http://${hostOnly}:8080"
}

Write-Host "[3/3] Smoke check via $baseUrl/actuator/health ..."
$deadline = (Get-Date).AddSeconds(90)
do {
    try {
        $health = Invoke-RestMethod -Uri "$baseUrl/actuator/health" -TimeoutSec 5
        if ($health.status -eq "UP") {
            Write-Host "      demo is UP: $baseUrl"
            exit 0
        }
    }
    catch { Start-Sleep -Seconds 3 }
} while ((Get-Date) -lt $deadline)

throw "demo did not become healthy in time"
