import Foundation

// MARK: - 规则文件

/// `shared/privacy/sensitive-rules.json` 的结构。mac 与 Windows 工具共用同一份，
/// 所以字段全部可选：老版本规则缺字段时按空集处理（行为仍然安全：宁可不发）。
struct SensitiveRules: Codable, Equatable {
    struct Thresholds: Codable, Equatable {
        var maxFileSizeMb: Int?
    }

    struct Directories: Codable, Equatable {
        var whitelist: [String]?
        var ignoreNames: [String]?
        var ignorePrefixes: [String]?
        var ignoreSuffixes: [String]?
    }

    var version: Int?
    var updatedAt: String?
    var thresholds: Thresholds?
    var directories: Directories?
    var sensitiveFileKeywords: [String]?
    var sensitiveExtensions: [String]?
    var sensitiveNames: [String]?
    var notes: String?
}

extension SensitiveRules {
    var keywords: [String] { sensitiveFileKeywords ?? [] }
    /// 扩展名统一小写后比较。
    var extensionList: [String] { (sensitiveExtensions ?? []).map { $0.lowercased() } }
    var nameList: [String] { (sensitiveNames ?? []).map { $0.lowercased() } }
    var ignoreNames: [String] { directories?.ignoreNames ?? [] }
    var ignorePrefixes: [String] { directories?.ignorePrefixes ?? [] }
    var ignoreSuffixes: [String] { directories?.ignoreSuffixes ?? [] }
    var maxFileSizeMb: Int? { thresholds?.maxFileSizeMb }

    /// 内置兜底：三个候选位置都没有规则文件时用它，照样挡得住常见敏感文件。
    static let builtin = SensitiveRules(
        version: 0,
        updatedAt: nil,
        thresholds: Thresholds(maxFileSizeMb: 50),
        directories: Directories(
            whitelist: nil,
            ignoreNames: [".DS_Store", "Thumbs.db"],
            ignorePrefixes: ["~$", "."],
            ignoreSuffixes: [".tmp", ".crdownload", ".part", ".download"]
        ),
        sensitiveFileKeywords: ["身份证", "银行卡", "工资", "薪资", "简历", "体检", "密码"],
        sensitiveExtensions: [".pem", ".key", ".p12", ".kdbx", ".env"],
        sensitiveNames: ["id_rsa", "id_ed25519", "credentials", "passwords"],
        notes: "内置兜底规则（没找到规则文件时使用）"
    )
}

/// 规则加载结果，带上来源，方便日志里说清到底用的是哪一份。
struct LoadedSensitiveRules {
    var rules: SensitiveRules
    var source: String
}

enum SensitiveRulesLoader {
    /// 按序找：Bundle 资源 → 仓库相对 `../../shared/privacy/sensitive-rules.json`
    /// → `~/Library/Application Support/HepaiDelivery/sensitive-rules.json` → 内置兜底。
    static func load() -> LoadedSensitiveRules {
        let decoder = JSONDecoder()
        for candidate in candidates {
            guard let data = try? Data(contentsOf: candidate) else { continue }
            guard let rules = try? decoder.decode(SensitiveRules.self, from: data) else {
                Log.shared.warn("规则文件解析失败，跳过：\(candidate.path)")
                continue
            }
            return LoadedSensitiveRules(rules: rules, source: candidate.path)
        }
        Log.shared.warn("没找到敏感规则文件，使用内置兜底规则")
        return LoadedSensitiveRules(rules: .builtin, source: "内置兜底")
    }

    /// 候选路径，按优先级去重排列。
    static var candidates: [URL] {
        var list: [URL] = []
        if let bundled = Bundle.main.url(forResource: "sensitive-rules", withExtension: "json") {
            list.append(bundled)
        }

        // 仓库相对路径：`macapp/HepaiDelivery` 往上两级就是仓库根。
        let relative = "../../shared/privacy/sensitive-rules.json"
        let anchors = [
            Bundle.main.bundleURL,
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath, isDirectory: true),
            URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        ]
        for anchor in anchors {
            list.append(anchor.appendingPathComponent(relative).standardizedFileURL)
        }
        // `#filePath` 指向 Sources/HepaiDelivery，再往上四层才是仓库根。
        list.append(
            URL(fileURLWithPath: #filePath)
                .deletingLastPathComponent()
                .appendingPathComponent("../../../../shared/privacy/sensitive-rules.json")
                .standardizedFileURL
        )
        // 用户自己放的覆盖份。
        list.append(AppPaths.rulesFile)

        var seen: Set<String> = []
        return list.filter { seen.insert($0.path).inserted }
    }
}

// MARK: - 护栏

/// 上传前的本地隐私护栏。命中任一规则 → 本地隔离，**绝不发网络请求**。
struct SensitiveGuard {
    enum Verdict: Equatable {
        case allow
        case block(reason: String)
    }

    let rules: SensitiveRules
    let enabled: Bool
    /// 实际生效的体积上限（MB）：配置 > 0 用配置，否则用规则文件里的阈值。
    let maxFileSizeMb: Int

    init(rules: SensitiveRules, enabled: Bool, configMaxFileSizeMb: Int) {
        self.rules = rules
        self.enabled = enabled
        let ruleLimit = rules.maxFileSizeMb ?? 50
        self.maxFileSizeMb = configMaxFileSizeMb > 0 ? configMaxFileSizeMb : ruleLimit
    }

    /// 判定顺序固定：文件名敏感关键词 → 敏感扩展名 → sensitiveNames
    /// → 体积上限 → 忽略前缀/后缀/垃圾文件名。命中第一个就返回。
    func check(fileName name: String, size: Int64) -> Verdict {
        guard enabled else { return .allow }
        let lower = name.lowercased()

        for keyword in rules.keywords where !keyword.isEmpty {
            if lower.contains(keyword.lowercased()) {
                return .block(reason: "文件名含敏感关键词「\(keyword)」")
            }
        }

        for ext in rules.extensionList where lower.hasSuffix(ext) {
            return .block(reason: "敏感扩展名 \(ext)")
        }

        let stem = (name as NSString).deletingPathExtension.lowercased()
        for sensitive in rules.nameList where lower == sensitive || stem == sensitive {
            return .block(reason: "敏感文件名 \(sensitive)")
        }

        let limitBytes = Int64(maxFileSizeMb) * 1024 * 1024
        if maxFileSizeMb > 0, size > limitBytes {
            return .block(reason: "超过 \(maxFileSizeMb)MB（\(size) 字节）")
        }

        for prefix in rules.ignorePrefixes where name.hasPrefix(prefix) {
            return .block(reason: "忽略前缀 \(prefix)")
        }
        for suffix in rules.ignoreSuffixes where lower.hasSuffix(suffix.lowercased()) {
            return .block(reason: "忽略后缀 \(suffix)")
        }
        for ignored in rules.ignoreNames where name == ignored {
            return .block(reason: "忽略文件 \(ignored)")
        }

        return .allow
    }
}

// MARK: - 项目编码识别

/// 一次编码匹配结果：`code` 是命中的编码，`projectId` 为 nil 表示编码没有映射到项目。
struct ProjectCodeMatch: Equatable {
    var code: String
    var projectId: Int?
}

enum ProjectCode {
    /// 项目编码形如 `HY-101`（2–6 个字母 + `-` + 数字）。
    private static let pattern = try! NSRegularExpression(
        pattern: "[A-Z]{2,6}-[0-9]+",
        options: [.caseInsensitive]
    )

    /// 先看文件名，再看路径；配置里的显式 code→projectId 映射优先（长编码优先，避免子串误判）。
    static func match(fileName: String, path: String, mapping: [String: Int]) -> ProjectCodeMatch? {
        if let hit = match(in: fileName, mapping: mapping) { return hit }
        return match(in: path, mapping: mapping)
    }

    private static func match(in text: String, mapping: [String: Int]) -> ProjectCodeMatch? {
        guard !text.isEmpty else { return nil }
        let lower = text.lowercased()
        for key in mapping.keys.sorted(by: { $0.count > $1.count }) where !key.isEmpty {
            if lower.contains(key.lowercased()) {
                return ProjectCodeMatch(code: key, projectId: mapping[key])
            }
        }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        guard let result = pattern.firstMatch(in: text, options: [], range: range),
              let matchRange = Range(result.range, in: text) else { return nil }
        let code = String(text[matchRange]).uppercased()
        return ProjectCodeMatch(code: code, projectId: mapping[code])
    }
}

// MARK: - 隔离目录

enum Quarantine {
    /// 隔离目录按天分桶；文件是从原目录「移」进来的，不删除。
    static func directory(for config: AppConfig, date: Date = Date()) -> URL {
        AppPaths.quarantineDayDir(config.quarantineDir, date: date)
    }

    /// 同名不覆盖：`a.txt` 已存在就变成 `a (1).txt`。
    static func uniqueTarget(in directory: URL, fileName: String) -> URL {
        let base = (fileName as NSString).deletingPathExtension
        let ext = (fileName as NSString).pathExtension
        var candidate = directory.appendingPathComponent(fileName)
        var index = 1
        while FileManager.default.fileExists(atPath: candidate.path) {
            let name = ext.isEmpty ? "\(base) (\(index))" : "\(base) (\(index)).\(ext)"
            candidate = directory.appendingPathComponent(name)
            index += 1
        }
        return candidate
    }

    /// 隔离目录里累计有多少个文件（配置窗口「已隔离 N 个文件」用）。
    static func fileCount(for config: AppConfig) -> Int {
        let root = AppPaths.quarantineRoot(config.quarantineDir)
        guard let enumerator = FileManager.default.enumerator(
            at: root,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        ) else { return 0 }
        var count = 0
        for case let url as URL in enumerator {
            if (try? url.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true { continue }
            count += 1
        }
        return count
    }
}
