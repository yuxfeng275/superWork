# Windows 投递工具

三个脚本，**零外部依赖**：只要 Windows 自带的 PowerShell 5.1（`powershell.exe`），不用装任何模块、不用管理员权限、不改后端。

| 文件 | 干什么 |
| --- | --- |
| `huji-put.ps1` | 命令行投递器。子命令 `login / file / note / chat / help`，参数与 `bin/huji-put`（bash 版）一致 |
| `huji-folder-sync.ps1` | 目录监听。往目录里丢文件就自动投；命中敏感规则就隔离，不上传 |
| `install-task.ps1` | 把监听注册成计划任务（默认只打印定义，必须显式 `-Apply` 才写系统） |

三者读**同一份**规则文件 `shared/privacy/sensitive-rules.json`（与 mac 端共用），口径见 `docs/privacy-policy.md`。

---

## 三条安装路径，挑一条

### 路径 A：零安装 —— 只用项目邮箱别名（推荐给大多数人）

不装脚本、不配环境变量。外部客户的关键结论，直接发到公司邮箱的项目别名：

```
收件人：inbox+<项目编码>@
```

- 编码用项目详情页 / 项目管理列表里那个编码，**写错就掉进「未归类」而不是进项目**（每周五清一次）。
- 自己给自己转发也行，但发件人必须是公司工作邮箱；个人邮箱不接入。
- 适合：只投「客户原话 / 会议结论 / 一两个附件」的人，一周投几次的量。
- 不适合：桌面上一堆对账单要批量投、或者要「丢进目录就自动走」的场景 → 走 C。

### 路径 B：只跑 CLI（不常驻）

适合偶尔批量投、或者要写进自己的小脚本。

```powershell
# 1) 密码走用户环境变量（不落盘、不进 argv），设一次，之后所有窗口都生效
[Environment]::SetEnvironmentVariable('HUJI_PASSWORD', '你的合拍密码', 'User')
# 关掉重开终端，让新环境变量生效

# 2) 自测：只登录，确认地址和账号对不对
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\win\huji-put.ps1 `
  --base-url http://127.0.0.1:18081 --user zhangsan --project 1 login

# 3) 投文件（可多个；投前会过敏感规则，命中就拒绝且不发请求）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\win\huji-put.ps1 `
  --user zhangsan --project 1 file "$env:USERPROFILE\Desktop\对账单.xlsx" "$env:USERPROFILE\Desktop\验收单.pdf"

# 4) 投一条说明
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\win\huji-put.ps1 `
  --user zhangsan --project 1 note "皇家 PMS 进度" "标品升级差异 4800 个，进度 15%，等张野出分类清单"

# 5) 投聊天导出（「[时间] 姓名: 内容」，解析不出就整段一条）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\win\huji-put.ps1 `
  --user zhangsan --project 1 chat "$env:USERPROFILE\Downloads\群聊导出.txt"
```

参数与 bash 版一一对应：`--base-url`（默认 `$env:HUJI_BASE_URL` 或 `http://127.0.0.1:18081`）、`--user`（默认 `$env:HUJI_USER`）、`--project`（必填，数字）、`--visibility`（`PROJECT` 默认 / `PRIVATE_COMPANY` / `OWNER`）、`--note`（追加到 file 的备注），另有 `--rules` 覆盖规则文件路径。`--help` 打印全部说明。

退出码同 bash：`0` 全成功、`1` 有失败、`2` 用法错误。

### 路径 C：跑常驻监听（丢进目录就自动投）

默认监听 `%USERPROFILE%\Documents\合拍`，事件进来合并 **3 秒**，稳定后过护栏：敏感 → 移到隔离目录 + 写日志；通过 → 调 `huji-put.ps1 file` 上传。

```powershell
# 先手动跑一次，确认能投（Ctrl+C 结束）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\win\huji-folder-sync.ps1 `
  -Project 1 -User zhangsan -Root "$env:USERPROFILE\Documents\合拍"

# 排查用：只扫一遍现有文件就退出，不进监听循环
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\win\huji-folder-sync.ps1 `
  -Project 1 -User zhangsan -Once
```

开机自启（先看定义，再真的注册）：

```powershell
# 只看要注册什么，不写系统
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\win\install-task.ps1 -Project 1 -User zhangsan -DryRun

# 确认无误后真的注册（当前用户身份、隐藏窗口、开机 + 登录触发）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\win\install-task.ps1 -Project 1 -User zhangsan -Apply

# 卸载
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\win\install-task.ps1 -Uninstall -Apply
```

任务里**不写密码**：任务只负责起进程，进程从用户环境变量 `HUJI_PASSWORD` 读。所以路径 B 第 1 步必须先做完，否则任务一起来就因为没密码退出（日志里能看到原因）。

`-DryRun` 打印的就是 `New-ScheduledTaskAction / -Trigger / -SettingsSet / -Principal / Register-ScheduledTask` 的完整参数，可以复制出来手工改。`-Trigger` 可选 `Both`（默认，开机 + 登录）、`Startup`、`Logon`；身份固定当前用户（`Interactive` / `Limited`），不是 SYSTEM。

日志与隔离目录（按天滚动的日志名带日期，自动清理 30 天前的）：

```
%LOCALAPPDATA%\HujiDelivery\logs\huji-folder-sync-yyyyMMdd.log
%LOCALAPPDATA%\HujiDelivery\quarantine\yyyyMMdd\<原文件名>     + 同目录 _quarantine.log 记原因
```

监听目录、日志目录、隔离目录、规则文件都能用参数覆盖（`-Root / -LogDir / -Quarantine / -Rules`）。**别把隔离目录设在监听目录里面**，否则隔离完还会被重新扫到。

---

## 护栏：与 mac 共用同一份规则

规则文件只有一份真源：`shared/privacy/sensitive-rules.json`。mac（`macapp/HepaiDelivery`）、Windows CLI、Windows 监听都读它，服务端脱敏也读它。改规则只改这一个人地方，两端下次启动即生效——**不要在任何一端复制第二份**。

Windows 侧默认按脚本位置找 `..\..\shared\privacy\sensitive-rules.json`；脚本被单独拷到别处时，用 `--rules`（CLI）或 `-Rules`（监听）指向真源，或者设环境变量 `HUJI_RULES`。

判定顺序（命中即停，两端一致）：

| # | 判什么 | 命中后果 |
| --- | --- | --- |
| 1 | `directories.ignoreNames / ignorePrefixes / ignoreSuffixes`（`.DS_Store`、`Thumbs.db`、`~$*`、隐藏/临时文件） | 跳过：不投、不隔离、不报错 |
| 2 | 扩展名在 `sensitiveExtensions`（`.pem .key .p12 .pfx .kdbx .env .ovpn .jks .keystore`） | **拒绝**：CLI 报 REFUSE 并退出 1，监听移到 quarantine |
| 3 | 文件名在 `sensitiveNames`（`id_rsa`、`credentials`、`passwords`…） | 同上 |
| 4 | 文件名含 `sensitiveFileKeywords`（身份证 / 银行卡 / 工资 / 劳动合同 / 简历 / 体检 / 密码 / 社保 / 公积金 / 个税 / 离职 / offer / 背调 / 病例 / 诊断…） | 同上 |
| 5 | 体积超过 `thresholds.maxFileSizeMb`（默认 50MB） | 跳过并说明原因（不隔离——不是敏感，只是太大） |
| 6 | 以上都不命中 | 上传 |

两条实现细节，避免踩坑：

- **拒绝发生在任何 HTTP 请求之前**：CLI 是「读规则 → 判 → 通过才登录+上传」，监听是「判完才起子进程」，所以命中敏感时连 token 都不会去换。
- `note` / `chat` 的正文会再扫一遍 `sensitiveFileKeywords`，但**只告警不拦截**：文本脱敏是服务端的事（保留期 + 脱敏规则），本地拦死只会逼用户绕过整个工具。文件本身仍然是硬拒。

mac 与 Windows 的差异只有两处，都是平台行为不是规则差异：mac 用 `NSKeychain` 存密码，Windows 用用户环境变量；mac 的监听抖动是 0.4s 扫描 + 稳定性判定，Windows 是 3 秒 debounce + 体积稳定判定。

---

## 自测（装完先跑这两条，不碰后端、不写系统）

```powershell
# 1) CLI 能解析、帮助能打出来
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\win\huji-put.ps1 help

# 2) 计划任务定义能生成（不注册）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\win\install-task.ps1 -DryRun -Project 1 -User zhangsan
```

`help` 打到「本地护栏判定顺序」那段就说明参数解析和规则加载链路是通的；`-DryRun` 打到 `=== 计划任务定义（未写系统）===` 并在末尾提示「要真注册，加 -Apply」就说明没有任何东西被写进系统。

## 常见问题

**「没有密码：请设置 $env:HUJI_PASSWORD」** — 环境变量只在新的进程里生效。设完要关掉终端重开（或注销重登）。计划任务那侧还要确认是用当前用户的环境变量，而不是 SYSTEM 的。

**「找不到敏感规则集」** — 脚本被拷到别处了。用 `--rules` / `-Rules` 指到仓库里的 `shared/privacy/sensitive-rules.json`，或设 `HUJI_RULES`。

**投了没反应 / 监听不触发** — 先 `-Once` 手动扫一遍看日志：`Get-Content $env:LOCALAPPDATA\HujiDelivery\logs\huji-folder-sync-*.log -Tail 30`。

**PowerShell 执行策略拦住脚本** — 用 `powershell -NoProfile -ExecutionPolicy Bypass -File …`（上面所有例子都是这个形式），不要改全局策略。

**文件被移走了去哪** — `%LOCALAPPDATA%\HujiDelivery\quarantine\<日期>\`，同目录 `_quarantine.log` 记了命中的是哪条规则。确认不敏感就手工挪回监听目录。
