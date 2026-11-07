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

    init(baseUrl: String = "", username: String = "", folders: [FolderConfig] = []) {
        self.baseUrl = baseUrl
        self.username = username
        self.folders = folders
    }

    /// 缺字段时给默认值，保证老配置文件也能读。
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        baseUrl = try container.decodeIfPresent(String.self, forKey: .baseUrl) ?? ""
        username = try container.decodeIfPresent(String.self, forKey: .username) ?? ""
        folders = try container.decodeIfPresent([FolderConfig].self, forKey: .folders) ?? []
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
      "folders": []
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
