import Foundation
import UserNotifications

/// 命中即弹系统通知。
/// 打包成 .app 后用 UNUserNotificationCenter；`swift run` 没有 bundle 时退化成 osascript，
/// 通知失败只写日志，不影响投递。
enum Notifier {
    private static var hasBundle: Bool { Bundle.main.bundleIdentifier != nil }

    static func requestAuthorization() {
        guard hasBundle else {
            Log.shared.info("非 .app 运行，跳过通知授权（打成 .app 后会正常弹通知）")
            return
        }
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { granted, error in
            if let error {
                Log.shared.warn("通知授权失败：\(error.localizedDescription)")
            } else {
                Log.shared.info("通知授权：\(granted ? "已允许" : "被拒绝")")
            }
        }
    }

    static func delivered(fileName: String, projectId: Int, path: String) {
        post(title: "已投递 \(fileName)", body: "项目 #\(projectId) · \(path)")
    }

    static func failed(fileName: String, reason: String) {
        post(title: "投递失败 \(fileName)", body: reason)
    }

    private static func post(title: String, body: String) {
        guard hasBundle else {
            scriptNotification(title: title, body: body)
            return
        }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request) { error in
            if let error {
                Log.shared.warn("发通知失败：\(error.localizedDescription)")
            }
        }
    }

    /// 命令行直跑时的兜底：走 osascript，不需要 Bundle。
    private static func scriptNotification(title: String, body: String) {
        func escape(_ text: String) -> String {
            text.replacingOccurrences(of: "\\", with: "\\\\")
                .replacingOccurrences(of: "\"", with: "\\\"")
        }
        let script = "display notification \"\(escape(body))\" with title \"\(escape(title))\""
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
        process.arguments = ["-e", script]
        do {
            try process.run()
        } catch {
            Log.shared.warn("osascript 通知失败：\(error.localizedDescription)")
        }
    }
}

