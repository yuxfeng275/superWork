# 合拍·投递（HepaiDelivery）

macOS 原生菜单栏小工具：监听本机若干目录，把落到目录里的新文件自动投到合拍项目事实层。
纯 Swift + AppKit + SwiftPM，无第三方依赖、无 Electron/Tauri。

- 菜单栏常驻（`NSStatusItem`），没有 Dock 图标
- 新文件「稳定」后自动 multipart 上传（两次 `stat` 大小一致才投，防止投半个文件）
- 失败队列落盘，每 60 秒重试一次；菜单里能看到「待投 N · 今日已投 M」
- 命中弹系统通知（`UserNotifications`）
- 密码只进钥匙串，**不写明文到磁盘**

## 目录结构

```
macapp/HepaiDelivery/
├── Package.swift                     # SwiftPM，platforms: macOS 13，target HepaiDelivery
├── Sources/HepaiDelivery/
│   ├── main.swift                    # NSApplication + accessory 激活策略（无 Dock 图标）
│   ├── AppDelegate.swift             # 启动、菜单回调、配置窗口
│   ├── StatusMenu.swift              # NSStatusItem 菜单
│   ├── ConfigWindow.swift            # 首次运行 / 「配置窗口…」的配置面板
│   ├── DeliveryEngine.swift          # 监听→稳定性→入队→上传→重试→通知
│   ├── FolderWatcher.swift           # DispatchSource.makeFileSystemObjectSource 递归监听
│   ├── DeliveryAPI.swift             # 登录拿 token + multipart 上传（401 重登一次）
│   ├── QueueStore.swift              # queue.json / state.json（待投队列、今日计数）
│   ├── Config.swift                  # ~/.hepai-delivery.json
│   ├── Keychain.swift                # 密码存钥匙串（service com.bu.hepai.delivery）
│   ├── Notifier.swift                # UserNotifications
│   ├── Log.swift                     # delivery.log，>2MB 轮转
│   └── AppPaths.swift                # 各路径
├── scripts/
│   ├── make-app.sh                   # 打成 合拍投递.app + ad-hoc 签名
│   ├── install-launch-agent.sh       # 安装/卸载 launchd 开机自启
│   └── Info.plist                    # LSUIElement = true
└── README.md
```

## 编译

需要 macOS 13+ 与 Xcode 命令行工具（`xcode-select --install`；`xcode-select -p` 应指向
`/Library/Developer/CommandLineTools` 或 Xcode 的 Developer 目录）。

```bash
cd macapp/HepaiDelivery
swift build -c release          # 产物：.build/release/HepaiDelivery
swift run -c release            # 前台跑一遍看日志（无 Dock 图标）
```

## 配置

首次运行会生成 `~/.hepai-delivery.json`（`0600`）并弹出配置窗口。

```json
{
  "baseUrl": "https://hepai.example.com",
  "username": "your.name",
  "folders": [
    { "path": "~/Desktop/投递箱", "projectId": 12, "visibility": "PROJECT" }
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| `baseUrl` | 合拍后端根地址，不带结尾 `/`；请求走 `{baseUrl}/api/...` |
| `username` | 登录取 token 的账号，也是钥匙串条目的 account 后缀 |
| `folders[].path` | 要监听的目录，支持 `~`；递归监听子目录 |
| `folders[].projectId` | 投到哪个项目 |
| `folders[].visibility` | `PROJECT` / `PRIVATE_COMPANY` / `OWNER`（对应事实层 visibility，缺省 `PROJECT`） |

**密码**不在 JSON 里：存在登录钥匙串 `service = com.bu.hepai.delivery`，
`account = password:<username>`。配置窗口里填一次即可；
也可以用「钥匙串访问」手动改。

其它运行期文件：

| 路径 | 用途 |
| --- | --- |
| `~/.hepai-delivery.json` | 配置（不含密码） |
| `~/Library/Application Support/HepaiDelivery/queue.json` | 失败队列，重启后继续重试 |
| `~/Library/Application Support/HepaiDelivery/state.json` | 今日已投 / 累计已投计数 |
| `~/Library/Application Support/HepaiDelivery/delivery.log` | 日志，超过 2MB 轮转为 `delivery.log.1` |

## 接口

1. **登录**：`POST {baseUrl}/api/auth/login`，body `{"username": "...", "password": "..."}`，
   取 `data.token`，只缓存在内存里；上传返回 401 时重登一次再重试，仍然 401 才算失败。
2. **投递**：`POST {baseUrl}/api/projects/{projectId}/facts/files`，
   `Authorization: Bearer <token>`，`multipart/form-data`：

   | 字段 | 内容 |
   | --- | --- |
   | `file` | 文件本体（文件名保留原样） |
   | `note` | `来自 合拍·投递 / <主机名>` |
   | `visibility` | 目录配置里的 visibility |

新文件判定：`DispatchSource` 收到目录 write/vnode 事件后 0.4s 合并重扫，
发现新文件记下大小，**至少 1.2s 后再次 `stat` 大小一致**才入队上传（同时跳过 `.DS_Store`、
隐藏文件、`.part/.crdownload/.swp/~` 等未完成文件）。

## 打成 .app（菜单栏 App）

```bash
cd macapp/HepaiDelivery
./scripts/make-app.sh          # = swift build -c release + 组装 + ad-hoc 签名
open dist/合拍投递.app
```

脚本做的事（也可以手工做）：

```
合拍投递.app/
└── Contents/
    ├── Info.plist            # 必须有 LSUIElement = true
    ├── PkgInfo               # APPL????
    ├── MacOS/
    │   └── HepaiDelivery     # .build/release/HepaiDelivery
    └── Resources/
```

`Info.plist` 关键项（完整模板见 `scripts/Info.plist`）：

```xml
<key>CFBundleExecutable</key><string>HepaiDelivery</string>
<key>CFBundleIdentifier</key><string>com.bu.hepai.delivery</string>
<key>LSUIElement</key><true/>          <!-- 菜单栏常驻，不显示 Dock 图标 -->
<key>LSMinimumSystemVersion</key><string>13.0</string>
```

**ad-hoc 签名**（本机自用足够；分发才需要开发者证书）：

```bash
codesign --force --deep -s - "dist/合拍投递.app"
codesign --verify --verbose=2 "dist/合拍投递.app"     # 验证
```

> 打包后通知走 `UNUserNotificationCenter`，第一次投递前系统会请求通知权限；
> `swift run` 直接跑没有 bundle，会退化成 `osascript` 通知。

## 开机自启（launchd）

```bash
./scripts/install-launch-agent.sh              # 写 ~/Library/LaunchAgents/com.bu.hepai.delivery.plist 并 load
./scripts/install-launch-agent.sh --uninstall  # 卸载
```

等价的手工 plist（`~/Library/LaunchAgents/com.bu.hepai.delivery.plist`，`RunAtLoad=true` +
`KeepAlive=true`，`ProgramArguments` 填 app 里的可执行文件绝对路径）：

```xml
<key>Label</key><string>com.bu.hepai.delivery</string>
<key>ProgramArguments</key>
<array><string>/Users/you/macapp/HepaiDelivery/dist/合拍投递.app/Contents/MacOS/HepaiDelivery</string></array>
<key>RunAtLoad</key><true/>
<key>KeepAlive</key><true/>
```

```bash
launchctl bootstrap "gui/$(id -u)" ~/Library/LaunchAgents/com.bu.hepai.delivery.plist
launchctl bootout   "gui/$(id -u)/com.bu.hepai.delivery"
```

登录项里也可以直接拖 `.app`（系统设置 → 通用 → 登录项），二选一。

## 首次配置步骤

1. `swift build -c release`（或 `./scripts/make-app.sh` 打成 app）
2. 运行：`open dist/合拍投递.app`，或 `swift run -c release`
3. 菜单栏出现「合拍·投递」；首次运行自动弹配置窗口，填：
   - 服务器地址（如 `https://hepai.example.com`）
   - 用户名
   - 密码（存钥匙串，不写盘）
   - 「＋ 添加目录」逐个加监听目录，填 `projectId`、选 `visibility`
4. 点「保存」→ 配置写进 `~/.hepai-delivery.json`，监听立即生效
5. 往目录里丢一个文件，几秒后应看到通知；菜单里「今日已投」+1
6. 网络/后端挂了也不丢：进 `queue.json`，每 60 秒重试；菜单「立即重试」可手动催

菜单项：`待投 N · 今日已投 M` / `暂停监听`（或 `恢复监听`）/ `立即重试` /
`打开配置（编辑 .hepai-delivery.json）` / `配置窗口…` / `重载配置` / `打开日志` / `退出`。

「打开配置」用 `NSWorkspace.open` 把 `~/.hepai-delivery.json` 交给默认编辑器，改完点
菜单「重载配置」即可（不用重启）。

## 排障

```bash
tail -f ~/Library/Application\ Support/HepaiDelivery/delivery.log
cat ~/Library/Application\ Support/HepaiDelivery/queue.json
```

- 「Keychain 里没有 xxx 的密码」：重新在配置窗口填密码保存，或「钥匙串访问」里确认
  `com.bu.hepai.delivery` 条目存在
- 投递一直 401：token 过期且重登失败 → 检查用户名/密码、`baseUrl`
- 投递 403/404：`projectId` 不存在或该账号对该项目无权限
- 目录没反应：确认目录存在且被本进程可读（macOS 可能要求给终端/App 授权「完全磁盘访问权限」）
