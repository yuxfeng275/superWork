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
    static var logFile: URL { supportDir.appendingPathComponent("delivery.log") }

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
