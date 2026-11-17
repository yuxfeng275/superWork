# 入站选型：开源调研与四类结论（人读版）

> 更新时间：2026-11-08 · 数据采集：2026-11-08（GitHub REST API，逐仓库 `license` / `pushed_at` / `open_issues_count`）
> 相关：[fact-intake.md](fact-intake.md)（投什么、怎么投）、[privacy-policy.md](privacy-policy.md)（采什么 / 不采什么）、
> [wecom-archive-plan.md](wecom-archive-plan.md)（企微入站与存档）、[fact-sop.md](fact-sop.md)（每周动作）

**一句话结论**：四条入站链路里，只有会议转写（FunASR）和邮件协议栈（`jakarta.mail`）用现成库；
目录监听用操作系统原生能力、企微存档自研、文档解析用已引入的 POI——**没有一个是"为了少写代码"而引入的**。

---

## ⓪ 口径（先看这一节，表才读得懂）

| 项 | 口径 |
| --- | --- |
| 最近 push | `pushed_at`，精确到日；**超过 12 个月没有提交即视为"零维护"** |
| open issues | `open_issues_count`（含 PR，GitHub 口径）；数字只是热度参考，**不单独作为采纳依据** |
| license | `license.spdx_id`；`null` = 仓库**没有 LICENSE 文件** = 默认保留所有权利，**商用直接不可用**；`NOASSERTION` = GitHub 认不出，须逐个看 LICENSE 原文 |
| 采纳门槛 | **要么有明确许可证且有维护，要么干脆自己写**——不引"看起来省两天、实际背上许可证风险或维护债"的库 |
| 硬约束 | 后端只加 Maven 依赖、mac 只走 SwiftPM、Windows 只走 PowerShell 内置能力；**不新增第三方依赖**（本轮所有"不采纳"结论都在守这条） |

---

## ① 本地目录监听（投递箱：mac 菜单栏 / Windows 工具）

| 候选 | 链接 | license | 最近 push | open issues | 结论 |
| --- | --- | --- | --- | --- | --- |
| chokidar | https://github.com/paulmillr/chokidar | MIT | 2026-11-06 | 7 | **不采纳**——Node 生态，产品侧是 Swift / PowerShell，引它等于再背一个 Node 运行时 |
| fswatch | https://github.com/emcrisostomo/fswatch | GPL-3.0 | 2026-07-22 | 46 | **不采纳**——GPL-3.0 传染，且要外挂二进制进程 |
| rclone | https://github.com/rclone/rclone | MIT | 2026-11-07 | 1300 | **不采纳**——它是双向同步器；我们要的是"单向投递 + 命中即隔离"，语义超出需求 |
| syncthing | https://github.com/syncthing/syncthing | MPL-2.0 | 2026-11-05 | 400 | **不采纳**——P2P 同步，牵扯常驻服务与端口，隐私面反而变大 |

**结论**：不引第三方，直接用平台原生——
mac 走 `DispatchSource.makeFileSystemObjectSource`（`macapp/HepaiDelivery/Sources/HepaiDelivery/FolderWatcher.swift:4`），
Windows 走 `.NET FileSystemWatcher`（`tools/win/huji-folder-sync.ps1:6`）。
两边都必须自己实现 debounce（mac 0.4s、Windows 3s），因为隔离判定要看"文件是否已稳定"。

---

## ② 邮件入站（公司工作邮箱 + 项目邮箱别名 `inbox+<项目编码>@`）

| 候选 | 链接 | license | 最近 push | open issues | 结论 |
| --- | --- | --- | --- | --- | --- |
| Jakarta Mail (`jakarta.mail`) | https://github.com/jakartaee/mail-api | EPL-2.0 / GPL-2.0-CPE（GitHub 记为 NOASSERTION，以 LICENSE 原文为准） | 2026-11-02 | 108 | **采纳（已在用）**——`backend/pom.xml:67`，IMAP/SMTP 都没有更省的选择 |
| Apache James | https://github.com/apache/james-project | Apache-2.0 | 2026-11-07 | 25 | **不采纳**——整套邮件服务器；我们要的是"接进公司邮箱"，不是自建 MTA |
| docker-mailserver | https://github.com/docker-mailserver/docker-mailserver | MIT | 2026-11-06 | 88 | **不采纳**——要自己的域名、MX、证书与反垃圾运维，成本远超收益 |
| GreenMail | https://github.com/greenmail-mail-test/greenmail | Apache-2.0 | 2026-11-05 | 66 | **不采纳（本轮）**——只在需要端到端跑真实 IMAP 协议时才有价值；现有测试用桩即可 |
| Nodemailer | https://github.com/nodemailer/nodemailer | NOASSERTION | 2026-11-07 | 2 | **不采纳**——Node 生态，后端是 Java，跨运行时没有收益 |

**结论**：邮件入站只用 `jakarta.mail`，**不自建邮件服务器**；别名路由靠 IMAP 头里的 `To`（`inbox+<编码>@`）解析，
不依赖任何服务商特有的钩子。

---

## ③ 企微会话存档封装（三个社区封装，均不采用）

| 编号 | 候选 | 链接 | license | 最近 push | open issues | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| 候选一 | oiuv/WeWorkFinanceSdk | https://github.com/oiuv/WeWorkFinanceSdk | **无许可证**（`null`） | 2024-12-13 | 0 | **不采纳**——没有 LICENSE 文件等于默认保留所有权利，商用直接踩法律风险 |
| 候选二 | vesio/WeComFinanceSdk_python | https://github.com/vesio/WeComFinanceSdk_python | **无许可证**（`null`） | 2025-08-20 | 0 | **不采纳**——同上，单文件 Python 封装，README 明确只说"学习交流用" |
| 候选三 | Memoyu/wework-finance-sdk-csharp | https://github.com/Memoyu/wework-finance-sdk-csharp | MIT | 2022-09-02 | 0 | **不采纳**——许可证没问题，但**零维护**：最后一次提交停在 2022-09，官方 SDK 已换过接口，接上去等于自己维护一个 fork |

> 口径对齐：[wecom-archive-plan.md](wecom-archive-plan.md) ⑧ 表里的"停在两年前"是概数，**准确日期以本表为准（2022-09-02）**。

为避免误判，另外记录两个**许可证明确、仍在维护**的封装（本轮仍不采用，但属于"再看一眼"级别，不是黑名单）：

| 候选 | 链接 | license | 最近 push | open issues | 结论 |
| --- | --- | --- | --- | --- | --- |
| NICEXAI/WeWorkFinanceSDK（Java 封装 C SDK） | https://github.com/NICEXAI/WeWorkFinanceSDK | Apache-2.0 | 2024-11-25 | 6 | **本轮不采纳**——许可证干净、但两年无提交，且只封了 C SDK 的取数与解密，游标与写入还得自己写；既然后半段照写，不如整段自研 |
| Hanson/WeworkMsg（服务端 + CLI） | https://github.com/Hanson/WeworkMsg | MIT | 2026-04-21 | 1 | **本轮不采纳**——MIT 且在维护，但架构是"独立服务端 + 客户端"，要额外部署一套进程；我们的写入路径已经是 `project_fact` |

**结论**：三个社区封装全部不采用，走**官方 SDK（C / Java）+ 自己写解密、游标、写入**。
理由与取舍原则见 [wecom-archive-plan.md](wecom-archive-plan.md) ⑧；法务边界（个人微信、外部联系人单聊必须先拿授权）见同一文档 ①②。

### ③-补 立项时直查的三个社区封装（2026-11-08）

立项当天直接 `gh api` 查过的三个仓，单独留档（数据采集：2026-11-08，GitHub REST API）：

| 仓库链接 | license | 最近 push | star | open issues | 结论 |
| --- | --- | --- | --- | --- | --- |
| https://github.com/911061873/pyWecomArchive | **NOASSERTION** | 2026-10-28 | 0 | 0 | **仅借思路**——GitHub 认不出许可证（`NOASSERTION`），**无有效许可证**即默认保留所有权利，商用不可用；空有封装结构，取数/解密仍是官方 SDK 的薄壳 |
| https://github.com/liuxiguang/wecom_finance_sdk_python_wrapper | **无许可证**（`null`） | 2026-10-09 | 0 | 0 | **仅借思路**——仓库**没有 LICENSE 文件**，同 ⓪ 口径：默认保留所有权利，一行代码都不能进产品 |
| https://github.com/RockerMJ031/wecom-archive-puller | MIT | 2026-08-27 | 0 | 0 | **仅借思路**——MIT 但**零 star 零维护**（无人用、无 issue、无社区反馈），接进来等于自己当唯一维护者 |

> 结论不变：走腾讯官方 SDK（C/Java），开源仓只借设计思路，不引依赖、不复制代码。

---

## ④ 会议转写与文档解析（录音 → 纪要；附件 → 正文）

| 候选 | 链接 | license | 最近 push | open issues | 结论 |
| --- | --- | --- | --- | --- | --- |
| FunASR（SenseVoiceSmall + VAD + 标点 + CAM++） | https://github.com/modelscope/FunASR | MIT | 2026-11-07 | 47 | **采纳（已在用）**——`asr-worker/transcribe.py:1`，中文会议场景下 VAD / 标点 / 说话人切分一套齐活 |
| openai/whisper | https://github.com/openai/whisper | MIT | 2026-08-31 | 181 | **不采纳**——要自己扛 GPU 与模型分发；与已有 `asr-worker` 契约重复 |
| whisper.cpp | https://github.com/ggml-org/whisper.cpp | MIT | 2026-11-07 | 361 | **不做主路径**——体积小、可离线，留作"没网 / 无 GPU 的兜底"，本轮不引入 |
| Apache Tika | https://github.com/apache/tika | Apache-2.0 | 2026-11-07 | 66 | **本轮不采纳**——格式覆盖最全，但是 JVM 重栈；当前入站格式（Office / 文本）POI 已够 |
| Apache POI | https://github.com/apache/poi | Apache-2.0 | 2026-11-07 | 43 | **采纳（已在用）**——`backend/pom.xml:154`（`poi-ooxml`），Word / Excel 正文抽取 |
| Apache PDFBox | https://github.com/apache/pdfbox | Apache-2.0 | 2026-11-07 | 39 | **未来候选，本轮不引入**——等"PDF 合同进事实"真成为需求再加，避免为假想需求加依赖 |
| Docling | https://github.com/docling-project/docling | MIT | 2026-11-07 | 956 | **不采纳**——Python 大栈 + 模型权重，只为抽文本不值 |
| MarkItDown | https://github.com/microsoft/markitdown | MIT | 2026-11-07 | 814 | **不采纳**——CLI 形态，后端不便内嵌；issue 堆积也在提示成熟度 |

**结论**：转写用 FunASR（已在用），文档解析用已引入的 POI；**Tika / PDFBox 只登记为未来候选**，
真出现新格式再加，不为"以后可能"提前背依赖。

---

## ⑤ 四类结论（一屏看完）

| 类别 | 结论 | 落地位置 |
| --- | --- | --- |
| 本地目录监听 | **不引第三方**，用平台原生（`DispatchSource` / `FileSystemWatcher`），debounce 自己写 | `macapp/HepaiDelivery/Sources/HepaiDelivery/FolderWatcher.swift:4`、`tools/win/huji-folder-sync.ps1:6` |
| 邮件入站 | **只用 `jakarta.mail`，不自建 MTA**；别名靠 IMAP 头的 `To` 解析 | `backend/pom.xml:67` |
| 企微存档 | **三个社区封装全不采用**（两个无许可证、一个 MIT 但零维护），官方 SDK + 自研（另含立项直查三仓：两个无许可证、一个 MIT 零维护） | 见 [wecom-archive-plan.md](wecom-archive-plan.md) ⑧ |
| 会议转写 / 文档解析 | **FunASR + POI 已在用**；whisper 系、Tika、PDFBox 只登记不引入 | `asr-worker/transcribe.py:1`、`backend/pom.xml:154` |

**这张表的意义**：四条入站链路里零新增第三方依赖，所以隐私面（见 [privacy-policy.md](privacy-policy.md)）
可以完全用我们自己的代码解释清楚——没有"这个库顺手还传了什么"的黑盒。

---

## ⑥ 复查方式（数据会旧，方法不会）

```bash
# 单仓库：许可证 / 最近 push / open issues
gh api repos/<owner>/<repo> --jq '"\(.full_name) | \(.license.spdx_id) | \(.pushed_at) | \(.open_issues_count)"'
```

判读顺序固定：**先看 license（`null` 直接淘汰）→ 再看最近 push（>12 个月降级为"自研"）→ 最后看 open issues（只作参考）**。
榜单随时会变，但"许可证 + 维护状态"这两关不会放松。
