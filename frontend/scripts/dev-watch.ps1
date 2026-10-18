# 共享开发服务器的守护脚本。
# Vite 在 Windows 上监视项目根目录时，遇到「写临时目录再改名」的原子替换会抛 EBUSY 并整体退出。
# 团队多人同时写文件时这不是偶发事件，因此用守护循环保证 5173 一直可用。

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Test-Path 'node_modules')) {
    Write-Host '缺少 node_modules，先执行 pnpm install'
    exit 1
}

$logDir = Join-Path $root '.artifacts'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$log = Join-Path $logDir 'dev-server.log'

while ($true) {
    $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    "[$stamp] 启动 vite" | Add-Content -Path $log -Encoding utf8
    & pnpm exec vite --host 127.0.0.1 --port 5173 --strictPort *>&1 |
        Tee-Object -FilePath $log -Append
    $code = $LASTEXITCODE
    $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    "[$stamp] vite 退出（exit=$code），2 秒后重启" | Add-Content -Path $log -Encoding utf8
    Start-Sleep -Seconds 2
}
