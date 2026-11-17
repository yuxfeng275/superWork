<#
.SYNOPSIS
    合拍投递目录监听（Windows，PowerShell 5.1+，零外部依赖）。

.DESCRIPTION
    用 FileSystemWatcher 盯一个目录（默认 %USERPROFILE%\Documents\合拍），
    事件进来后等 3 秒（Debounce）再把稳定下来的文件过一遍本地护栏：

      命中敏感规则（sensitive-rules.json）→ 移到 quarantine 目录 + 写日志，不上传
      通过                                → 调 huji-put.ps1 file 上传

    规则文件与 mac 端（macapp/HepaiDelivery）是同一份 shared/privacy/sensitive-rules.json，
    判定顺序也一致：忽略 → 扩展名 → 文件名名单 → 文件名关键词 → 体积 → 上传。

    日志：%LOCALAPPDATA%\HujiDelivery\logs\huji-folder-sync-yyyyMMdd.log（按天一个文件）。

.NOTES
    密码从 $env:HUJI_PASSWORD 读（不落盘）。计划任务里跑之前先把用户环境变量设好，
    见 tools/win/README.md。

.EXAMPLE
    $env:HUJI_PASSWORD = '…'
    .\huji-folder-sync.ps1 -Project 1 -User zhangsan

.EXAMPLE
    # 只扫一遍现有文件（不进监听循环），排查用
    .\huji-folder-sync.ps1 -Project 1 -User zhangsan -Once
#>

[CmdletBinding()]
param(
    [string]$Root = '',
    [string]$Project = '',
    [string]$User = '',
    [string]$BaseUrl = '',
    [string]$Visibility = 'PROJECT',
    [string]$Rules = '',
    [string]$Quarantine = '',
    [string]$LogDir = '',
    [string]$HujiPut = '',
    [int]$DebounceSeconds = 3,
    [int]$KeepDays = 30,
    [switch]$Once,
    [switch]$Help
)

$ErrorActionPreference = 'Stop'

$script:Prog = 'huji-folder-sync'
$script:Failed = 0

function Write-Err {
    param([string]$Text)
    [Console]::Error.WriteLine($Text)
}

function Fail {
    param([string]$Message, [int]$Code = 1)
    Write-Err ("{0}: {1}" -f $script:Prog, $Message)
    exit $Code
}

function Show-Usage {
    $text = @'
huji-folder-sync — 监听一个目录，自动把文件投进合拍项目事实层

用法：
  .\huji-folder-sync.ps1 -Project <项目ID> -User <账号> [选项]

必填：
  -Project <ID>         投到哪个项目（数字）
  -User <账号>          登录账号（或设 $env:HUJI_USER）

选项：
  -Root <目录>          监听目录，默认 %USERPROFILE%\Documents\合拍
  -BaseUrl <URL>        后端根地址，默认 $env:HUJI_BASE_URL 或 http://127.0.0.1:18081
  -Visibility <级别>    PROJECT（默认）/ PRIVATE_COMPANY / OWNER
  -Rules <文件>         敏感规则集，默认 <仓库>\shared\privacy\sensitive-rules.json
  -Quarantine <目录>    敏感文件隔离目录，默认 %LOCALAPPDATA%\HujiDelivery\quarantine
  -LogDir <目录>        日志目录，默认 %LOCALAPPDATA%\HujiDelivery\logs
  -HujiPut <脚本>       投递脚本，默认同目录的 huji-put.ps1
  -DebounceSeconds <秒> 抖动合并窗口，默认 3
  -KeepDays <天>        日志保留天数，默认 30
  -Once                 只扫一遍现有文件就退出（不进监听循环）
  -Help                 就是这页

密码：只认 $env:HUJI_PASSWORD。

退出码：0 正常；1 有文件投递失败；2 用法/环境错误。
'@
    Write-Host $text
}

if ($Help) { Show-Usage; exit 0 }

# FileSystemWatcher 和子进程都不认相对路径，统一转绝对
function Resolve-FullPath {
    param([string]$Path)
    if (-not $Path) { return $Path }
    try {
        if (Test-Path -LiteralPath $Path) { return (Resolve-Path -LiteralPath $Path).ProviderPath }
    } catch { }
    try { return [System.IO.Path]::GetFullPath($Path) } catch { return $Path }
}

# ---------------------------------------------------------------------------
# 路径与参数
# ---------------------------------------------------------------------------

if (-not $Root) {
    $Root = Join-Path $env:USERPROFILE 'Documents\合拍'
} else {
    $Root = [Environment]::ExpandEnvironmentVariables($Root)
}
if (-not $LogDir) { $LogDir = Join-Path $env:LOCALAPPDATA 'HujiDelivery\logs' }
if (-not $Quarantine) { $Quarantine = Join-Path $env:LOCALAPPDATA 'HujiDelivery\quarantine' }
if (-not $Rules) {
    if ($env:HUJI_RULES) { $Rules = $env:HUJI_RULES }
    else { $Rules = Join-Path $PSScriptRoot '..\..\shared\privacy\sensitive-rules.json' }
}
if (-not $HujiPut) { $HujiPut = Join-Path $PSScriptRoot 'huji-put.ps1' }
if (-not $BaseUrl) {
    if ($env:HUJI_BASE_URL) { $BaseUrl = $env:HUJI_BASE_URL }
    else { $BaseUrl = 'http://127.0.0.1:18081' }
}
if (-not $User) { $User = $env:HUJI_USER }

$Root = Resolve-FullPath $Root
$Rules = Resolve-FullPath $Rules
$LogDir = Resolve-FullPath $LogDir
$Quarantine = Resolve-FullPath $Quarantine
$HujiPut = Resolve-FullPath $HujiPut

if (-not $Project -or $Project -notmatch '^[0-9]+$') { Fail ('-Project 必须是数字项目ID，当前值: ' + $Project) 2 }
if (-not $User) { Fail '缺少 -User（或 $env:HUJI_USER）' 2 }
if (-not (Test-Path -LiteralPath $HujiPut -PathType Leaf)) { Fail ('找不到投递脚本：' + $HujiPut) 2 }
if (-not (Test-Path -LiteralPath $Rules -PathType Leaf)) { Fail ('找不到敏感规则集：' + $Rules) 2 }

$Visibility = $Visibility.ToUpperInvariant()
if ($Visibility -ne 'PROJECT' -and $Visibility -ne 'PRIVATE_COMPANY' -and $Visibility -ne 'OWNER') {
    Fail ('-Visibility 只能是 PROJECT / PRIVATE_COMPANY / OWNER，当前值: ' + $Visibility) 2
}
if ($DebounceSeconds -lt 1) { $DebounceSeconds = 1 }

# ---------------------------------------------------------------------------
# 日志（按天滚动）
# ---------------------------------------------------------------------------

function Initialize-LogDir {
    if (-not (Test-Path -LiteralPath $LogDir)) {
        New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
    }
    if ($KeepDays -gt 0) {
        $cutoff = (Get-Date).AddDays(-1 * $KeepDays)
        Get-ChildItem -LiteralPath $LogDir -Filter 'huji-folder-sync-*.log' -ErrorAction SilentlyContinue |
            Where-Object { $_.LastWriteTime -lt $cutoff } |
            Remove-Item -Force -ErrorAction SilentlyContinue
    }
}

function Write-Log {
    param([string]$Level, [string]$Message)
    $now = Get-Date
    $file = Join-Path $LogDir ('huji-folder-sync-' + $now.ToString('yyyyMMdd') + '.log')
    $line = '{0} [{1}] {2}' -f $now.ToString('yyyy-MM-dd HH:mm:ss'), $Level, $Message
    try { Add-Content -LiteralPath $file -Value $line -Encoding UTF8 } catch { }
    if ($Level -eq 'ERROR') { Write-Host $line -ForegroundColor Red }
    elseif ($Level -eq 'WARN') { Write-Host $line -ForegroundColor Yellow }
    else { Write-Host $line }
}

# ---------------------------------------------------------------------------
# 规则（与 mac / huji-put.ps1 同一份文件、同一判定顺序）
# ---------------------------------------------------------------------------

function Get-JsonProp {
    param($Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    $prop = $Object.PSObject.Properties[$Name]
    if ($null -eq $prop) { return $null }
    return $prop.Value
}

function Get-RuleArray {
    param($Node, [string]$Name)
    $value = Get-JsonProp $Node $Name
    if ($null -eq $value) { return @() }
    return @($value)
}

function Read-SensitiveRules {
    param([string]$Path)
    $raw = Get-Content -LiteralPath $Path -Raw -Encoding UTF8
    try {
        return ConvertFrom-Json -InputObject $raw
    } catch {
        Fail ('敏感规则集不是合法 JSON：' + $Path) 2
    }
}

function Test-IgnoredName {
    param($Rules, [string]$FileName)
    $dirs = Get-JsonProp $Rules 'directories'
    foreach ($n in (Get-RuleArray $dirs 'ignoreNames')) {
        if ([string]::IsNullOrEmpty([string]$n)) { continue }
        if ($FileName.Equals([string]$n, [StringComparison]::OrdinalIgnoreCase)) { return $true }
    }
    foreach ($p in (Get-RuleArray $dirs 'ignorePrefixes')) {
        if ([string]::IsNullOrEmpty([string]$p)) { continue }
        if ($FileName.StartsWith([string]$p, [StringComparison]::OrdinalIgnoreCase)) { return $true }
    }
    foreach ($s in (Get-RuleArray $dirs 'ignoreSuffixes')) {
        if ([string]::IsNullOrEmpty([string]$s)) { continue }
        if ($FileName.EndsWith([string]$s, [StringComparison]::OrdinalIgnoreCase)) { return $true }
    }
    return $false
}

function Find-MatchingNeedle {
    param($Needles, [string]$Text)
    if ([string]::IsNullOrEmpty($Text)) { return '' }
    foreach ($n in $Needles) {
        if ([string]::IsNullOrEmpty([string]$n)) { continue }
        if ($Text.IndexOf([string]$n, [StringComparison]::OrdinalIgnoreCase) -ge 0) { return [string]$n }
    }
    return ''
}

function New-Verdict {
    param([string]$Kind, [string]$Reason)
    return [pscustomobject]@{ Kind = $Kind; Reason = $Reason }
}

function Get-SensitiveVerdict {
    param($Rules, [string]$Path)

    $fileName = [System.IO.Path]::GetFileName($Path)
    $stem = [System.IO.Path]::GetFileNameWithoutExtension($Path)
    $extension = [System.IO.Path]::GetExtension($Path)

    if (Test-IgnoredName -Rules $Rules -FileName $fileName) {
        return New-Verdict 'IGNORE' ('系统/临时文件，按规则忽略：' + $fileName)
    }
    foreach ($e in (Get-RuleArray $Rules 'sensitiveExtensions')) {
        if ([string]::IsNullOrEmpty([string]$e) -or -not $extension) { continue }
        if ($extension.Equals([string]$e, [StringComparison]::OrdinalIgnoreCase)) {
            return New-Verdict 'SENSITIVE' ('扩展名 ' + $extension + ' 属敏感类型（sensitiveExtensions: ' + $e + '）')
        }
    }
    foreach ($n in (Get-RuleArray $Rules 'sensitiveNames')) {
        if ([string]::IsNullOrEmpty([string]$n)) { continue }
        if ($stem.Equals([string]$n, [StringComparison]::OrdinalIgnoreCase) -or
            $fileName.Equals([string]$n, [StringComparison]::OrdinalIgnoreCase)) {
            return New-Verdict 'SENSITIVE' ('文件名 "' + $fileName + '" 命中敏感名单（sensitiveNames: ' + $n + '）')
        }
    }
    $keyword = Find-MatchingNeedle -Needles (Get-RuleArray $Rules 'sensitiveFileKeywords') -Text $fileName
    if ($keyword) {
        return New-Verdict 'SENSITIVE' ('文件名 "' + $fileName + '" 含敏感关键词「' + $keyword + '」')
    }
    $thresholds = Get-JsonProp $Rules 'thresholds'
    $maxMb = Get-JsonProp $thresholds 'maxFileSizeMb'
    if ($maxMb) {
        try {
            $limitBytes = [double]$maxMb * 1MB
            $size = (Get-Item -LiteralPath $Path).Length
            if ($size -gt $limitBytes) {
                return New-Verdict 'TOOLARGE' ('体积 ' + [math]::Round($size / 1MB, 1) + ' MB 超过规则上限 ' + $maxMb + ' MB')
            }
        } catch { }
    }
    return New-Verdict 'OK' ''
}

# ---------------------------------------------------------------------------
# 隔离
# ---------------------------------------------------------------------------

function Move-ToQuarantine {
    param([string]$Path, [string]$Reason)
    $dayDir = Join-Path $Quarantine (Get-Date).ToString('yyyyMMdd')
    if (-not (Test-Path -LiteralPath $dayDir)) {
        New-Item -ItemType Directory -Path $dayDir -Force | Out-Null
    }
    $fileName = [System.IO.Path]::GetFileName($Path)
    $target = Join-Path $dayDir $fileName
    if (Test-Path -LiteralPath $target) {
        $stamp = (Get-Date).ToString('HHmmss')
        $stem = [System.IO.Path]::GetFileNameWithoutExtension($fileName)
        $extension = [System.IO.Path]::GetExtension($fileName)
        $target = Join-Path $dayDir ($stem + '.' + $stamp + $extension)
    }
    try {
        Move-Item -LiteralPath $Path -Destination $target -Force
        $note = Join-Path $dayDir '_quarantine.log'
        Add-Content -LiteralPath $note -Encoding UTF8 -Value ('{0}	{1}	{2}' -f (Get-Date).ToString('yyyy-MM-dd HH:mm:ss'), $target, $Reason)
        Write-Log 'WARN' ('已隔离 → ' + $target + '（' + $Reason + '）')
        return $target
    } catch {
        Write-Log 'ERROR' ('隔离失败 ' + $Path + '：' + $_.Exception.Message)
        return ''
    }
}

# ---------------------------------------------------------------------------
# 上传
# ---------------------------------------------------------------------------

function Send-ToHuji {
    param([string]$Path)
    # 起独立进程跑 huji-put.ps1：CLI 里有 exit，同进程调用会把监听一起带走。
    $sendArgs = @(
        '-NoProfile', '-NonInteractive',
        '-ExecutionPolicy', 'Bypass',
        '-File', $HujiPut,
        '--base-url', $BaseUrl,
        '--user', $User,
        '--project', $Project,
        '--visibility', $Visibility,
        '--rules', $Rules,
        'file', $Path
    )
    $output = & powershell.exe @sendArgs 2>&1
    $code = $LASTEXITCODE
    $joined = ($output | Out-String).Trim()
    return [pscustomobject]@{ ExitCode = $code; Output = $joined }
}

# ---------------------------------------------------------------------------
# 目录扫描 + 判定
# ---------------------------------------------------------------------------

$script:RulesObject = Read-SensitiveRules -Path $Rules
# 已经处理过的文件指纹，避免 -Once 重跑或事件重复时反复上传
$script:Seen = @{}

function Get-FileStamp {
    param([string]$Path)
    try {
        $item = Get-Item -LiteralPath $Path -Force
        return ('{0}:{1}' -f $item.Length, $item.LastWriteTimeUtc.Ticks)
    } catch {
        return ''
    }
}

function Invoke-ProcessFile {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return }

    $stamp = Get-FileStamp $Path
    if (-not $stamp) { return }
    if ($script:Seen.ContainsKey($Path) -and $script:Seen[$Path] -eq $stamp) { return }

    $verdict = Get-SensitiveVerdict -Rules $script:RulesObject -Path $Path
    switch ($verdict.Kind) {
        'IGNORE' {
            $script:Seen[$Path] = $stamp
            Write-Log 'DEBUG' ('跳过 ' + $Path + '（' + $verdict.Reason + '）')
        }
        'SENSITIVE' {
            $script:Seen[$Path] = $stamp
            [void](Move-ToQuarantine -Path $Path -Reason $verdict.Reason)
        }
        'TOOLARGE' {
            $script:Seen[$Path] = $stamp
            Write-Log 'WARN' ('跳过 ' + $Path + '（' + $verdict.Reason + '）')
        }
        default {
            Write-Log 'INFO' ('上传 ' + $Path)
            $result = Send-ToHuji -Path $Path
            if ($result.ExitCode -eq 0) {
                $script:Seen[$Path] = $stamp
                Write-Log 'INFO' ('已投递 ' + $Path + ' → ' + ($result.Output -split "`n" | Where-Object { $_ -match 'ok ' } | Select-Object -First 1))
            } else {
                $script:Failed++
                Write-Log 'ERROR' ('投递失败 ' + $Path + '（exit ' + $result.ExitCode + '）：' + $result.Output)
            }
        }
    }
}

function Invoke-ScanOnce {
    $files = Get-ChildItem -LiteralPath $Root -Recurse -File -Force -ErrorAction SilentlyContinue
    foreach ($file in $files) {
        Invoke-ProcessFile -Path $file.FullName
    }
}

# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

if (-not $env:HUJI_PASSWORD) {
    Fail '没有密码：先设置用户环境变量 HUJI_PASSWORD（见 tools/win/README.md），监听进程不接受交互输入。' 2
}
if (-not (Test-Path -LiteralPath $Root)) {
    New-Item -ItemType Directory -Path $Root -Force | Out-Null
    Write-Host ('已创建监听目录：' + $Root)
}

Initialize-LogDir
Write-Log 'INFO' ('启动：目录=' + $Root + ' 项目=' + $Project + ' 账号=' + $User + ' 服务=' + $BaseUrl + ' 抖动=' + $DebounceSeconds + 's 规则=' + $Rules)
Write-Log 'INFO' ('隔离目录=' + $Quarantine + ' 日志目录=' + $LogDir)

if ($Once) {
    Invoke-ScanOnce
    Write-Log 'INFO' ('一次性扫描结束：失败 ' + $script:Failed + ' 条；' + $script:Seen.Count + ' 个文件已处理')
    if ($script:Failed -gt 0) { exit 1 }
    exit 0
}

$watcher = New-Object System.IO.FileSystemWatcher
$watcher.Path = $Root
$watcher.IncludeSubdirectories = $true
$watcher.InternalBufferSize = 65536
$watcher.NotifyFilter = [System.IO.NotifyFilters]::FileName -bor [System.IO.NotifyFilters]::LastWrite -bor [System.IO.NotifyFilters]::Size

$subscriptions = @()
$subscriptions += Register-ObjectEvent -InputObject $watcher -EventName Created -SourceIdentifier 'huji-sync-created'
$subscriptions += Register-ObjectEvent -InputObject $watcher -EventName Changed -SourceIdentifier 'huji-sync-changed'
$subscriptions += Register-ObjectEvent -InputObject $watcher -EventName Renamed -SourceIdentifier 'huji-sync-renamed'
$subscriptions += Register-ObjectEvent -InputObject $watcher -EventName Error -SourceIdentifier 'huji-sync-error'
$watcher.EnableRaisingEvents = $true

Write-Log 'INFO' '监听已启动（FileSystemWatcher，Ctrl+C 结束）'

# 启动补齐：监听进程停机 / 重启期间放进目录的文件不会产生 FileSystemWatcher 事件，
# 光靠事件循环会让它们永远不会被投递。所以进入 Wait 循环之前先整体扫一遍。
# 位置放在 EnableRaisingEvents 之后：扫描期间进来的事件由 watcher 缓冲，扫描结束后照常处理，不会漏。
# 重扫不会重复投递事实：本次进程内已处理的文件有「已处理指纹」($script:Seen，size+LastWriteTimeUtc) 挡掉；
# 跨进程 / 跨重启靠后端的 file:{projectId}:{sha256} 幂等键，同一个文件再传一次也不会多出一条事实。
# 失败不能阻止监听启动：整体 try/catch，只记日志。
try {
    Write-Log 'INFO' '启动补齐：扫描监听目录中停机期间落下的文件'
    Invoke-ScanOnce
    Write-Log 'INFO' ('启动补齐结束：失败 ' + $script:Failed + ' 条；' + $script:Seen.Count + ' 个文件已处理')
} catch {
    Write-Log 'ERROR' ('启动补齐扫描失败（不影响监听启动）：' + $_.Exception.Message)
}

# pending: 路径 → 首次出现时间 + 上次看到的体积
$pending = @{}
$stop = $false

try {
    while (-not $stop) {
        $events = @(Get-Event | Where-Object { $_.SourceIdentifier -like 'huji-sync-*' })
        foreach ($ev in $events) {
            try {
                if ($ev.SourceIdentifier -eq 'huji-sync-error') {
                    Write-Log 'ERROR' ('监听缓冲溢出或目录不可用：' + $ev.SourceEventArgs.GetException().Message)
                } else {
                    $path = $ev.SourceEventArgs.FullPath
                    if ($path) {
                        if (-not $pending.ContainsKey($path)) {
                            $pending[$path] = @{ Since = (Get-Date); LastSize = -1 }
                        }
                    }
                }
            } catch {
                Write-Log 'ERROR' ('处理事件失败：' + $_.Exception.Message)
            }
            Remove-Event -EventIdentifier $ev.EventIdentifier -ErrorAction SilentlyContinue
        }

        $now = Get-Date
        $ready = @()
        foreach ($path in @($pending.Keys)) {
            $entry = $pending[$path]
            if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
                $pending.Remove($path)
                continue
            }
            $size = -1
            try { $size = (Get-Item -LiteralPath $path -Force).Length } catch { continue }
            if ($size -ne $entry.LastSize) {
                # 还在写：刷新体积和时间，继续等
                $entry.LastSize = $size
                $entry.Since = $now
                continue
            }
            $waited = ($now - $entry.Since).TotalSeconds
            if ($waited -ge $DebounceSeconds) { $ready += $path }
        }

        foreach ($path in $ready) {
            $pending.Remove($path)
            try { Invoke-ProcessFile -Path $path } catch { Write-Log 'ERROR' ('处理失败 ' + $path + '：' + $_.Exception.Message) }
        }

        Start-Sleep -Milliseconds 500
    }
} finally {
    $watcher.EnableRaisingEvents = $false
    foreach ($sub in $subscriptions) {
        try { Unregister-Event -SourceIdentifier $sub.Name -ErrorAction SilentlyContinue } catch { }
    }
    $watcher.Dispose()
    Write-Log 'INFO' '监听已停止'
}

if ($script:Failed -gt 0) { exit 1 }
exit 0
