import Foundation
import Darwin

/// 递归监听若干目录：每个子目录一个 `DispatchSource.makeFileSystemObjectSource`（write/vnode），
/// 事件到达后合并 0.4s 再全量扫一遍树，只把「新出现 / 有变化」的文件回调出去。
final class FolderWatcher {
    struct RootSpec: Equatable {
        var path: String
        var projectId: Int
        var visibility: String
    }

    /// 文件指纹：size + mtime。同时是落盘快照（watch-snapshot.json）的单元。
    private struct Stamp: Equatable, Codable {
        var size: Int64
        var mtime: TimeInterval
    }

    /// `watch-snapshot.json` 的结构：path → Stamp，带版本号方便以后换格式时整体作废。
    private struct SnapshotFile: Codable {
        var version: Int
        var files: [String: Stamp]
    }

    private static let snapshotVersion = 1

    /// 不投递的垃圾文件。
    private static let ignoredNames: Set<String> = [".DS_Store", "Icon\r", "Thumbs.db"]
    private static let ignoredSuffixes = [".part", ".download", ".crdownload", ".tmp", ".swp", ".swo", "~"]

    private let queue = DispatchQueue(label: "com.bu.hepai.delivery.watch")
    private var specs: [RootSpec] = []
    private var sources: [String: DispatchSourceFileSystemObject] = [:]
    private var snapshot: [String: Stamp] = [:]
    private var debounceWork: DispatchWorkItem?
    private var paused = false

    /// 发现候选文件（仍是未稳定的，稳不稳定交给 Engine 判）。
    var onCandidate: ((RootSpec, URL) -> Void)?

    /// 启动 = 恢复监听源 + 与落盘快照做一次对账。
    ///
    /// 对账语义：快照里没有的文件（`app` 停机期间新放进目录的）、以及 size/mtime 与快照不同的
    /// 文件（停机期间被修改的），都会在启动后的首轮扫描成为候选、交给 Engine 投递；基线里已有
    /// 且未变化的文件不会被重复投递。
    func start(specs: [RootSpec]) {
        queue.async {
            self.specs = specs
            self.sources.forEach { $0.value.cancel() }
            self.sources = [:]
            self.paused = false

            // 优先加载上次退出时落盘的快照当基线：以它为基线扫一遍并**发候选**，
            // 停机期间新增 / 修改的文件由此补齐。
            // 加载不到（首次安装、或旧版本升级时还没有快照文件）就先全量建立基线、不发候选，
            // 避免把整个目录一次性重传。
            if let baseline = self.loadSnapshotFromDisk() {
                self.snapshot = baseline
                self.refreshSources(emitCandidates: true)
                Log.shared.info("已加载监听快照（\(baseline.count) 个文件）作为基线，启动对账补齐停机期间的新文件")
            } else {
                self.snapshot = [:]
                self.primeSnapshot()
                self.refreshSources(emitCandidates: false)
                Log.shared.info("没有可用的监听快照，已全量建立基线（本轮不发候选）")
            }
            Log.shared.info("开始监听 \(specs.count) 个目录：\(specs.map { $0.path }.joined(separator: ", "))")
        }
    }

    func stop() {
        queue.async {
            self.sources.forEach { $0.value.cancel() }
            self.sources = [:]
            self.snapshot = [:]
            self.debounceWork?.cancel()
            self.debounceWork = nil
            Log.shared.info("停止监听")
        }
    }

    func setPaused(_ value: Bool) {
        queue.async {
            self.paused = value
            if value {
                Log.shared.info("监听已暂停")
            } else {
                Log.shared.info("监听已恢复")
                self.scheduleRescan()
            }
        }
    }

    /// 主动扫一次（暂停时也允许，用于「立即重试」前的刷新）。
    func rescanNow() {
        queue.async { self.scheduleRescan() }
    }

    // MARK: - 扫描

    /// 首次安装 / 快照丢失时用：把当前目录里所有文件记成基线，不产生候选。
    private func primeSnapshot() {
        for spec in specs {
            let root = URL(fileURLWithPath: AppPaths.expand(spec.path))
            guard let enumerator = makeEnumerator(root) else { continue }
            for case let url as URL in enumerator {
                guard !shouldIgnore(url) else { continue }
                guard let stamp = stampOf(url, isDirectory: false) else { continue }
                snapshot[url.path] = stamp
            }
        }
    }

    // MARK: - 快照落盘

    /// 读 `~/Library/Application Support/HepaiDelivery/watch-snapshot.json`。
    /// 返回 nil 表示没有可用基线（文件不存在、读不了、版本不符），调用方应退回全量建基线。
    private func loadSnapshotFromDisk() -> [String: Stamp]? {
        let url = AppPaths.watchSnapshotFile
        guard let data = try? Data(contentsOf: url) else { return nil }
        guard let file = try? JSONDecoder().decode(SnapshotFile.self, from: data) else {
            Log.shared.warn("监听快照无法解析，退回全量建立基线：\(url.path)")
            return nil
        }
        guard file.version == Self.snapshotVersion else {
            Log.shared.warn("监听快照版本不符（\(file.version) ≠ \(Self.snapshotVersion)），退回全量建立基线")
            return nil
        }
        return file.files
    }

    /// 每轮扫描后把快照落盘。写失败只记日志，不影响本轮投递。
    private func saveSnapshot() {
        let url = AppPaths.watchSnapshotFile
        let payload = SnapshotFile(version: Self.snapshotVersion, files: snapshot)
        do {
            AppPaths.ensureSupportDir()
            let data = try JSONEncoder().encode(payload)
            try data.write(to: url, options: .atomic)
        } catch {
            Log.shared.warn("监听快照落盘失败（不影响投递）：\(error.localizedDescription)")
        }
    }

    private func scheduleRescan() {
        debounceWork?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.refreshSources(emitCandidates: true) }
        debounceWork = work
        queue.asyncAfter(deadline: .now() + 0.4, execute: work)
    }

    /// 重建目录监听源 + 收集候选文件。
    private func refreshSources(emitCandidates: Bool) {
        guard !paused || !emitCandidates else { return }

        var liveDirectories: Set<String> = []
        var seenFiles: Set<String> = []

        for spec in specs {
            let root = URL(fileURLWithPath: AppPaths.expand(spec.path))
            var isDirectory: ObjCBool = false
            guard FileManager.default.fileExists(atPath: root.path, isDirectory: &isDirectory), isDirectory.boolValue else {
                continue
            }
            liveDirectories.insert(root.path)
            addSource(forDirectory: root.path)

            guard let enumerator = makeEnumerator(root) else { continue }
            for case let url as URL in enumerator {
                let path = url.path
                let values = try? url.resourceValues(forKeys: [.isDirectoryKey])
                if values?.isDirectory == true {
                    liveDirectories.insert(path)
                    addSource(forDirectory: path)
                    continue
                }
                guard !shouldIgnore(url) else { continue }
                guard let stamp = stampOf(url, isDirectory: false) else { continue }
                seenFiles.insert(path)

                if let previous = snapshot[path], previous == stamp { continue }
                snapshot[path] = stamp
                if emitCandidates, !paused {
                    onCandidate?(spec, url)
                }
            }
        }

        // 清掉已消失的文件记录，避免下次同名文件被误判为「没变化」。
        for path in snapshot.keys where !seenFiles.contains(path) {
            if path.isEmpty { continue }
            snapshot.removeValue(forKey: path)
        }

        // 卸载已消失目录的监听源。
        for (path, source) in sources where !liveDirectories.contains(path) {
            source.cancel()
            sources.removeValue(forKey: path)
        }

        // 每轮扫完都落盘，这样进程被杀 / 停机后重启还能拿到上一次的基线做对账。
        saveSnapshot()
    }

    private func makeEnumerator(_ root: URL) -> FileManager.DirectoryEnumerator? {
        FileManager.default.enumerator(
            at: root,
            includingPropertiesForKeys: [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey],
            options: [.skipsHiddenFiles, .skipsPackageDescendants],
            errorHandler: { url, error in
                Log.shared.warn("遍历失败 \(url.path)：\(error.localizedDescription)")
                return true
            }
        )
    }

    private func shouldIgnore(_ url: URL) -> Bool {
        let name = url.lastPathComponent
        if Self.ignoredNames.contains(name) { return true }
        if name.hasPrefix(".") { return true }
        for suffix in Self.ignoredSuffixes where name.hasSuffix(suffix) { return true }
        return false
    }

    private func stampOf(_ url: URL, isDirectory: Bool) -> Stamp? {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: url.path) else { return nil }
        if (attributes[.type] as? FileAttributeType) == .typeDirectory { return nil }
        let size = (attributes[.size] as? NSNumber)?.int64Value ?? 0
        let mtime = (attributes[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0
        return Stamp(size: size, mtime: mtime)
    }

    // MARK: - DispatchSource

    private func addSource(forDirectory path: String) {
        guard sources[path] == nil else { return }
        let fd = open(path, O_EVTONLY)
        guard fd >= 0 else {
            Log.shared.warn("无法监听目录 \(path)（open errno=\(errno)）")
            return
        }
        let source = DispatchSource.makeFileSystemObjectSource(
            fileDescriptor: fd,
            eventMask: [.write, .extend, .attrib, .delete, .rename, .link],
            queue: queue
        )
        source.setEventHandler { [weak self, weak source] in
            guard let self else { return }
            let flags = source?.data ?? []
            if flags.contains(.delete) || flags.contains(.rename) {
                self.sources.removeValue(forKey: path)?.cancel()
            }
            self.scheduleRescan()
        }
        source.setCancelHandler { close(fd) }
        source.resume()
        sources[path] = source
    }
}
