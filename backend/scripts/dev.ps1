# RAG-Wiki 本地中间件管理脚本（PowerShell）
# 用法：powershell -ExecutionPolicy Bypass -File scripts/dev.ps1 <命令>，命令列表见 help
# 与 scripts/dev.sh 功能对齐
# 注意：本文件必须带 UTF-8 BOM，否则 Windows PowerShell 5.1 会按 ANSI 读取导致中文乱码报错
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Command = 'help'
)

# 原生命令（docker / mysql）往 stderr 写内容时，Stop 模式会把它当成终止性错误，
# 这里保持 Continue，改为显式检查 $LASTEXITCODE
$ErrorActionPreference = 'Continue'
$Root = Split-Path -Parent $PSScriptRoot
$ComposeFile = Join-Path $Root 'docker-compose.yml'

# 从 Git Bash 等外部终端调用时，PowerShell 默认按控制台代码页输出，中文会乱码
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

function Import-DotEnv {
    param([string]$Path)
    if (-not (Test-Path $Path)) { return }
    Get-Content $Path -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if ($line -eq '' -or $line.StartsWith('#')) { return }
        $idx = $line.IndexOf('=')
        if ($idx -lt 1) { return }
        $key = $line.Substring(0, $idx).Trim()
        $value = $line.Substring($idx + 1).Trim()
        if ($value.Length -ge 2 -and $value.StartsWith('"') -and $value.EndsWith('"')) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        Set-Item -Path "Env:$key" -Value $value
    }
}

# 载入 .env，保证探活用的账号密码与容器启动时一致
Import-DotEnv (Join-Path $Root '.env')

function Get-EnvOrDefault {
    param([string]$Name, [string]$Default)
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrEmpty($value)) { return $Default }
    return $value
}

$MysqlContainer = Get-EnvOrDefault 'MYSQL_CONTAINER' 'rag-wiki-mysql'
$RedisContainer = Get-EnvOrDefault 'REDIS_CONTAINER' 'rag-wiki-redis'
$EsContainer = Get-EnvOrDefault 'ES_CONTAINER' 'rag-wiki-es'
$MysqlDatabase = Get-EnvOrDefault 'MYSQL_DATABASE' 'rag_wiki'
$MysqlUser = Get-EnvOrDefault 'MYSQL_USER' 'rag_wiki'
$MysqlPassword = Get-EnvOrDefault 'MYSQL_PASSWORD' 'rag_wiki'
$MysqlRootPassword = Get-EnvOrDefault 'MYSQL_ROOT_PASSWORD' 'root123456'
$RedisPassword = Get-EnvOrDefault 'REDIS_PASSWORD' 'redis123456'
$EsUris = Get-EnvOrDefault 'ES_URIS' 'http://localhost:19200'
$HealthTimeout = [int](Get-EnvOrDefault 'HEALTH_TIMEOUT' '240')
$HealthInterval = 5

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$ComposeArgs)
    & docker compose -f $ComposeFile @ComposeArgs
}

function Get-ContainerHealth {
    param([string]$Name)
    $state = & docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $Name 2>$null
    if ([string]::IsNullOrEmpty($state)) { return 'missing' }
    return $state.Trim()
}

function Wait-Healthy {
    $elapsed = 0
    $detail = ''
    while ($elapsed -lt $HealthTimeout) {
        $allOk = $true
        $detail = ''
        foreach ($name in @($MysqlContainer, $RedisContainer, $EsContainer)) {
            $state = Get-ContainerHealth $name
            $detail += "  $name=$state"
            if ($state -ne 'healthy') { $allOk = $false }
        }
        if ($allOk) {
            Write-Host "全部 healthy（约 ${elapsed}s）："
            Write-Host $detail
            return $true
        }
        Start-Sleep -Seconds $HealthInterval
        $elapsed += $HealthInterval
    }
    Write-Host "等待超时（${HealthTimeout}s），当前状态：$detail" -ForegroundColor Red
    Write-Host '排查：powershell -File scripts/dev.ps1 logs' -ForegroundColor Red
    return $false
}

function Invoke-Up {
    Write-Host '启动中间件 ...'
    Invoke-Compose up -d
    if (-not (Wait-Healthy)) { exit 1 }
}

function Invoke-Down { Invoke-Compose down }

function Invoke-Restart {
    Invoke-Compose restart
    if (-not (Wait-Healthy)) { exit 1 }
}

function Invoke-Status { Invoke-Compose ps }

function Invoke-Logs { Invoke-Compose logs -f --tail=100 }

# 密码走 MYSQL_PWD 环境变量而不是命令行，避免 mysqladmin 的安全警告污染输出
function Get-MySqlRootScalar {
    param([string]$Sql, [string]$Database)
    $DockerArgs = @('exec', '-e', "MYSQL_PWD=$MysqlRootPassword", $MysqlContainer, 'mysql', '-u', 'root', '-N', '-B', '-e', $Sql)
    if ($Database) { $DockerArgs += $Database }
    return (& docker @DockerArgs 2>$null)
}

function Invoke-Health {
    $rc = 0

    Write-Host '== MySQL =='
    & docker exec -e "MYSQL_PWD=$MysqlRootPassword" $MysqlContainer mysqladmin ping -h 127.0.0.1 -u root --silent 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[FAIL] MySQL 探活失败，容器 $MysqlContainer 是否在运行？" -ForegroundColor Red
        $rc = 1
    }
    else {
        $version = Get-MySqlRootScalar 'SELECT VERSION()'
        $charset = Get-MySqlRootScalar "SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='$MysqlDatabase'"
        $tz = Get-MySqlRootScalar 'SELECT @@global.time_zone'
        $dbExists = Get-MySqlRootScalar "SHOW DATABASES LIKE '$MysqlDatabase'"
        $userOk = & docker exec -e "MYSQL_PWD=$MysqlPassword" $MysqlContainer mysql -u $MysqlUser -N -B -e 'SELECT 1' $MysqlDatabase 2>$null
        Write-Host "[OK]   版本 $version"
        Write-Host "[OK]   字符集/排序规则 $charset"
        Write-Host "[OK]   时区 $tz"
        if ($dbExists) { Write-Host "[OK]   库 $MysqlDatabase 存在" } else { Write-Host "[FAIL] 库 $MysqlDatabase 不存在" -ForegroundColor Red; $rc = 1 }
        if ($userOk -eq '1') { Write-Host "[OK]   应用账号 $MysqlUser 可登录" } else { Write-Host "[FAIL] 应用账号 $MysqlUser 无法登录" -ForegroundColor Red; $rc = 1 }
    }

    Write-Host ''
    Write-Host '== Redis =='
    $ping = & docker exec $RedisContainer redis-cli --no-auth-warning -a $RedisPassword ping 2>$null
    if ($ping -match 'PONG') {
        Write-Host '[OK]   PONG'
        $aof = & docker exec $RedisContainer redis-cli --no-auth-warning -a $RedisPassword config get appendonly 2>$null | Select-Object -Last 1
        Write-Host "[OK]   appendonly=$aof"
        & docker exec $RedisContainer redis-cli --no-auth-warning -a $RedisPassword set rag-wiki:health ok 2>$null | Out-Null
        $back = & docker exec $RedisContainer redis-cli --no-auth-warning -a $RedisPassword get rag-wiki:health 2>$null
        if ($back -eq 'ok') { Write-Host '[OK]   set/get 一致' } else { Write-Host "[FAIL] set/get 不一致：$back" -ForegroundColor Red; $rc = 1 }
    }
    else {
        Write-Host "[FAIL] Redis 探活失败，容器 $RedisContainer 是否在运行？" -ForegroundColor Red
        $rc = 1
    }

    Write-Host ''
    Write-Host '== Elasticsearch =='
    try {
        $info = Invoke-RestMethod -Uri $EsUris -TimeoutSec 5
        $health = Invoke-RestMethod -Uri "$EsUris/_cluster/health" -TimeoutSec 5
        Write-Host "[OK]   版本 $($info.version.number)"
        Write-Host "[OK]   集群状态 $($health.status)，节点数 $($health.number_of_nodes)"
        $plugins = & docker exec $EsContainer bin/elasticsearch-plugin list 2>$null
        if ([string]::IsNullOrEmpty($plugins)) {
            Write-Host '[OK]   无额外插件（中文分析用内置 cjk，见 docs/backend/总体方案.md）'
        }
        else {
            Write-Host "[OK]   已装插件：$($plugins -join ', ')"
        }
    }
    catch {
        Write-Host "[FAIL] ES 探活失败，容器 $EsContainer 是否在运行？" -ForegroundColor Red
        $rc = 1
    }

    Write-Host ''
    if ($rc -eq 0) { Write-Host 'health 全部通过' } else { Write-Host 'health 存在失败项' -ForegroundColor Red }
    if ($rc -ne 0) { exit $rc }
}

function Invoke-Reset {
    Write-Host '⚠️  即将停止全部中间件并删除数据卷：' -ForegroundColor Yellow
    Write-Host '    mysql-data / redis-data / es-data'
    Write-Host '    数据库、Redis 数据、ES 索引将全部丢失，不可恢复。'
    $answer = Read-Host '确认请输入项目名 rag-wiki'
    if ($answer -ne 'rag-wiki') {
        Write-Host '输入不匹配，已取消。'
        return
    }
    Invoke-Compose down -v
    Write-Host '数据卷已删除。重新启动：powershell -File scripts/dev.ps1 up'
}

function Show-Usage {
    Write-Host @'
用法：powershell -ExecutionPolicy Bypass -File scripts/dev.ps1 <命令>

命令：
  up        启动全部中间件，三个服务全部 healthy 后才返回
  down      停止全部中间件（保留数据卷）
  restart   重启全部中间件
  status    查看容器状态
  health    逐项探活 MySQL / Redis / ES
  logs      跟踪日志（Ctrl-C 退出）
  reset     停止并删除数据卷（需二次确认，数据不可恢复）
'@
}

switch ($Command.ToLower()) {
    'up' { Invoke-Up }
    'down' { Invoke-Down }
    'restart' { Invoke-Restart }
    'status' { Invoke-Status }
    'health' { Invoke-Health }
    'logs' { Invoke-Logs }
    'reset' { Invoke-Reset }
    'help' { Show-Usage }
    default {
        Write-Host "未知命令：$Command" -ForegroundColor Red
        Write-Host ''
        Show-Usage
        exit 2
    }
}
