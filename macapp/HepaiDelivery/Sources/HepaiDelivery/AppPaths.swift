import Foundation

/// 本 App 用到的所有路径（配置、队列、日志、状态）。
enum AppPaths {
    /// 配置文件放在用户 home，方便手动编辑 / `open` 打开。
    static var configFile: URL {
        FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent(".hepai-delivery.json")
    }

    /// ~/Library/Application Support/HepaiDelivery/
    static var supportDir: URL {
        let base = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)
            .first
            ?? FileManager.default.homeDirectoryForCurrentUser
                .appendingPathComponent("Library/Application Support", isDirectory: true)
        return base.appendingPathComponent("HepaiDelivery", isDirectory: true)
    }

    static var queueFile: URL { supportDir.appendingPathComponent("queue.json") }
    static var stateFile: URL { supportDir.appendingPathComponent("state.json") }

    /// 监听快照（path → size+mtime）：启动时当基线做对账，补齐停机期间的新文件。
    static var watchSnapshotFile: URL { supportDir.appendingPathComponent("watch-snapshot.json") }
    static var logFile: URL { supportDir.appendingPathComponent("delivery.log") }

    /// 用户自己放的敏感规则覆盖份（优先级低于仓库里的共享规则）。
    static var rulesFile: URL { supportDir.appendingPathComponent("sensitive-rules.json") }

    /// 隔离根目录：配置了 `quarantineDir` 就用它，否则落在 Application Support 下。
    static func quarantineRoot(_ configured: String = "") -> URL {
        let trimmed = configured.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return supportDir.appendingPathComponent("quarantine", isDirectory: true)
        }
        return URL(fileURLWithPath: expand(trimmed), isDirectory: true)
    }

    /// 按天分桶的隔离目录：`.../quarantine/yyyy-MM-dd/`。
    static func quarantineDayDir(_ configured: String = "", date: Date = Date()) -> URL {
        quarantineRoot(configured).appendingPathComponent(dayStamp(date), isDirectory: true)
    }

    static func dayStamp(_ date: Date = Date()) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return formatter.string(from: date)
    }

    @discardableResult
    static func ensureSupportDir() -> Bool {
        do {
            try FileManager.default.createDirectory(at: supportDir, withIntermediateDirectories: true)
            return true
        } catch {
            return false
        }
    }

    /// `~` 展开成用户目录，去掉首尾空白。
    static func expand(_ path: String) -> String {
        let trimmed = path.trimmingCharacters(in: .whitespacesAndNewlines)
        return (trimmed as NSString).expandingTildeInPath
    }
}
