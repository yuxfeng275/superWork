import AppKit

/// 菜单栏图标 + 菜单。所有菜单文案都在 `refresh()` 里更新。
final class StatusMenuController: NSObject, NSMenuDelegate {
    private let statusItem: NSStatusItem
    private let menu = NSMenu()
    private weak var engine: DeliveryEngine?

    private let statusLine = NSMenuItem(title: "", action: nil, keyEquivalent: "")
    private let errorLine = NSMenuItem(title: "", action: nil, keyEquivalent: "")
    private let pauseItem = NSMenuItem(title: "暂停监听", action: nil, keyEquivalent: "p")
    private let retryItem = NSMenuItem(title: "立即重试", action: nil, keyEquivalent: "r")

    var onOpenConfigFile: (() -> Void)?
    var onOpenConfigWindow: (() -> Void)?
    var onOpenLog: (() -> Void)?
    var onReloadConfig: (() -> Void)?
    var onQuit: (() -> Void)?

    init(engine: DeliveryEngine) {
        self.engine = engine
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        super.init()

        if let button = statusItem.button {
            button.title = "合拍·投递"
            button.font = NSFont.systemFont(ofSize: 12, weight: .medium)
        }
        buildMenu()
        statusItem.menu = menu
        refresh()
    }

    private func buildMenu() {
        menu.delegate = self

        statusLine.isEnabled = false
        menu.addItem(statusLine)

        errorLine.isEnabled = false
        errorLine.isHidden = true
        menu.addItem(errorLine)
        menu.addItem(.separator())

        pauseItem.target = self
        pauseItem.action = #selector(togglePause)
        menu.addItem(pauseItem)

        retryItem.target = self
        retryItem.action = #selector(retryNow)
        menu.addItem(retryItem)
        menu.addItem(.separator())

        menu.addItem(makeItem("打开配置（编辑 .hepai-delivery.json）", #selector(openConfigFile)))
        menu.addItem(makeItem("配置窗口…", #selector(openConfigWindow)))
        menu.addItem(makeItem("重载配置", #selector(reloadConfig)))
        menu.addItem(makeItem("打开日志", #selector(openLog)))
        menu.addItem(.separator())
        menu.addItem(makeItem("退出 合拍·投递", #selector(quit)))
    }

    private func makeItem(_ title: String, _ selector: Selector) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: selector, keyEquivalent: "")
        item.target = self
        return item
    }

    func refresh() {
        guard let engine else { return }
        statusLine.title = "待投 \(engine.pendingCount) · 今日已投 \(engine.deliveredToday)"
        pauseItem.title = engine.paused ? "恢复监听" : "暂停监听"

        if let error = engine.lastError, !error.isEmpty {
            errorLine.title = "上次错误：\(error.prefix(60))"
            errorLine.isHidden = false
        } else {
            errorLine.isHidden = true
        }

        statusItem.button?.title = engine.pendingCount > 0 ? "合拍·投递 \(engine.pendingCount)" : "合拍·投递"
    }

    // MARK: - 动作

    @objc private func togglePause() {
        engine?.togglePaused()
        refresh()
    }

    @objc private func retryNow() {
        engine?.retryNow()
        refresh()
    }

    @objc private func openConfigFile() { onOpenConfigFile?() }
    @objc private func openConfigWindow() { onOpenConfigWindow?() }
    @objc private func reloadConfig() { onReloadConfig?() }
    @objc private func openLog() { onOpenLog?() }
    @objc private func quit() { onQuit?() }

    func menuWillOpen(_ menu: NSMenu) {
        refresh()
    }
}
