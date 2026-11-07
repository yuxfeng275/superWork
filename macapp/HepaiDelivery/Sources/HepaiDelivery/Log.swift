import Foundation

/// 极简滚动日志：单文件超过 2MB 就轮转成 delivery.log.1（只留一份备份）。
final class Log {
    static let shared = Log()

    private let queue = DispatchQueue(label: "com.bu.hepai.delivery.log")
    private let maxBytes: UInt64 = 2 * 1024 * 1024
    private let formatter: DateFormatter

    private init() {
        AppPaths.ensureSupportDir()
        formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        formatter.locale = Locale(identifier: "en_US_POSIX")
    }

    func info(_ message: String) { write("INFO ", message) }
    func warn(_ message: String) { write("WARN ", message) }
    func error(_ message: String) { write("ERROR", message) }

    /// 读日志尾部若干行（菜单「打开日志」用不到，但方便排查时打印）。
    func tail(_ lines: Int) -> String {
        queue.sync {
            guard let data = try? Data(contentsOf: AppPaths.logFile),
                  let text = String(data: data, encoding: .utf8) else { return "" }
            let all = text.split(separator: "\n", omittingEmptySubsequences: false)
            return all.suffix(lines).joined(separator: "\n")
        }
    }

    private func write(_ level: String, _ message: String) {
        let line = "\(formatter.string(from: Date())) [\(level)] \(message)\n"
        queue.sync {
            rotateIfNeeded()
            guard let data = line.data(using: .utf8) else { return }
            if let handle = try? FileHandle(forWritingTo: AppPaths.logFile) {
                defer { try? handle.close() }
                _ = try? handle.seekToEnd()
                try? handle.write(contentsOf: data)
            } else {
                try? data.write(to: AppPaths.logFile)
            }
        }
        // 顺带打到 stderr，前台 `swift run` 时可见。
        FileHandle.standardError.write(line.data(using: .utf8) ?? Data())
    }

    private func rotateIfNeeded() {
        let attributes = try? FileManager.default.attributesOfItem(atPath: AppPaths.logFile.path)
        let size = (attributes?[.size] as? NSNumber)?.uint64Value ?? 0
        guard size > maxBytes else { return }
        let backup = AppPaths.logFile.appendingPathExtension("1")
        try? FileManager.default.removeItem(at: backup)
        try? FileManager.default.moveItem(at: AppPaths.logFile, to: backup)
    }
}
