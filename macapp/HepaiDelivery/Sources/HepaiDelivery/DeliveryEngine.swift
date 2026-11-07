import Foundation

/// 编排：监听 → 稳定性判定 → 入队 → 上传 → 失败重试 → 通知/状态刷新。
/// 状态都在主线程改，只有上传循环里跳到后台再回主线程刷 UI，所以标 @unchecked Sendable。
final class DeliveryEngine: @unchecked Sendable {
    struct Unstable {
        var size: Int64
        var firstSeen: Date
        var spec: FolderWatcher.RootSpec
        var url: URL
    }

    private let store = QueueStore()
    private let api = DeliveryAPI()
    private let watcher = FolderWatcher()

    private var config: AppConfig
    private var passwordProvider: (String) -> String?
    private var unstable: [String: Unstable] = [:]
    private var stabilityTimer: Timer?
    private var retryTimer: Timer?
    private var flushing = false
    private(set) var paused = false

    /// 队列/计数变化时通知菜单刷新（主线程回调）。
    var statusChanged: (() -> Void)?

    private let stabilityInterval: TimeInterval = 0.9
    private let stableThreshold: TimeInterval = 1.2

    init(config: AppConfig, passwordProvider: @escaping (String) -> String?) {
        self.config = config
        self.passwordProvider = passwordProvider
    }

    var note: String {
        "来自 合拍·投递 / \(ProcessInfo.processInfo.hostName)"
    }

    var pendingCount: Int { store.pendingCount }
    var deliveredToday: Int { store.deliveredToday }

    var lastError: String? { store.lastErrorText }

    // MARK: - 生命周期

    func start() {
        AppPaths.ensureSupportDir()
        startTimers()
        Notifier.requestAuthorization()

        let specs = config.folders.map {
            FolderWatcher.RootSpec(path: $0.path, projectId: $0.projectId, visibility: $0.visibility)
        }
        watcher.onCandidate = { [weak self] spec, url in
            DispatchQueue.main.async {
                self?.track(spec: spec, url: url)
            }
        }
        watcher.start(specs: specs)
        Log.shared.info("引擎启动，队列中已有 \(store.pendingCount) 条待投")

        if specs.isEmpty {
            Log.shared.warn("没有配置任何监听目录")
        }
        flush()
        notifyStatus()
    }

    /// 配置改了（窗口保存 / 重载）后热生效。
    func reload(config newConfig: AppConfig) {
        config = newConfig
        unstable.removeAll()
        Task { await api.resetToken() }
        let specs = newConfig.folders.map {
            FolderWatcher.RootSpec(path: $0.path, projectId: $0.projectId, visibility: $0.visibility)
        }
        watcher.start(specs: specs)
        if paused {
            watcher.setPaused(true)
        }
        flush()
        notifyStatus()
    }

    func setPaused(_ value: Bool) {
        paused = value
        watcher.setPaused(value)
        notifyStatus()
    }

    func togglePaused() { setPaused(!paused) }

    /// 「立即重试」：先重扫目录，再把队列跑一遍。
    func retryNow() {
        watcher.rescanNow()
        flush()
        notifyStatus()
    }

    // MARK: - 定时器

    private func startTimers() {
        guard stabilityTimer == nil, retryTimer == nil else { return }

        let stabilityTimer = Timer(timeInterval: stabilityInterval, repeats: true) { [weak self] _ in
            self?.checkStability()
        }
        RunLoop.main.add(stabilityTimer, forMode: .common)
        self.stabilityTimer = stabilityTimer

        let retryTimer = Timer(timeInterval: 60, repeats: true) { [weak self] _ in
            Log.shared.info("60s 重试 tick，待投 \(self?.store.pendingCount ?? 0)")
            self?.flush()
        }
        RunLoop.main.add(retryTimer, forMode: .common)
        self.retryTimer = retryTimer
    }

    // MARK: - 稳定性判定

    private func track(spec: FolderWatcher.RootSpec, url: URL) {
        guard !paused else { return }
        guard let size = sizeOf(url) else { return }
        if let existing = unstable[url.path], existing.size == size {
            // 大小没变：保留首次观测时间，等它满足稳定性阈值。
            return
        }
        unstable[url.path] = Unstable(size: size, firstSeen: Date(), spec: spec, url: url)
        Log.shared.info("发现新文件 \(url.path)（\(size) 字节，等待稳定）")
    }

    /// 两次 stat 大小一致 + 至少 stableThreshold 秒没人写 → 认为稳定，入队投递。
    private func checkStability() {
        guard !unstable.isEmpty else { return }
        var ready: [Unstable] = []
        for (path, entry) in unstable {
            guard let size = sizeOf(entry.url) else {
                unstable.removeValue(forKey: path)
                continue
            }
            if size != entry.size {
                var updated = entry
                updated.size = size
                updated.firstSeen = Date()
                unstable[path] = updated
                continue
            }
            if Date().timeIntervalSince(entry.firstSeen) >= stableThreshold {
                ready.append(entry)
                unstable.removeValue(forKey: path)
            }
        }
        guard !ready.isEmpty else { return }
        for entry in ready {
            let item = QueueItem(
                path: entry.url.path,
                projectId: entry.spec.projectId,
                visibility: entry.spec.visibility,
                note: note
            )
            store.enqueue(item)
        }
        notifyStatus()
        flush()
    }

    private func sizeOf(_ url: URL) -> Int64? {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: url.path) else { return nil }
        if (attributes[.type] as? FileAttributeType) == .typeDirectory { return nil }
        return (attributes[.size] as? NSNumber)?.int64Value ?? 0
    }

    // MARK: - 上传

    /// 串行地把队列跑一遍（同一时间只有一个 flush 在跑）。
    func flush() {
        guard !flushing else { return }
        let items = store.snapshot()
        guard !items.isEmpty else { return }

        let config = self.config
        guard let password = passwordProvider(config.username), !password.isEmpty else {
            Log.shared.warn("Keychain 里没有 \(config.username) 的密码，跳过本轮投递")
            return
        }

        flushing = true
        Task { [weak self] in
            guard let self else { return }
            var stop = false
            for item in items where !stop {
                let url = URL(fileURLWithPath: item.path)
                guard FileManager.default.fileExists(atPath: item.path) else {
                    self.store.remove(id: item.id)
                    Log.shared.warn("源文件已不存在，出队：\(item.path)")
                    continue
                }
                do {
                    try await self.api.upload(
                        baseUrl: config.baseUrl,
                        username: config.username,
                        password: password,
                        projectId: item.projectId,
                        fileURL: url,
                        note: item.note,
                        visibility: item.visibility
                    )
                    self.store.markDelivered(id: item.id)
                    Log.shared.info("投递成功 \(item.path) → 项目 #\(item.projectId) [\(item.visibility)]")
                    Notifier.delivered(fileName: url.lastPathComponent, projectId: item.projectId, path: item.path)
                } catch {
                    let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                    self.store.markFailed(id: item.id, error: message)
                    Log.shared.error("投递失败 \(item.path)：\(message)")
                    Notifier.failed(fileName: url.lastPathComponent, reason: message)
                    // 登录/鉴权问题就不必把队列里剩下的挨个撞一遍。
                    if message.contains("登录失败") || message.contains("401") {
                        stop = true
                    }
                }
                DispatchQueue.main.async { self.notifyStatus() }
            }
            DispatchQueue.main.async {
                self.flushing = false
                self.notifyStatus()
            }
        }
    }

    private func notifyStatus() {
        if Thread.isMainThread {
            statusChanged?()
        } else {
            DispatchQueue.main.async { [weak self] in self?.statusChanged?() }
        }
    }
}
