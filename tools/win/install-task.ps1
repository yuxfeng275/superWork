<#
.SYNOPSIS
    把合拍目录监听注册成 Windows 计划任务（PowerShell 5.1+，零外部依赖）。

.DESCRIPTION
    只做三件事：打印任务定义（默认行为）、注册（-Apply）、卸载（-Uninstall -Apply）。
    默认永远不动系统 —— 必须显式 -Apply 才会真正写进任务计划程序。

    任务形态：
      Action    powershell.exe -NoProfile -WindowStyle Hidden -NonInteractive -File  huji-folder-sync.ps1 …
      Trigger   AtStartup + AtLogOn（可用 -Trigger 选其中一个）
      Principal 当前用户（Interactive，RunLevel Limited）—— 不是 SYSTEM，不跨用户
      Settings  Hidden（任务计划程序里不显示）、不限执行时长、失败重试、忽略重复实例

.NOTES
    密码不写进任务参数，任务里的进程从「用户环境变量 HUJI_PASSWORD」里读。
    设置方法见 tools/win/README.md。

.EXAMPLE
    # 只看要注册什么（不写系统）
    .\install-task.ps1 -Project 1 -User zhangsan -DryRun

.EXAMPLE
    # 真的注册
    .\install-task.ps1 -Project 1 -User zhangsan -Apply

.EXAMPLE
    # 卸载
    .\install-task.ps1 -Uninstall -Apply
#>

[CmdletBinding()]
param(
    [switch]$Apply,
    [switch]$DryRun,
    [switch]$Uninstall,
    [string]$TaskName = 'HujiFolderSync',
    [string]$Root = '',
    [string]$Project = '',
    [string]$User = '',
    [string]$BaseUrl = '',
    [string]$Visibility = 'PROJECT',
    [string]$Rules = '',
    [string]$LogDir = '',
    [string]$Quarantine = '',
    [int]$DebounceSeconds = 3,
    [ValidateSet('Both', 'Startup', 'Logon')]
    [string]$Trigger = 'Both',
    [switch]$Help
)

$ErrorActionPreference = 'Stop'

$script:Prog = 'install-task'

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
install-task — 注册 / 卸载「合拍投递目录监听」计划任务

用法：
  .\install-task.ps1 -Project <项目ID> -User <账号> [-Apply | -DryRun]
  .\install-task.ps1 -Uninstall [-Apply]

必填（注册时）：
  -Project <ID>      投到哪个项目（数字）
  -User <账号>       登录账号（或设 $env:HUJI_USER）

选项：
  -TaskName <名字>        计划任务名，默认 HujiFolderSync
  -Root <目录>            监听目录，默认 %USERPROFILE%\Documents\合拍
  -BaseUrl <URL>          默认 $env:HUJI_BASE_URL 或 http://127.0.0.1:18081
  -Visibility <级别>      PROJECT（默认）/ PRIVATE_COMPANY / OWNER
  -Rules <文件>           敏感规则集，默认 <仓库>\shared\privacy\sensitive-rules.json
  -LogDir <目录>          日志目录，默认 %LOCALAPPDATA%\HujiDelivery\logs
  -Quarantine <目录>      隔离目录，默认 %LOCALAPPDATA%\HujiDelivery\quarantine
  -DebounceSeconds <秒>   抖动合并窗口，默认 3
  -Trigger Both|Startup|Logon  触发器，默认 Both（开机 + 当前用户登录）
  -DryRun                 只打印任务定义，不写系统（默认行为就是这样）
  -Apply                  真的注册 / 真的卸载
  -Uninstall              卸载
  -Help                   就是这页

安全默认：不带 -Apply 一律不动系统。密码不进任务参数，运行期从用户环境变量
          HUJI_PASSWORD 读（见 tools/win/README.md）。

退出码：0 成功；1 失败；2 用法/环境错误。
'@
    Write-Host $text
}

if ($Help) {
    Show-Usage
    exit 0
}

# 任务参数里的路径必须是绝对路径（计划任务的工作目录不可预期）
function Resolve-FullPath {
    param([string]$Path)
    if (-not $Path) { return $Path }
    try {
        if (Test-Path -LiteralPath $Path) { return (Resolve-Path -LiteralPath $Path).ProviderPath }
    } catch { }
    try { return [System.IO.Path]::GetFullPath($Path) } catch { return $Path }
}

if ($DryRun -and $Apply) {
    Write-Host '提示：-DryRun 与 -Apply 同时给了，按 -DryRun 处理（不写系统）。'
    $Apply = $false
}
if ($Uninstall -and -not $Apply) { $DryRun = $true }

# ---------------------------------------------------------------------------
# 解析路径与参数
# ---------------------------------------------------------------------------

$syncScript = Join-Path $PSScriptRoot 'huji-folder-sync.ps1'
if (-not $Uninstall -and -not (Test-Path -LiteralPath $syncScript -PathType Leaf)) {
    Fail ('找不到 huji-folder-sync.ps1（应当与本脚本同目录）：' + $syncScript) 2
}

if (-not $Root) { $Root = Join-Path $env:USERPROFILE 'Documents\合拍' }
if (-not $LogDir) { $LogDir = Join-Path $env:LOCALAPPDATA 'HujiDelivery\logs' }
if (-not $Quarantine) { $Quarantine = Join-Path $env:LOCALAPPDATA 'HujiDelivery\quarantine' }
if (-not $Rules) {
    if ($env:HUJI_RULES) { $Rules = $env:HUJI_RULES }
    else { $Rules = Join-Path $PSScriptRoot '..\..\shared\privacy\sensitive-rules.json' }
}
if (-not $BaseUrl) {
    if ($env:HUJI_BASE_URL) { $BaseUrl = $env:HUJI_BASE_URL }
    else { $BaseUrl = 'http://127.0.0.1:18081' }
}
if (-not $User) { $User = $env:HUJI_USER }

$isUninstall = [bool]$Uninstall

if (-not $isUninstall) {
    if (-not $Project -or $Project -notmatch '^[0-9]+$') { Fail ('-Project 必须是数字项目ID，当前值: ' + $Project) 2 }
    if (-not $User) { Fail '缺少 -User（或 $env:HUJI_USER）' 2 }
    $Visibility = $Visibility.ToUpperInvariant()
    if ($Visibility -ne 'PROJECT' -and $Visibility -ne 'PRIVATE_COMPANY' -and $Visibility -ne 'OWNER') {
        Fail ('-Visibility 只能是 PROJECT / PRIVATE_COMPANY / OWNER，当前值: ' + $Visibility) 2
    }
    if (-not (Test-Path -LiteralPath $Rules -PathType Leaf)) {
        Write-Host ('提醒：规则集暂时不存在（' + $Rules + '），注册后监听会启动失败直到它到位。')
    }
}

$syncScript = Resolve-FullPath $syncScript
$Root = Resolve-FullPath $Root
$Rules = Resolve-FullPath $Rules
$LogDir = Resolve-FullPath $LogDir
$Quarantine = Resolve-FullPath $Quarantine

# 引号：任务参数里路径带空格必须用双引号包起来；内部的双引号直接拒绝，避免注入
function Format-QuotedArg {
    param([string]$Value)
    if ($Value -and $Value.Contains('"')) { Fail ('路径里含双引号，拒绝写入任务参数：' + $Value) 2 }
    return ('"' + $Value + '"')
}

$arguments = @(
    '-NoProfile',
    '-NonInteractive',
    '-WindowStyle', 'Hidden',
    '-ExecutionPolicy', 'Bypass',
    '-File', (Format-QuotedArg $syncScript),
    '-Root', (Format-QuotedArg $Root),
    '-Project', $Project,
    '-User', (Format-QuotedArg $User),
    '-BaseUrl', (Format-QuotedArg $BaseUrl),
    '-Visibility', $Visibility,
    '-Rules', (Format-QuotedArg $Rules),
    '-LogDir', (Format-QuotedArg $LogDir),
    '-Quarantine', (Format-QuotedArg $Quarantine),
    '-DebounceSeconds', [string]$DebounceSeconds
) -join ' '

$principalUser = $env:USERDOMAIN + '\' + $env:USERNAME

$triggerList = @()
if ($Trigger -eq 'Both' -or $Trigger -eq 'Startup') { $triggerList += 'AtStartup' }
if ($Trigger -eq 'Both' -or $Trigger -eq 'Logon') { $triggerList += 'AtLogOn' }

# ---------------------------------------------------------------------------
# 打印定义（默认 / -DryRun / 卸载预览）
# ---------------------------------------------------------------------------

function Show-Definition {
    Write-Host ''
    Write-Host '=== 计划任务定义（未写系统）==='
    Write-Host ('任务名    : ' + $TaskName)
    Write-Host ('可执行    : powershell.exe')
    Write-Host ('参数      : ' + $arguments)
    Write-Host ('工作目录  : ' + $PSScriptRoot)
    Write-Host ''
    Write-Host '--- Action ---'
    Write-Host ('New-ScheduledTaskAction -Execute ''powershell.exe'' -Argument ''' + $arguments + ''' -WorkingDirectory ''' + $PSScriptRoot + '''')
    Write-Host ''
    Write-Host '--- Trigger ---'
    foreach ($item in $triggerList) {
        if ($item -eq 'AtStartup') {
            Write-Host 'New-ScheduledTaskTrigger -AtStartup'
        } else {
            Write-Host ('New-ScheduledTaskTrigger -AtLogOn -User ''' + $principalUser + '''')
        }
    }
    Write-Host ''
    Write-Host '--- Settings ---'
    Write-Host 'New-ScheduledTaskSettingsSet -Hidden -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero)'
    Write-Host ''
    Write-Host '--- Principal ---'
    Write-Host ('New-ScheduledTaskPrincipal -UserId ''' + $principalUser + ''' -LogonType Interactive -RunLevel Limited')
    Write-Host ''
    Write-Host '--- Register ---'
    if ($triggerList.Count -gt 1) {
        Write-Host ('Register-ScheduledTask -TaskName ''' + $TaskName + ''' -Action $action -Trigger $startupTrigger,$logonTrigger -Settings $settings -Principal $principal -Description "合拍投递目录监听：命中敏感规则即隔离" -Force')
    } else {
        Write-Host ('Register-ScheduledTask -TaskName ''' + $TaskName + ''' -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Description "合拍投递目录监听：命中敏感规则即隔离" -Force')
    }
    Write-Host ''
    Write-Host '--- 运行期环境 ---'
    Write-Host ('监听目录  : ' + $Root)
    Write-Host ('日志目录  : ' + $LogDir)
    Write-Host ('隔离目录  : ' + $Quarantine)
    Write-Host ('规则集    : ' + $Rules)
    Write-Host '密码      : 不写进任务；进程从用户环境变量 HUJI_PASSWORD 读'
    Write-Host ''
    Write-Host '这是「只看不写」。要真注册，加 -Apply 重跑一次。'
}

if ($isUninstall) {
    Write-Host ''
    Write-Host '=== 卸载 ==='
    Write-Host ('Stop-ScheduledTask -TaskName ''' + $TaskName + '''')
    Write-Host ('Unregister-ScheduledTask -TaskName ''' + $TaskName + ''' -Confirm:$false')
    Write-Host ''
    if (-not $Apply -or $DryRun) {
        if (-not $Apply) { Write-Host '这是「只看不写」。要真卸载，加 -Apply 重跑一次。' }
        exit 0
    }

    $existing = $null
    try { $existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue } catch { }
    if (-not $existing) {
        Write-Host ('任务不存在，无需卸载：' + $TaskName)
        exit 0
    }
    try { Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue } catch { }
    try {
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    } catch {
        Fail ('卸载失败：' + $_.Exception.Message) 1
    }
    Write-Host ('已卸载计划任务：' + $TaskName)
    Write-Host '日志与隔离目录未删除（%LOCALAPPDATA%\HujiDelivery），需要自己清。'
    exit 0
}

Show-Definition

if (-not $Apply) {
    Write-Host '（默认只打印定义；加 -Apply 才会真正注册）'
    exit 0
}

# ---------------------------------------------------------------------------
# 真正注册
# ---------------------------------------------------------------------------

$required = @('New-ScheduledTaskAction', 'New-ScheduledTaskTrigger', 'New-ScheduledTaskSettingsSet', 'New-ScheduledTaskPrincipal', 'Register-ScheduledTask')
foreach ($cmdlet in $required) {
    if (-not (Get-Command $cmdlet -ErrorAction SilentlyContinue)) {
        Fail ('本机没有 ' + $cmdlet + '（需要 Windows 8 / Server 2012 及以上）。可以改用启动文件夹里的快捷方式：' +
              (Join-Path ([Environment]::GetFolderPath('Startup')) 'huji-folder-sync.cmd')) 1
    }
}

Write-Host ''
Write-Host '=== 注册 ==='

$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arguments -WorkingDirectory $PSScriptRoot

$triggers = @()
if ($Trigger -eq 'Both' -or $Trigger -eq 'Startup') { $triggers += New-ScheduledTaskTrigger -AtStartup }
if ($Trigger -eq 'Both' -or $Trigger -eq 'Logon') { $triggers += New-ScheduledTaskTrigger -AtLogOn -User $principalUser }

$settings = $null
try {
    $settings = New-ScheduledTaskSettingsSet -Hidden -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
        -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero) `
        -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
} catch {
    $settings = New-ScheduledTaskSettingsSet -Hidden -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
        -StartWhenAvailable -ExecutionTimeLimit ([TimeSpan]::Zero)
}
try {
    $settings.DisallowStartIfOnBatteries = $false
    $settings.StopIfGoingOnBatteries = $false
} catch { }

$principal = New-ScheduledTaskPrincipal -UserId $principalUser -LogonType Interactive -RunLevel Limited

try {
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $triggers -Settings $settings `
        -Principal $principal -Description '合拍投递目录监听：命中敏感规则即隔离' -Force | Out-Null
} catch {
    Fail ('注册失败：' + $_.Exception.Message) 1
}

$task = $null
try { $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue } catch { }
if ($task) {
    Write-Host ('已注册：' + $task.TaskName + '（状态 ' + $task.State + '）')
} else {
    Write-Host ('已注册：' + $TaskName)
}
Write-Host ''
Write-Host '下一步：'
Write-Host '  1. 确认用户环境变量 HUJI_PASSWORD 已设置（否则任务起来会因为没密码直接退出）'
Write-Host '  2. 手动跑一次验证：Start-ScheduledTask -TaskName ''' + $TaskName + ''''
Write-Host '  3. 看日志：' + (Join-Path $LogDir 'huji-folder-sync-*.log')
exit 0
