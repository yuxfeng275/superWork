import Foundation

/// 一个被监听目录 = 一个项目 + 一个可见范围。
struct FolderConfig: Codable, Equatable {
    var path: String
    var projectId: Int
    var visibility: String

    init(path: String, projectId: Int, visibility: String = "PROJECT") {
        self.path = path
        self.projectId = projectId
        self.visibility = visibility
    }
}

/// ~/.hepai-delivery.json 的结构。**不含密码**：密码只在 Keychain。
struct AppConfig: Codable, Equatable {
    var baseUrl: String
    var username: String
    var folders: [FolderConfig]

    /// 本地隐私护栏开关（默认开）。关掉等于敏感文件也照传，只建议排障时临时关。
    var sensitiveFilterEnabled: Bool
    /// 隔离目录；空 = `~/Library/Application Support/HepaiDelivery/quarantine/yyyy-MM-dd/`。
    var quarantineDir: String
    /// 每天几点发那条汇总（0–23），默认 18 点。
    var dailySummaryHour: Int
    /// 单文件体积上限（MB）；0 = 用规则文件里的 `thresholds.maxFileSizeMb`。
    var maxFileSizeMb: Int
    /// 项目编码 → projectId，例如 `"HY-101": 12`。
    var projectCodes: [String: Int]
    /// 编码匹配不到映射时的默认行为：`pending`（待归类，不上传）/ `folderDefault`（用目录的 projectId）。
    var unmatchedProjectBehavior: String

    /// 默认监听目录；projectId 0 = 待归类，靠文件名里的项目编码自动认。
    static let defaultFolders = [
        FolderConfig(path: "~/Documents/合拍", projectId: 0, visibility: "PROJECT"),
        FolderConfig(path: "~/Downloads/合拍", projectId: 0, visibility: "PROJECT")
    ]

    init(
        baseUrl: String = "",
        username: String = "",
        folders: [FolderConfig] = AppConfig.defaultFolders,
        sensitiveFilterEnabled: Bool = true,
        quarantineDir: String = "",
        dailySummaryHour: Int = 18,
        maxFileSizeMb: Int = 50,
        projectCodes: [String: Int] = [:],
        unmatchedProjectBehavior: String = "pending"
    ) {
        self.baseUrl = baseUrl
        self.username = username
        self.folders = folders
        self.sensitiveFilterEnabled = sensitiveFilterEnabled
        self.quarantineDir = quarantineDir
        self.dailySummaryHour = dailySummaryHour
        self.maxFileSizeMb = maxFileSizeMb
        self.projectCodes = projectCodes
        self.unmatchedProjectBehavior = unmatchedProjectBehavior
    }

    /// 缺字段时给默认值，保证老配置文件也能读。
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        baseUrl = try container.decodeIfPresent(String.self, forKey: .baseUrl) ?? ""
        username = try container.decodeIfPresent(String.self, forKey: .username) ?? ""
        folders = try container.decodeIfPresent([FolderConfig].self, forKey: .folders) ?? AppConfig.defaultFolders
        sensitiveFilterEnabled = try container.decodeIfPresent(Bool.self, forKey: .sensitiveFilterEnabled) ?? true
        quarantineDir = try container.decodeIfPresent(String.self, forKey: .quarantineDir) ?? ""
        dailySummaryHour = try container.decodeIfPresent(Int.self, forKey: .dailySummaryHour) ?? 18
        maxFileSizeMb = try container.decodeIfPresent(Int.self, forKey: .maxFileSizeMb) ?? 50
        projectCodes = try container.decodeIfPresent([String: Int].self, forKey: .projectCodes) ?? [:]
        unmatchedProjectBehavior = try container.decodeIfPresent(String.self, forKey: .unmatchedProjectBehavior) ?? "pending"
    }

    /// 编码没映射到项目时是否走「待归类」（默认是；只有显式配 folderDefault 才用目录的 projectId）。
    var unresolvedBehaviorIsPending: Bool {
        unmatchedProjectBehavior.lowercased() != "folderdefault"
    }

    var isUsable: Bool {
        !baseUrl.trimmingCharacters(in: .whitespaces).isEmpty
            && !username.trimmingCharacters(in: .whitespaces).isEmpty
            && !folders.isEmpty
    }
}

enum ConfigStore {
    /// 首跑模板：故意留空，这样 `isUsable == false`，会自动弹配置窗口。
    /// 字段含义见 README。
    static let template = """
    {
      "baseUrl": "",
      "username": "",
      "folders": [
        { "path": "~/Documents/合拍", "projectId": 0, "visibility": "PROJECT" },
        { "path": "~/Downloads/合拍", "projectId": 0, "visibility": "PROJECT" }
      ],
      "sensitiveFilterEnabled": true,
      "quarantineDir": "",
      "dailySummaryHour": 18,
      "maxFileSizeMb": 50,
      "projectCodes": {},
      "unmatchedProjectBehavior": "pending"
    }

    """

    /// 读配置；文件不存在或解析失败返回 nil。
    static func load() -> AppConfig? {
        guard let data = try? Data(contentsOf: AppPaths.configFile) else { return nil }
        do {
            return try JSONDecoder().decode(AppConfig.self, from: data)
        } catch {
            Log.shared.error("配置文件解析失败：\(error.localizedDescription)")
            return nil
        }
    }

    /// 保存配置（保持人类可读 + 尾换行）。
    static func save(_ config: AppConfig) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        var data = try encoder.encode(config)
        data.append(0x0A)
        try data.write(to: AppPaths.configFile, options: .atomic)
        // 只给当前用户读写。
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: AppPaths.configFile.path)
    }

    /// 首次运行：落一份模板配置，返回 nil 表示需要用户先填。
    @discardableResult
    static func ensureConfigFile() -> AppConfig? {
        if let config = load() { return config }
        if !FileManager.default.fileExists(atPath: AppPaths.configFile.path) {
            try? template.data(using: .utf8)?.write(to: AppPaths.configFile, options: .atomic)
            Log.shared.info("已生成配置模板 \(AppPaths.configFile.path)")
        }
        return load()
    }
}
