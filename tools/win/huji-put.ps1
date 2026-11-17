<#
.SYNOPSIS
    合拍项目事实层命令行投递器（Windows 版，PowerShell 5.1+，零外部依赖）。

.DESCRIPTION
    与仓库 bin/huji-put（bash 版）参数对齐，子命令 login / file / note / chat / help。
    只依赖 .NET BCL（Invoke-RestMethod + System.IO），不装任何模块。

    护栏（与 macapp/HepaiDelivery 同一份规则文件 shared/privacy/sensitive-rules.json）：
    上传前先判，命中即拒绝并把原因打出来，不发任何一个 HTTP 请求。

.PARAMETER 密码
    只认 $env:HUJI_PASSWORD（不落盘、不进 argv）。
    交互终端下没设置会弹 SecureString 提示。

.EXAMPLE
    $env:HUJI_PASSWORD = '…'
    .\huji-put.ps1 --base-url http://127.0.0.1:18081 --user zhangsan --project 1 file C:\Users\me\Desktop\对账单.xlsx

.EXAMPLE
    .\huji-put.ps1 --user zhangsan --project 1 note "皇家 PMS 进度" "标品升级差异 4800 个，进度 15%"
#>

$ErrorActionPreference = 'Stop'

$script:Prog = 'huji-put'
$script:BaseUrl = 'http://127.0.0.1:18081'
if ($env:HUJI_BASE_URL) { $script:BaseUrl = $env:HUJI_BASE_URL }
$script:UserName = ''
if ($env:HUJI_USER) { $script:UserName = $env:HUJI_USER }
$script:ProjectId = ''
$script:Visibility = 'PROJECT'
$script:ExtraNote = ''
$script:RulesPath = ''
$script:Token = ''
$script:Failed = 0
$script:Password = ''

# ---------------------------------------------------------------------------
# 基础工具
# ---------------------------------------------------------------------------

function Write-Err {
    param([string]$Text)
    [Console]::Error.WriteLine($Text)
}

function Fail {
    param([string]$Message, [int]$Code = 1)
    Write-Err ("{0}: {1}" -f $script:Prog, $Message)
    exit $Code
}

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

function Get-ApiUri {
    param([string]$Path)
    return ($script:BaseUrl + $Path)
}

function Get-AuthHeaders {
    if ($script:Token) { return @{ Authorization = 'Bearer ' + $script:Token } }
    return @{}
}

# ---------------------------------------------------------------------------
# 用法
# ---------------------------------------------------------------------------

function Show-Usage {
    $text = @'
huji-put — 把本机文件 / 说明 / 聊天记录投进合拍项目事实层（Windows / PowerShell 5.1+）

用法：
  huji-put.ps1 --base-url <URL> --user <账号> --project <项目ID> <子命令> [参数...]

通用参数：
  --base-url <URL>     后端根地址，默认 $env:HUJI_BASE_URL 或 http://127.0.0.1:18081
  --user <账号>        登录账号，默认 $env:HUJI_USER
  --project <项目ID>   投到哪个项目，必填
  --visibility <级别>  PROJECT（默认）/ PRIVATE_COMPANY / OWNER
  --note <补充说明>    追加到 file 的 note 后面
  --rules <路径>       敏感规则集，默认 <仓库>/shared/privacy/sensitive-rules.json
  -h, --help           就是这页

密码：只认环境变量 HUJI_PASSWORD（不落盘、不进进程参数）。
      交互终端下没设置的话会弹一次输入框（SecureString）。
        $env:HUJI_PASSWORD = '…'; .\huji-put.ps1 --user me --project 1 file C:\tmp\a.pdf

子命令：
  login
      只登录，打印账号 / 项目 / token 前 8 位，用来确认地址与账号对不对。

  file <路径...>
      逐个 POST /api/projects/{id}/facts/files（multipart），
      note 写「来自 huji-put / 主机名」。同一文件重传不新增事实。
      上传前读敏感规则集，命中即拒绝、不发请求。

  note "标题" "正文"
      POST /api/projects/{id}/facts/notes，每次都是一条新事实。正文可省略。

  chat <导出文本文件>
      按行解析「[时间] 姓名: 内容」为 messages 后 POST /api/projects/{id}/facts/chat；
      解析不出（没有一行符合格式）就整段当一条。同一段记录重传不新增。

  help
      就是这页。

退出码：0 全部成功；1 有失败（逐条打印原因）；2 用法错误。

本地护栏判定顺序（命中即停，规则全部来自 sensitive-rules.json）：
  1. ignoreNames / ignorePrefixes / ignoreSuffixes → 跳过（不投、不隔离）
  2. 扩展名命中 sensitiveExtensions               → 拒绝
  3. 文件名命中 sensitiveNames                    → 拒绝
  4. 文件名含 sensitiveFileKeywords               → 拒绝
  5. 体积超过 thresholds.maxFileSizeMb            → 拒绝
  6. 以上都不命中                                 → 上传

note / chat 的正文同样会过一遍关键词（sensitiveFileKeywords），
命中只告警不拦截——文本脱敏由服务端负责，本地拦死会逼用户绕过整个工具。
'@
    Write-Host $text
}

# ---------------------------------------------------------------------------
# 参数解析（保持 --xxx 形式，与 bash 版一字不差）
# ---------------------------------------------------------------------------

$positional = New-Object System.Collections.ArrayList
$argIndex = 0
while ($argIndex -lt $args.Count) {
    $token = [string]$args[$argIndex]
    if ($token -eq '-h' -or $token -eq '--help') {
        Show-Usage
        exit 0
    } elseif ($token -eq '--') {
        $argIndex++
        while ($argIndex -lt $args.Count) {
            [void]$positional.Add([string]$args[$argIndex])
            $argIndex++
        }
    } elseif ($token -eq '--base-url' -or $token -eq '--user' -or $token -eq '--project' -or
              $token -eq '--visibility' -or $token -eq '--note' -or $token -eq '--rules') {
        if ($argIndex + 1 -ge $args.Count) {
            Show-Usage
            Fail ("{0} 缺少值" -f $token) 2
        }
        $value = [string]$args[$argIndex + 1]
        if ($token -eq '--base-url') { $script:BaseUrl = $value }
        elseif ($token -eq '--user') { $script:UserName = $value }
        elseif ($token -eq '--project') { $script:ProjectId = $value }
        elseif ($token -eq '--visibility') { $script:Visibility = $value }
        elseif ($token -eq '--note') { $script:ExtraNote = $value }
        elseif ($token -eq '--rules') { $script:RulesPath = $value }
        $argIndex += 2
    } elseif ($token.StartsWith('-')) {
        Show-Usage
        Fail ("未知参数：{0}" -f $token) 2
    } else {
        [void]$positional.Add($token)
        $argIndex++
    }
}

if ($positional.Count -eq 0) {
    Show-Usage
    exit 2
}

$subCommand = [string]$positional[0]
$subArgs = @()
for ($k = 1; $k -lt $positional.Count; $k++) {
    $subArgs += [string]$positional[$k]
}

if ($subCommand -eq 'help') {
    Show-Usage
    exit 0
}
if ($subCommand -ne 'login' -and $subCommand -ne 'file' -and $subCommand -ne 'note' -and $subCommand -ne 'chat') {
    Show-Usage
    Fail ("未知子命令：{0}（只有 login / file / note / chat / help）" -f $subCommand) 2
}

# ---------------------------------------------------------------------------
# 公共校验
# ---------------------------------------------------------------------------

$script:BaseUrl = $script:BaseUrl.TrimEnd('/')
if (-not $script:BaseUrl) { Fail '缺少 --base-url' 2 }
if (-not $script:UserName) { Fail '缺少 --user（或 $env:HUJI_USER）' 2 }

$script:Visibility = $script:Visibility.ToUpperInvariant()
if ($script:Visibility -ne 'PROJECT' -and $script:Visibility -ne 'PRIVATE_COMPANY' -and $script:Visibility -ne 'OWNER') {
    Fail ("--visibility 只能是 PROJECT / PRIVATE_COMPANY / OWNER，当前值: {0}" -f $script:Visibility) 2
}

if ($subCommand -ne 'login' -or $script:ProjectId) {
    if ($script:ProjectId -notmatch '^[0-9]+$') {
        Fail ("--project 必须是数字项目ID，当前值: {0}" -f $script:ProjectId) 2
    }
}

try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
} catch { }

# ---------------------------------------------------------------------------
# 本地护栏：读规则集（与 mac 共用同一份文件）
# ---------------------------------------------------------------------------

function Resolve-RulesPath {
    if ($script:RulesPath) { return $script:RulesPath }
    if ($env:HUJI_RULES) { return $env:HUJI_RULES }
    $candidate = Join-Path $PSScriptRoot '..\..\shared\privacy\sensitive-rules.json'
    return $candidate
}

function Read-SensitiveRules {
    param([string]$Path)
    $resolved = $Path
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
        Fail ("找不到敏感规则集：{0}`n  可以从仓库复制 shared/privacy/sensitive-rules.json 过来，或用 --rules 指定。" -f $resolved) 1
    }
    $raw = Get-Content -LiteralPath $resolved -Raw -Encoding UTF8
    $rules = $null
    try { $rules = ConvertFrom-Json -InputObject $raw } catch {
        Fail ("敏感规则集不是合法 JSON：{0}" -f $resolved) 1
    }
    return $rules
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

# 判定顺序 = 本文件 help 里写的那 6 步，与 mac 端一致
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
            return New-Verdict 'SENSITIVE' ('扩展名 ' + $extension + ' 属敏感类型（规则 sensitiveExtensions: ' + $e + '）')
        }
    }
    foreach ($n in (Get-RuleArray $Rules 'sensitiveNames')) {
        if ([string]::IsNullOrEmpty([string]$n)) { continue }
        if ($stem.Equals([string]$n, [StringComparison]::OrdinalIgnoreCase)) {
            return New-Verdict 'SENSITIVE' ('文件名 "' + $fileName + '" 命中敏感名单（rules sensitiveNames: ' + $n + '）')
        }
        if ($fileName.Equals([string]$n, [StringComparison]::OrdinalIgnoreCase)) {
            return New-Verdict 'SENSITIVE' ('文件名 "' + $fileName + '" 命中敏感名单（rules sensitiveNames: ' + $n + '）')
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

function Get-TextWarnings {
    param($Rules, [string]$Text)
    $hits = @()
    foreach ($n in (Get-RuleArray $Rules 'sensitiveFileKeywords')) {
        if ([string]::IsNullOrEmpty([string]$n)) { continue }
        if ($Text -and $Text.IndexOf([string]$n, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            $hits += [string]$n
        }
    }
    return $hits
}

# ---------------------------------------------------------------------------
# 密码 + 登录
# ---------------------------------------------------------------------------

function Read-Password {
    if ($env:HUJI_PASSWORD) { return $env:HUJI_PASSWORD }
    if (-not [Console]::IsInputRedirected) {
        $secure = Read-Host -AsSecureString ('密码（' + $script:UserName + '）')
        $bstr = [System.IntPtr]::Zero
        try {
            $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
            $plain = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
        } finally {
            if ($bstr -ne [System.IntPtr]::Zero) {
                [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
            }
        }
        if (-not $plain) { Fail '密码为空' 1 }
        return $plain
    }
    Fail '没有密码：请设置 $env:HUJI_PASSWORD 再跑（不落盘、不进 argv）' 1
}

function Get-ErrorText {
    param($ErrorRecord)
    $text = ''
    if ($ErrorRecord.ErrorDetails -and $ErrorRecord.ErrorDetails.Message) {
        $text = $ErrorRecord.ErrorDetails.Message
    }
    if (-not $text) {
        $response = $null
        try { $response = $ErrorRecord.Exception.Response } catch { }
        if ($response) {
            try {
                if ($response.GetResponseStream) {
                    $stream = $response.GetResponseStream()
                    if ($stream) {
                        $reader = New-Object System.IO.StreamReader($stream)
                        $text = $reader.ReadToEnd()
                        $reader.Dispose()
                    }
                } elseif ($response.Content) {
                    $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                }
            } catch { }
        }
    }
    if ($text) {
        $parsed = $null
        try { $parsed = ConvertFrom-Json -InputObject $text } catch { }
        if ($parsed) {
            $message = Get-JsonProp $parsed 'message'
            if ($message) { return [string]$message }
        }
        return $text.Trim()
    }
    return $ErrorRecord.Exception.Message
}

function Send-Json {
    param([string]$Path, $Payload)
    $json = ConvertTo-Json -InputObject $Payload -Depth 8 -Compress
    $bytes = (New-Object System.Text.UTF8Encoding($false)).GetBytes($json)
    return Invoke-RestMethod -Uri (Get-ApiUri $Path) -Method Post `
        -Headers (Get-AuthHeaders) `
        -ContentType 'application/json; charset=utf-8' `
        -Body $bytes -TimeoutSec 120
}

function Invoke-Login {
    $payload = [ordered]@{ username = $script:UserName; password = $script:Password }
    $response = $null
    try {
        $response = Send-Json '/api/auth/login' $payload
    } catch {
        Fail ('登录请求失败：' + (Get-ErrorText $_)) 1
    }
    $data = Get-JsonProp $response 'data'
    $token = Get-JsonProp $data 'token'
    if (-not $token) {
        $message = Get-JsonProp $response 'message'
        if (-not $message) { $message = '登录失败（响应里没有 data.token）' }
        Fail ('登录失败：' + $message) 1
    }
    $script:Token = [string]$token
}

# ---------------------------------------------------------------------------
# 结果汇报
# ---------------------------------------------------------------------------

function Report-Result {
    param([string]$Label, $Response)
    $code = Get-JsonProp $Response 'code'
    if (('' + $code) -eq '200') {
        $data = Get-JsonProp $Response 'data'
        $title = Get-JsonProp $data 'title'
        if (-not $title) { $title = Get-JsonProp $data 'sourceType' }
        if (-not $title) { $title = 'ok' }
        Write-Host ("  ok    {0} → {1}" -f $Label, $title)
        return $true
    }
    $message = Get-JsonProp $Response 'message'
    if (-not $message) { $message = ('code=' + $code) }
    Report-Failure $Label ([string]$message)
    return $false
}

function Report-Failure {
    param([string]$Label, [string]$Message)
    Write-Err ("  FAIL  {0} → {1}" -f $Label, $Message)
    $script:Failed++
}

# ---------------------------------------------------------------------------
# multipart 组装（PS 5.1 没有 -Form，手工拼，字节流分块拷贝）
# ---------------------------------------------------------------------------

function New-MultipartBody {
    param([string]$FilePath, [string]$Note, [string]$Visibility)
    $boundary = '----HujiPut' + [System.Guid]::NewGuid().ToString('N')
    $fileName = [System.IO.Path]::GetFileName($FilePath)
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    $stream = New-Object System.IO.MemoryStream
    $header = "--$boundary`r`nContent-Disposition: form-data; name=`"file`"; filename=`"$fileName`"`r`nContent-Type: application/octet-stream`r`n`r`n"
    $headerBytes = $utf8.GetBytes($header)
    $stream.Write($headerBytes, 0, $headerBytes.Length)
    # 不叫 $input —— 那是 PowerShell 的自动变量
    $source = [System.IO.File]::Open($FilePath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    try {
        $source.CopyTo($stream)
    } finally {
        $source.Dispose()
    }
    $tail = "`r`n--$boundary`r`nContent-Disposition: form-data; name=`"note`"`r`n`r`n$Note`r`n--$boundary`r`nContent-Disposition: form-data; name=`"visibility`"`r`n`r`n$Visibility`r`n--$boundary--`r`n"
    $tailBytes = $utf8.GetBytes($tail)
    $stream.Write($tailBytes, 0, $tailBytes.Length)
    $bytes = $stream.ToArray()
    $stream.Dispose()
    return [pscustomobject]@{ Boundary = $boundary; Bytes = $bytes }
}

function Send-File {
    param([string]$FilePath, [string]$Note, [string]$Visibility)
    $part = New-MultipartBody -FilePath $FilePath -Note $Note -Visibility $Visibility
    $contentType = 'multipart/form-data; boundary=' + $part.Boundary
    return Invoke-RestMethod -Uri (Get-ApiUri ("/api/projects/$($script:ProjectId)/facts/files")) -Method Post `
        -Headers (Get-AuthHeaders) -ContentType $contentType -Body $part.Bytes -TimeoutSec 600
}

# ---------------------------------------------------------------------------
# 子命令
# ---------------------------------------------------------------------------

function Invoke-CmdFile {
    param([string[]]$Paths)
    # 守卫已在上传前（登录之前）跑完，到这里只剩「通过护栏」的路径
    if ($Paths.Count -eq 0) { return }

    $machine = $env:COMPUTERNAME
    if (-not $machine) { $machine = [System.Environment]::MachineName }
    $note = '来自 huji-put / ' + $machine
    if ($script:ExtraNote) { $note = $note + ' · ' + $script:ExtraNote }

    foreach ($path in $Paths) {
        $label = [System.IO.Path]::GetFileName($path)
        try {
            $response = Send-File -FilePath $path -Note $note -Visibility $script:Visibility
            [void](Report-Result $label $response)
        } catch {
            Report-Failure $label (Get-ErrorText $_)
        }
    }
}

function Invoke-CmdNote {
    param([string[]]$Parts)
    if ($Parts.Count -lt 1) { Fail 'note 子命令需要："标题" ["正文"]' 2 }
    $title = [string]$Parts[0]
    $body = ''
    if ($Parts.Count -ge 2) { $body = [string]$Parts[1] }
    if (-not $title) { Fail '标题不能为空' 2 }

    $warnings = Get-TextWarnings -Rules $script:Rules -Text ($title + ' ' + $body)
    if ($warnings.Count -gt 0) {
        Write-Err ('  警告  正文含敏感关键词：' + ($warnings -join '、') + '（文本脱敏由服务端负责；能改成文件请改走 file）')
    }

    $payload = [ordered]@{ title = $title; body = $body; visibility = $script:Visibility }
    try {
        $response = Send-Json ("/api/projects/$($script:ProjectId)/facts/notes") $payload
        [void](Report-Result ('note: ' + $title) $response)
    } catch {
        Report-Failure ('note: ' + $title) (Get-ErrorText $_)
    }
}

function Convert-ChatLines {
    param([string]$FilePath)
    $lines = @(Get-Content -LiteralPath $FilePath -Encoding UTF8)
    $pattern = '^\[(?<time>[^\]]+)\]\s*(?<sender>[^:：]+)[:：]\s*(?<text>.*)$'
    $matched = $false
    foreach ($line in $lines) {
        if ($line -match $pattern) { $matched = $true; break }
    }
    $messages = New-Object System.Collections.ArrayList

    if ($matched) {
        $curTime = ''
        $curSender = ''
        $curText = ''
        $have = $false
        foreach ($line in $lines) {
            $text = [string]$line
            $m = [regex]::Match($text, $pattern)
            if ($m.Success) {
                if ($have) {
                    [void]$messages.Add([ordered]@{ time = $curTime; sender = $curSender; text = $curText })
                }
                $curTime = $m.Groups['time'].Value
                $curSender = $m.Groups['sender'].Value
                $curText = $m.Groups['text'].Value
                $have = $true
            } elseif ($have -and $text.Trim().Length -gt 0) {
                $curText = $curText + [Environment]::NewLine + $text
            }
        }
        if ($have) {
            [void]$messages.Add([ordered]@{ time = $curTime; sender = $curSender; text = $curText })
        }
    } else {
        # 解析不出格式：整段当一条，sender 留「导出文本」
        $whole = Get-Content -LiteralPath $FilePath -Raw -Encoding UTF8
        [void]$messages.Add([ordered]@{ time = ''; sender = '导出文本'; text = $whole })
    }
    return $messages
}

function Invoke-CmdChat {
    param([string[]]$Parts)
    if ($Parts.Count -lt 1) { Fail 'chat 子命令需要一个导出文本文件' 2 }
    $file = [string]$Parts[0]
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { Fail ('找不到文件：' + $file) 1 }
    # 护栏已在上传前（登录之前）跑过，敏感文件走不到这里

    $messages = Convert-ChatLines -FilePath $file
    if ($messages.Count -eq 0) { Fail ('聊天记录解析后是空的：' + $file) 1 }

    $raw = Get-Content -LiteralPath $file -Raw -Encoding UTF8
    $warnings = Get-TextWarnings -Rules $script:Rules -Text $raw
    if ($warnings.Count -gt 0) {
        Write-Err ('  警告  聊天正文含敏感关键词：' + ($warnings -join '、') + '（文本脱敏由服务端负责）')
    }

    $payload = [ordered]@{ messages = @($messages); visibility = $script:Visibility }
    $label = 'chat: ' + [System.IO.Path]::GetFileName($file) + '（' + $messages.Count + ' 条）'
    try {
        $response = Send-Json ("/api/projects/$($script:ProjectId)/facts/chat") $payload
        [void](Report-Result $label $response)
    } catch {
        Report-Failure $label (Get-ErrorText $_)
    }
}

# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

$script:RulesPathResolved = Resolve-RulesPath
$script:Rules = Read-SensitiveRules -Path $script:RulesPathResolved

# --- 上传前护栏：先判，再决定要不要登录 -----------------------------------
# 这里的顺序保证「命中敏感 = 一个 HTTP 请求都不发」：判完、过滤完才去换 token。
$script:UploadQueue = @()
if ($subCommand -eq 'file' -or $subCommand -eq 'chat') {
    $targets = @()
    if ($subCommand -eq 'file') {
        if ($subArgs.Count -eq 0) { Fail 'file 子命令至少要一个文件路径' 2 }
        $targets = $subArgs
    } else {
        if ($subArgs.Count -lt 1) { Fail 'chat 子命令需要一个导出文本文件' 2 }
        $targets = @([string]$subArgs[0])
    }

    foreach ($target in $targets) {
        if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
            Report-Failure $target '不是文件'
            continue
        }
        $verdict = Get-SensitiveVerdict -Rules $script:Rules -Path $target
        if ($verdict.Kind -eq 'IGNORE') {
            Write-Host ("  skip  {0} → {1}" -f $target, $verdict.Reason)
            continue
        }
        if ($verdict.Kind -eq 'SENSITIVE') {
            Write-Err ("  REFUSE {0} → 本地敏感规则命中：{1}" -f $target, $verdict.Reason)
            Write-Err ('         未发送任何请求（连登录都没做）。规则来源：' + $script:RulesPathResolved)
            $script:Failed++
            continue
        }
        if ($verdict.Kind -eq 'TOOLARGE') {
            Report-Failure $target $verdict.Reason
            continue
        }
        $script:UploadQueue += $target
    }

    if ($script:UploadQueue.Count -eq 0) {
        Write-Err ''
        Write-Err ("{0}: 没有可投递的条目（{1} 条被本地护栏拦下或跳过）" -f $script:Prog, $script:Failed)
        exit 1
    }
}

$script:Password = Read-Password
Invoke-Login

switch ($subCommand) {
    'login' {
        $short = ''
        if ($script:Token.Length -gt 8) { $short = $script:Token.Substring(0, 8) + '…' } else { $short = $script:Token }
        Write-Host ('  ok    login → ' + $script:UserName + ' @ ' + $script:BaseUrl + '（token ' + $short + '）')
        if ($script:ProjectId) { Write-Host ('        --project ' + $script:ProjectId + '，可见性 ' + $script:Visibility) }
        Write-Host ('        规则集 ' + $script:RulesPathResolved)
    }
    'file' { Invoke-CmdFile -Paths $script:UploadQueue }
    'note' { Invoke-CmdNote -Parts $subArgs }
    'chat' { Invoke-CmdChat -Parts $subArgs }
}

if ($script:Failed -gt 0) {
    Write-Err ('')
    Write-Err ("{0}: {1} 条失败" -f $script:Prog, $script:Failed)
    exit 1
}
Write-Host ('')
Write-Host ("{0}: 全部投递成功（project {1}）" -f $script:Prog, $script:ProjectId)
exit 0
