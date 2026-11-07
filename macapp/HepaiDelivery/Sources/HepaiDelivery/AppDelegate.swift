import AppKit

final class AppDelegate: NSObject, NSApplicationDelegate {
    private var engine: DeliveryEngine?
    private var menu: StatusMenuController?
    private var configWindow: ConfigWindowController?

    func applicationDidFinishLaunching(_ notification: Notification) {
        // 无 Dock 图标（Info.plist 里也有 LSUIElement=true，这里保证 `swift run` 也一致）。
        NSApp.setActivationPolicy(.accessory)

        let config = ConfigStore.ensureConfigFile() ?? AppConfig()
        let engine = DeliveryEngine(config: config) { username in
            Keychain.password(username: username)
        }
        self.engine = engine

        let menu = StatusMenuController(engine: engine)
        self.menu = menu

        menu.onOpenConfigFile = { AppDelegate.openConfigFile() }
        menu.onOpenConfigWindow = { [weak self] in self?.showConfigWindow(firstRun: false) }
        menu.onOpenLog = { AppDelegate.openLog() }
        menu.onReloadConfig = { [weak self] in self?.reloadConfig() }
        menu.onQuit = { NSApp.terminate(nil) }

        engine.statusChanged = { [weak self] in self?.menu?.refresh() }
        engine.start()

        if !config.isUsable {
            Log.shared.warn("配置不完整（baseUrl/用户名/目录），弹出配置窗口")
            showConfigWindow(firstRun: true)
        }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        false
    }

    // MARK: - 配置

    private func showConfigWindow(firstRun: Bool) {
        let controller: ConfigWindowController
        if let existing = configWindow {
            controller = existing
        } else {
            controller = ConfigWindowController()
            controller.onSaved = { [weak self] config in
                Log.shared.info("配置窗口已保存，热重载")
                self?.engine?.reload(config: config)
                self?.menu?.refresh()
            }
            configWindow = controller
        }
        controller.show(firstRun: firstRun)
    }

    private func reloadConfig() {
        guard let config = ConfigStore.load() else {
            Log.shared.error("重载配置失败：\(AppPaths.configFile.path) 无法解析")
            return
        }
        engine?.reload(config: config)
        menu?.refresh()
        Log.shared.info("配置已重载（\(config.folders.count) 个目录）")
    }

    private static func openConfigFile() {
        ConfigStore.ensureConfigFile()
        if !NSWorkspace.shared.open(AppPaths.configFile) {
            Log.shared.error("打开配置文件失败：\(AppPaths.configFile.path)")
        }
    }

    private static func openLog() {
        AppPaths.ensureSupportDir()
        if !FileManager.default.fileExists(atPath: AppPaths.logFile.path) {
            FileManager.default.createFile(atPath: AppPaths.logFile.path, contents: Data())
        }
        if !NSWorkspace.shared.open(AppPaths.logFile) {
            Log.shared.error("打开日志失败：\(AppPaths.logFile.path)")
        }
    }
}
