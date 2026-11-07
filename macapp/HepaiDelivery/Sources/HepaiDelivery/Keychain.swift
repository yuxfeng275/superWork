import Foundation
import Security

enum KeychainError: LocalizedError {
    case status(OSStatus)
    case empty

    var errorDescription: String? {
        switch self {
        case .status(let status):
            let text = SecCopyErrorMessageString(status, nil) as String? ?? "未知错误"
            return "Keychain 操作失败（\(status)）：\(text)"
        case .empty:
            return "密码为空"
        }
    }
}

/// 密码只进 Keychain：service = com.bu.hepai.delivery，account = "password:<用户名>"。
enum Keychain {
    static let service = "com.bu.hepai.delivery"

    private static func account(for username: String) -> String {
        "password:\(username)"
    }

    private static func baseQuery(username: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account(for: username)
        ]
    }

    static func savePassword(_ password: String, username: String) throws {
        guard !password.isEmpty else { throw KeychainError.empty }
        let data = Data(password.utf8)
        let query = baseQuery(username: username)
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrLabel as String: "合拍·投递 密码"
        ]

        let updateStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecSuccess { return }
        guard updateStatus == errSecItemNotFound else { throw KeychainError.status(updateStatus) }

        var insert = query
        insert.merge(attributes) { _, new in new }
        insert[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        let addStatus = SecItemAdd(insert as CFDictionary, nil)
        guard addStatus == errSecSuccess else { throw KeychainError.status(addStatus) }
    }

    static func password(username: String) -> String? {
        var query = baseQuery(username: username)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func deletePassword(username: String) {
        SecItemDelete(baseQuery(username: username) as CFDictionary)
    }
}
