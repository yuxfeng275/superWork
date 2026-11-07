import Foundation

/// 一条待投递任务（持久化到 queue.json）。
struct QueueItem: Codable {
    var id: String
    var path: String
    var projectId: Int
    var visibility: String
    var note: String
    var attempts: Int
    var lastError: String?
    var addedAt: Date

    init(path: String, projectId: Int, visibility: String, note: String) {
        self.id = UUID().uuidString
        self.path = path
        self.projectId = projectId
        self.visibility = visibility
        self.note = note
        self.attempts = 0
        self.lastError = nil
        self.addedAt = Date()
    }
}

/// 计数器（今日已投 / 累计已投），跨天自动归零。
struct DeliveryState: Codable {
    var day: String
    var deliveredToday: Int
    var deliveredTotal: Int

    static func today() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return formatter.string(from: Date())
    }

    static let empty = DeliveryState(day: DeliveryState.today(), deliveredToday: 0, deliveredTotal: 0)
}

/// 失败队列 + 计数器的磁盘存储。所有访问都在串行队列里做，避免并发写坏文件。
final class QueueStore {
    private let queue = DispatchQueue(label: "com.bu.hepai.delivery.queue")
    private var items: [QueueItem] = []
    private var state: DeliveryState = .empty

    private let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        return encoder
    }()

    private let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }()

    init() {
        AppPaths.ensureSupportDir()
        queue.sync { self.loadFromDisk() }
    }

    // MARK: - 读

    var pendingCount: Int { queue.sync { items.count } }
    var deliveredToday: Int { queue.sync { rollDay(); return state.deliveredToday } }
    var deliveredTotal: Int { queue.sync { state.deliveredTotal } }

    func snapshot() -> [QueueItem] { queue.sync { items } }

    // MARK: - 写

    func enqueue(_ item: QueueItem) {
        queue.sync {
            if let index = items.firstIndex(where: { $0.path == item.path && $0.projectId == item.projectId }) {
                items[index].visibility = item.visibility
                items[index].note = item.note
            } else {
                items.append(item)
            }
            saveQueue()
        }
        Log.shared.info("入队待投 \(item.path) → 项目 #\(item.projectId)")
    }

    /// 投递成功：出队 + 计数 +1。
    func markDelivered(id: String) {
        queue.sync {
            items.removeAll { $0.id == id }
            saveQueue()
            rollDay()
            state.deliveredToday += 1
            state.deliveredTotal += 1
            saveState()
        }
    }

    /// 投递失败：记一次尝试与错误原因，留在队列里等下一轮。
    func markFailed(id: String, error: String) {
        queue.sync {
            if let index = items.firstIndex(where: { $0.id == id }) {
                items[index].attempts += 1
                items[index].lastError = error
                saveQueue()
            }
        }
    }

    func remove(id: String) {
        queue.sync {
            items.removeAll { $0.id == id }
            saveQueue()
        }
    }

    var lastErrorText: String? {
        queue.sync { items.compactMap(\.lastError).last }
    }

    // MARK: - 磁盘

    private func loadFromDisk() {
        if let data = try? Data(contentsOf: AppPaths.queueFile),
           let decoded = try? decoder.decode([QueueItem].self, from: data) {
            items = decoded
        } else {
            items = []
        }
        if let data = try? Data(contentsOf: AppPaths.stateFile),
           let decoded = try? decoder.decode(DeliveryState.self, from: data) {
            state = decoded
        } else {
            state = .empty
        }
        rollDay()
    }

    private func saveQueue() {
        do {
            try encoder.encode(items).write(to: AppPaths.queueFile, options: .atomic)
        } catch {
            Log.shared.error("写 queue.json 失败：\(error.localizedDescription)")
        }
    }

    private func saveState() {
        do {
            try encoder.encode(state).write(to: AppPaths.stateFile, options: .atomic)
        } catch {
            Log.shared.error("写 state.json 失败：\(error.localizedDescription)")
        }
    }

    private func rollDay() {
        let today = DeliveryState.today()
        guard state.day != today else { return }
        state.day = today
        state.deliveredToday = 0
        saveState()
    }
}
