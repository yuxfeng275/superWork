import AppKit

/// 把按钮/控件上的 target-action 转成闭包。
final class ActionProxy: NSObject {
    private let handler: () -> Void

    init(_ handler: @escaping () -> Void) {
        self.handler = handler
        super.init()
    }

    @objc func fire() { handler() }
}

/// 一行目录配置：路径 + 项目 ID + 可见范围。
final class FolderRowView: NSView {
    private let pathField = NSTextField(string: "")
    private let projectField = NSTextField(string: "1")
    private let visibilityPopup = NSPopUpButton(frame: .zero, pullsDown: false)
    private var removeProxy: ActionProxy?
    private var browseProxy: ActionProxy?

    var onRemove: (() -> Void)?

    var folder: FolderConfig {
        FolderConfig(
            path: pathField.stringValue.trimmingCharacters(in: .whitespacesAndNewlines),
            projectId: Int(projectField.stringValue.trimmingCharacters(in: .whitespaces)) ?? 0,
            visibility: visibilityPopup.titleOfSelectedItem ?? "PROJECT"
        )
    }

    init(folder: FolderConfig) {
        super.init(frame: .zero)
        translatesAutoresizingMaskIntoConstraints = false
        pathField.stringValue = folder.path
        pathField.placeholderString = "/Users/you/Desktop/投递箱"
        projectField.stringValue = String(folder.projectId)
        projectField.alignment = .center

        visibilityPopup.addItems(withTitles: ["PROJECT", "PRIVATE_COMPANY", "OWNER"])
        if let index = ["PROJECT", "PRIVATE_COMPANY", "OWNER"].firstIndex(of: folder.visibility) {
            visibilityPopup.selectItem(at: index)
        }

        let browseProxy = ActionProxy { [weak self] in self?.chooseDirectory() }
        self.browseProxy = browseProxy
        let browse = NSButton(title: "选择…", target: browseProxy, action: #selector(ActionProxy.fire))
        browse.bezelStyle = .rounded

        let removeProxy = ActionProxy { [weak self] in self?.onRemove?() }
        self.removeProxy = removeProxy
        let remove = NSButton(title: "－", target: removeProxy, action: #selector(ActionProxy.fire))
        remove.bezelStyle = .rounded

        let stack = NSStackView(views: [pathField, browse, projectField, visibilityPopup, remove])
        stack.orientation = .horizontal
        stack.spacing = 8
        stack.alignment = .centerY
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)

        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor),
            stack.topAnchor.constraint(equalTo: topAnchor),
            stack.bottomAnchor.constraint(equalTo: bottomAnchor),
            pathField.widthAnchor.constraint(greaterThanOrEqualToConstant: 240),
            projectField.widthAnchor.constraint(equalToConstant: 64),
            visibilityPopup.widthAnchor.constraint(equalToConstant: 150)
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) 不支持") }

    func clear() {
        pathField.stringValue = ""
        projectField.stringValue = "1"
        visibilityPopup.selectItem(at: 0)
    }

    private func chooseDirectory() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.allowsMultipleSelection = false
        panel.prompt = "监听此目录"
        if panel.runModal() == .OK, let url = panel.url {
            pathField.stringValue = url.path
        }
    }
}

/// 首次运行 / 菜单「配置窗口…」打开的配置面板。
final class ConfigWindowController: NSWindowController, NSWindowDelegate {
    private let baseUrlField = NSTextField(string: "")
    private let usernameField = NSTextField(string: "")
    private let passwordField = NSSecureTextField(string: "")
    private let foldersStack = NSStackView()
    private var rows: [FolderRowView] = []
    private var addProxy: ActionProxy?
    private var saveProxy: ActionProxy?
    private var closeProxy: ActionProxy?

    var onSaved: ((AppConfig) -> Void)?

    convenience init() {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 720, height: 520),
            styleMask: [.titled, .closable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.title = "合拍·投递 · 配置"
        window.center()
        window.isReleasedWhenClosed = false
        self.init(window: window)
        window.delegate = self
        buildUI()
        loadCurrent()
    }

    // MARK: - UI

    private func buildUI() {
        guard let window else { return }

        let baseLabel = NSTextField(labelWithString: "服务器地址 baseUrl")
        let userLabel = NSTextField(labelWithString: "用户名")
        let passwordLabel = NSTextField(labelWithString: "密码（存 Keychain，不落盘）")
        baseUrlField.placeholderString = "https://hepai.example.com"
        usernameField.placeholderString = "域账号"
        passwordField.placeholderString = "••••••"

        let grid = NSGridView(views: [
            [baseLabel, baseUrlField],
            [userLabel, usernameField],
            [passwordLabel, passwordField]
        ])
        grid.rowSpacing = 8
        grid.columnSpacing = 10
        grid.column(at: 0).xPlacement = .trailing
        grid.translatesAutoresizingMaskIntoConstraints = false

        let foldersTitle = NSTextField(labelWithString: "监听目录（新文件稳定后自动投递）")
        foldersTitle.font = NSFont.boldSystemFont(ofSize: 12)

        foldersStack.orientation = .vertical
        foldersStack.alignment = .leading
        foldersStack.spacing = 8
        foldersStack.translatesAutoresizingMaskIntoConstraints = false

        let documentView = NSView()
        documentView.translatesAutoresizingMaskIntoConstraints = false
        documentView.addSubview(foldersStack)
        NSLayoutConstraint.activate([
            foldersStack.leadingAnchor.constraint(equalTo: documentView.leadingAnchor),
            foldersStack.trailingAnchor.constraint(equalTo: documentView.trailingAnchor),
            foldersStack.topAnchor.constraint(equalTo: documentView.topAnchor),
            foldersStack.bottomAnchor.constraint(equalTo: documentView.bottomAnchor)
        ])

        let scroll = NSScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.hasVerticalScroller = true
        scroll.borderType = .bezelBorder
        scroll.drawsBackground = true
        scroll.documentView = documentView

        let addProxy = ActionProxy { [weak self] in self?.addRow(FolderConfig(path: "", projectId: 1, visibility: "PROJECT")) }
        self.addProxy = addProxy
        let addButton = NSButton(title: "＋ 添加目录", target: addProxy, action: #selector(ActionProxy.fire))
        addButton.bezelStyle = .rounded

        let saveProxy = ActionProxy { [weak self] in self?.save() }
        self.saveProxy = saveProxy
        let saveButton = NSButton(title: "保存", target: saveProxy, action: #selector(ActionProxy.fire))
        saveButton.keyEquivalent = "\r"
        saveButton.bezelStyle = .rounded

        let closeProxy = ActionProxy { [weak self] in self?.window?.close() }
        self.closeProxy = closeProxy
        let closeButton = NSButton(title: "取消", target: closeProxy, action: #selector(ActionProxy.fire))
        closeButton.bezelStyle = .rounded

        let hint = NSTextField(wrappingLabelWithString:
            "配置文件：\(AppPaths.configFile.path)（密码只在钥匙串，不写进 JSON）\n"
            + "队列/日志：\(AppPaths.supportDir.path)")
        hint.font = NSFont.systemFont(ofSize: 10)
        hint.textColor = .secondaryLabelColor
        hint.translatesAutoresizingMaskIntoConstraints = false

        let spacer = NSView()
        spacer.setContentHuggingPriority(NSLayoutConstraint.Priority(1), for: .horizontal)
        let buttons = NSStackView(views: [addButton, spacer, closeButton, saveButton])
        buttons.orientation = .horizontal
        buttons.spacing = 10
        buttons.translatesAutoresizingMaskIntoConstraints = false

        let content = NSView()
        content.addSubview(grid)
        content.addSubview(foldersTitle)
        content.addSubview(scroll)
        content.addSubview(buttons)
        content.addSubview(hint)
        foldersTitle.translatesAutoresizingMaskIntoConstraints = false

        NSLayoutConstraint.activate([
            grid.topAnchor.constraint(equalTo: content.topAnchor, constant: 18),
            grid.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 18),
            grid.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -18),
            baseUrlField.widthAnchor.constraint(greaterThanOrEqualToConstant: 380),

            foldersTitle.topAnchor.constraint(equalTo: grid.bottomAnchor, constant: 18),
            foldersTitle.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 18),

            scroll.topAnchor.constraint(equalTo: foldersTitle.bottomAnchor, constant: 8),
            scroll.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 18),
            scroll.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -18),
            scroll.heightAnchor.constraint(greaterThanOrEqualToConstant: 150),

            buttons.topAnchor.constraint(equalTo: scroll.bottomAnchor, constant: 12),
            buttons.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 18),
            buttons.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -18),

            hint.topAnchor.constraint(equalTo: buttons.bottomAnchor, constant: 10),
            hint.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 18),
            hint.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -18),
            hint.bottomAnchor.constraint(equalTo: content.bottomAnchor, constant: -14)
        ])

        // documentView 宽度跟随滚动区，避免横向滚动。
        NSLayoutConstraint.activate([
            documentView.widthAnchor.constraint(equalTo: scroll.contentView.widthAnchor)
        ])

        window.contentView = content
    }

    private func loadCurrent() {
        let config = ConfigStore.load() ?? AppConfig()
        baseUrlField.stringValue = config.baseUrl
        usernameField.stringValue = config.username
        if !config.username.isEmpty {
            passwordField.stringValue = Keychain.password(username: config.username) ?? ""
        }
        rows.forEach { $0.removeFromSuperview() }
        rows.removeAll()
        for folder in config.folders {
            addRow(folder)
        }
        if config.folders.isEmpty {
            addRow(FolderConfig(path: "", projectId: 1, visibility: "PROJECT"))
        }
    }

    private func addRow(_ folder: FolderConfig) {
        let row = FolderRowView(folder: folder)
        row.onRemove = { [weak self, weak row] in
            guard let self, let row else { return }
            self.rows.removeAll { $0 === row }
            row.removeFromSuperview()
        }
        rows.append(row)
        foldersStack.addArrangedSubview(row)
        row.widthAnchor.constraint(equalTo: foldersStack.widthAnchor).isActive = true
    }

    // MARK: - 保存

    private func save() {
        let folders = rows.map { $0.folder }.filter { !$0.path.isEmpty }
        let config = AppConfig(
            baseUrl: baseUrlField.stringValue.trimmingCharacters(in: .whitespacesAndNewlines),
            username: usernameField.stringValue.trimmingCharacters(in: .whitespacesAndNewlines),
            folders: folders
        )

        guard !config.baseUrl.isEmpty, !config.username.isEmpty else {
            alert("服务器地址和用户名必填")
            return
        }
        guard !folders.isEmpty else {
            alert("至少配置一个监听目录")
            return
        }
        if folders.contains(where: { $0.projectId <= 0 }) {
            alert("projectId 必须是正整数")
            return
        }

        do {
            try ConfigStore.save(config)
            if !passwordField.stringValue.isEmpty {
                try Keychain.savePassword(passwordField.stringValue, username: config.username)
            }
            Log.shared.info("配置已保存：baseUrl=\(config.baseUrl) 用户=\(config.username) 目录=\(folders.count)")
            onSaved?(config)
            window?.close()
        } catch {
            alert(error.localizedDescription)
        }
    }

    private func alert(_ message: String) {
        let alert = NSAlert()
        alert.messageText = "配置有问题"
        alert.informativeText = message
        alert.alertStyle = .warning
        alert.runModal()
    }

    func show(firstRun: Bool) {
        loadCurrent()
        NSApp.activate(ignoringOtherApps: true)
        showWindow(nil)
        window?.makeKeyAndOrderFront(nil)
        if firstRun {
            let alert = NSAlert()
            alert.messageText = "首次配置"
            alert.informativeText = "填服务器地址、用户名与密码，并添加要监听的目录。密码会存进钥匙串（service \(Keychain.service)），不会写进 JSON。"
            alert.runModal()
        }
    }
}
