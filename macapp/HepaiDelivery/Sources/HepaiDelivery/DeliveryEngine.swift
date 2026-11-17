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
    private var summaryTimer: Timer?
    private var flushing = false
    /// 每日汇总的异步窗口重入保护：true 时新的 tick 不再发，等回调落地再放开。
    private var summarySending = false
    private(set) var paused = false

    /// 本地隐私护栏（规则文件 + 配置开关），start/reload 时重建。
    private var privacyGuard = SensitiveGuard(rules: .builtin, enabled: true, configMaxFileSizeMb: 50)
    /// 项目编码 → projectId（键统一大写）。
    private var projectCodes: [String: Int] = [:]
    /// 今日被隔离的示例文件名，每日汇总里带出来。
    private var quarantineSamples: [String] = []
    private var sampleDay = DeliveryState.today()

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
    var quarantinedToday: Int { store.quarantinedToday }
    var quarantinedTotal: Int { store.quarantinedTotal }
    /// 待归类（没认到项目、但也没丢）的文件数。
    var unclassifiedCount: Int { store.unclassifiedCount }

    var quarantineDirectory: URL { AppPaths.quarantineRoot(config.quarantineDir) }

    var lastError: String? { store.lastErrorText }

    // MARK: - 生命周期

    func start() {
        AppPaths.ensureSupportDir()
        applyPrivacyConfig()
        startTimers()
        startSummaryTimer()
        // 静默模式：只在每天固定时刻发一条汇总，逐文件通知已取消。
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
        applyPrivacyConfig()
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
            // 护栏先过：命中就本地隔离，绝不入队、绝不发请求。
            guard passesPrivacyGuard(url: entry.url, size: entry.size) else { continue }
            let resolved = resolveProject(spec: entry.spec, url: entry.url)
            if resolved.projectId <= 0 {
                Log.shared.warn(
                    "项目编码「\(resolved.code ?? "无")」没有映射，进入待归类（不上传也不丢）：\(entry.url.path)"
                )
            } else if let code = resolved.code {
                Log.shared.info("识别到项目编码 \(code) → 项目 #\(resolved.projectId)：\(entry.url.lastPathComponent)")
            }
            store.enqueue(
                QueueItem(
                    path: entry.url.path,
                    projectId: resolved.projectId,
                    visibility: entry.spec.visibility,
                    note: note
                )
            )
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
                // 待归类：入队时认不到项目，但配置事后可能改过（编码映射或目录的项目），
                // 所以这里按当前配置重认一次，命中就把历史队列救回来。
                var projectId = item.projectId
                if projectId <= 0 {
                    guard let rematched = self.rematchProject(for: url) else {
                        Log.shared.info(
                            "仍待归类：\(url.lastPathComponent) → 项目 #0，等配置补上编码映射或目录项目后再试"
                        )
                        continue
                    }
                    projectId = rematched
                    // 队列里存的旧值已经没意义，顺手刷正（并清掉旧错误）。
                    self.store.updateProject(id: item.id, projectId: rematched)
                }
                // 上传前再过一遍护栏：规则或配置可能刚改过。
                guard self.passesPrivacyGuard(url: url, size: nil) else {
                    self.store.remove(id: item.id)
                    DispatchQueue.main.async { self.notifyStatus() }
                    continue
                }
                do {
                    try await self.api.upload(
                        baseUrl: config.baseUrl,
                        username: config.username,
                        password: password,
                        projectId: projectId,
                        fileURL: url,
                        note: item.note,
                        visibility: item.visibility
                    )
                    self.store.markDelivered(id: item.id)
                    Log.shared.info("投递成功 \(item.path) → 项目 #\(projectId) [\(item.visibility)]")
                } catch {
                    let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                    self.store.markFailed(id: item.id, error: message)
                    Log.shared.error("投递失败 \(item.path)：\(message)")
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

    // MARK: - 本地隐私护栏

    /// 规则文件 + 配置开关热生效（start / reload 时调用）。
    private func applyPrivacyConfig() {
        let loaded = SensitiveRulesLoader.load()
        privacyGuard = SensitiveGuard(
            rules: loaded.rules,
            enabled: config.sensitiveFilterEnabled,
            configMaxFileSizeMb: config.maxFileSizeMb
        )
        projectCodes = config.projectCodes.reduce(into: [:]) { result, pair in
            let key = pair.key.trimmingCharacters(in: .whitespaces).uppercased()
            guard !key.isEmpty, pair.value > 0 else { return }
            result[key] = pair.value
        }
        Log.shared.info(
            "隐私护栏：\(config.sensitiveFilterEnabled ? "开" : "关")"
                + "，规则 \(loaded.source)"
                + "，体积上限 \(privacyGuard.maxFileSizeMb)MB"
                + "，项目编码 \(projectCodes.count) 条"
                + "，每日汇总 \(config.dailySummaryHour) 点"
        )
    }

    /// 只有通过护栏的文件才会进上传队列；命中的当场移到隔离目录，一个字节也不发出去。
    private func passesPrivacyGuard(url: URL, size bytes: Int64?) -> Bool {
        guard config.sensitiveFilterEnabled else { return true }
        let fileSize = bytes ?? sizeOf(url) ?? 0
        switch privacyGuard.check(fileName: url.lastPathComponent, size: fileSize) {
        case .allow:
            return true
        case let .block(reason):
            quarantine(url: url, reason: reason)
            return false
        }
    }

    /// 移到 `quarantine/yyyy-MM-dd/`（不删除、同名不覆盖），写日志 + 记账。
    @discardableResult
    private func quarantine(url: URL, reason: String) -> URL? {
        let directory = Quarantine.directory(for: config)
        let manager = FileManager.default
        do {
            try manager.createDirectory(at: directory, withIntermediateDirectories: true)
            let target = Quarantine.uniqueTarget(in: directory, fileName: url.lastPathComponent)
            try manager.moveItem(at: url, to: target)
            store.recordQuarantine()
            recordQuarantineSample(url.lastPathComponent)
            Log.shared.warn("隐私护栏命中（\(reason)），已隔离 \(url.lastPathComponent) → \(target.path)，未上传")
            notifyStatus()
            return target
        } catch {
            Log.shared.error("隔离失败（\(reason)）\(url.path)：\(error.localizedDescription)；该文件不会上传")
            return nil
        }
    }

    private func recordQuarantineSample(_ name: String) {
        let today = DeliveryState.today()
        if sampleDay != today {
            sampleDay = today
            quarantineSamples.removeAll()
        }
        guard quarantineSamples.count < 10, !quarantineSamples.contains(name) else { return }
        quarantineSamples.append(name)
    }

    // MARK: - 项目识别

    /// 文件名/路径里出现项目编码 → 用映射到的 projectId；映射不到按配置走待归类，不静默丢文件。
    private func resolveProject(
        spec: FolderWatcher.RootSpec,
        url: URL
    ) -> (projectId: Int, code: String?) {
        guard let match = ProjectCode.match(
            fileName: url.lastPathComponent,
            path: url.path,
            mapping: projectCodes
        ) else {
            return (spec.projectId, nil)
        }
        if let mapped = match.projectId, mapped > 0 {
            return (mapped, match.code)
        }
        if config.unresolvedBehaviorIsPending {
            return (0, match.code)
        }
        return (spec.projectId, match.code)
    }

    /// 待归类项的重认（flush 用）：有两条救援路径，改其中任一条都能把历史队列救回来——
    /// 1) 项目编码映射 `projectCodes`（事后补上的）；
    /// 2) 目录自己配的项目：`folders[]` 里该文件所在目录的 `projectId > 0`，子目录配置优先（取路径最长）。
    /// 两条都认不到才返回 nil；命中时写一行日志说明是走编码还是走目录。
    private func rematchProject(for url: URL) -> Int? {
        let name = url.lastPathComponent
        if let hit = ProjectCode.match(
            fileName: name,
            path: url.path,
            mapping: projectCodes
        ), let mapped = hit.projectId, mapped > 0 {
            Log.shared.info("重认成功：\(name) → 项目 #\(mapped)（编码 \(hit.code)）")
            return mapped
        }

        let filePath = url.path
        var bestDir = ""
        var bestProject = 0
        for folder in config.folders where folder.projectId > 0 {
            let dir = AppPaths.expand(folder.path)
            guard !dir.isEmpty else { continue }
            // 目录补上 / 后做前缀比较；文件路径等于目录本身也算命中。
            let prefix = dir.hasSuffix("/") ? dir : dir + "/"
            guard filePath == dir || filePath.hasPrefix(prefix) else { continue }
            // 多个命中取路径最长的：子目录配置优先于父目录配置。
            if dir.count > bestDir.count {
                bestDir = dir
                bestProject = folder.projectId
            }
        }
        guard bestProject > 0 else { return nil }
        Log.shared.info("重认成功：\(name) → 项目 #\(bestProject)（目录 \(bestDir)）")
        return bestProject
    }

    // MARK: - 每日汇总

    /// 每分钟看一眼：到点且今天还没发过，就发一条汇总（每天只一条）。
    private func startSummaryTimer() {
        guard summaryTimer == nil else { return }
        let timer = Timer(timeInterval: 60, repeats: true) { [weak self] _ in
            self?.checkDailySummary()
        }
        RunLoop.main.add(timer, forMode: .common)
        summaryTimer = timer
    }

    private func checkDailySummary() {
        let hour = min(max(config.dailySummaryHour, 0), 23)
        let today = DeliveryState.today()
        // 标记持久化在 state.json 里：同一天只发一条，重启也不会重发。
        guard store.summaryDay != today else { return }
        guard Calendar.current.component(.hour, from: Date()) >= hour else { return }
        // 通知还在异步入队的窗口里就不再发一条，避免重发。
        guard !summarySending else { return }
        summarySending = true
        // 真的入队成功才标记；今日还没数据、授权被拒、入队失败都不提前占掉这一天的名额，
        // 下一分钟的 tick 会自然重试，直到当天真发出去。
        sendDailySummary { [weak self] sent in
            guard let self else { return }
            DispatchQueue.main.async {
                self.summarySending = false
                if sent {
                    self.store.markSummarySent(day: today)
                } else {
                    Log.shared.warn("每日汇总未发出（无数据 / 授权被拒 / 入队失败），不标记已发，下一分钟重试")
                }
            }
        }
    }

    /// 异步发每日汇总：只有通知真的入队成功才回调 true。
    private func sendDailySummary(completion: @escaping (Bool) -> Void) {
        let delivered = store.deliveredToday
        let quarantined = store.quarantinedToday
        guard delivered > 0 || quarantined > 0 else {
            Log.shared.info("今日暂无可汇总数据（无投递也无隔离），下一分钟再试")
            completion(false)
            return
        }
        let example = quarantineSamples.prefix(3).joined(separator: "、")
        Notifier.dailySummary(
            delivered: delivered,
            quarantined: quarantined,
            samples: quarantineSamples
        ) { sent in
            if sent {
                Log.shared.info(
                    "已发每日汇总：今日已投 \(delivered)，隔离 \(quarantined)"
                        + (example.isEmpty ? "" : "（示例：\(example)）")
                )
            }
            completion(sent)
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
