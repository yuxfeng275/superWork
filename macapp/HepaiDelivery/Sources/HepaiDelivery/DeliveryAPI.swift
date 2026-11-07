import Foundation
import UniformTypeIdentifiers

struct DeliveryError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// 后端客户端：登录拿 token（进程内存缓存）+ multipart 投递文件。
/// 401 时重登一次再重试；其余错误直接抛出交给失败队列。
actor DeliveryAPI {
    private let session: URLSession
    private var token: String?
    private var tokenUser: String?

    init() {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 120
        configuration.waitsForConnectivity = false
        session = URLSession(configuration: configuration)
    }

    /// 清掉缓存 token（换账号 / 配置变更时调用）。
    func resetToken() {
        token = nil
        tokenUser = nil
    }

    // MARK: - 登录

    private func makeURL(_ baseUrl: String, _ path: String) throws -> URL {
        var base = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        while base.hasSuffix("/") { base.removeLast() }
        guard let url = URL(string: base + path) else {
            throw DeliveryError(message: "baseUrl 非法：\(baseUrl)")
        }
        return url
    }

    private func login(baseUrl: String, username: String, password: String) async throws -> String {
        let url = try makeURL(baseUrl, "/api/auth/login")
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(
            withJSONObject: ["username": username, "password": password]
        )

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw DeliveryError(message: "登录无 HTTP 响应")
        }
        guard (200..<300).contains(http.statusCode) else {
            throw DeliveryError(message: "登录失败 HTTP \(http.statusCode) \(Self.preview(data))")
        }

        let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        let payload = root?["data"] as? [String: Any]
        let value = (payload?["token"] as? String) ?? (root?["token"] as? String) ?? ""
        guard !value.isEmpty else {
            throw DeliveryError(message: "登录响应里没有 data.token：\(Self.preview(data))")
        }
        token = value
        tokenUser = username
        Log.shared.info("登录成功：\(username)")
        return value
    }

    private func currentToken(baseUrl: String, username: String, password: String) async throws -> String {
        if let token, tokenUser == username { return token }
        return try await login(baseUrl: baseUrl, username: username, password: password)
    }

    // MARK: - 投递

    private enum SendResult {
        case ok
        case unauthorized
        case failed(String)
    }

    /// POST {baseUrl}/api/projects/{projectId}/facts/files
    func upload(
        baseUrl: String,
        username: String,
        password: String,
        projectId: Int,
        fileURL: URL,
        note: String,
        visibility: String
    ) async throws {
        let token = try await currentToken(baseUrl: baseUrl, username: username, password: password)
        let first = try await send(
            baseUrl: baseUrl, projectId: projectId, fileURL: fileURL,
            note: note, visibility: visibility, token: token
        )
        switch first {
        case .ok:
            return
        case .failed(let message):
            throw DeliveryError(message: message)
        case .unauthorized:
            resetToken()
            Log.shared.warn("投递 401，重新登录后重试一次：\(fileURL.lastPathComponent)")
            let fresh = try await currentToken(baseUrl: baseUrl, username: username, password: password)
            let second = try await send(
                baseUrl: baseUrl, projectId: projectId, fileURL: fileURL,
                note: note, visibility: visibility, token: fresh
            )
            switch second {
            case .ok:
                return
            case .unauthorized:
                throw DeliveryError(message: "重登后仍 401（无权限或 projectId 不存在）")
            case .failed(let message):
                throw DeliveryError(message: message)
            }
        }
    }

    private func send(
        baseUrl: String,
        projectId: Int,
        fileURL: URL,
        note: String,
        visibility: String,
        token: String
    ) async throws -> SendResult {
        let data = try Data(contentsOf: fileURL)
        let boundary = "----HepaiDelivery\(UUID().uuidString.replacingOccurrences(of: "-", with: ""))"
        let fileName = fileURL.lastPathComponent
        let mime = UTType(filenameExtension: fileURL.pathExtension)?.preferredMIMEType ?? "application/octet-stream"

        var body = Data()
        func append(_ text: String) { body.append(Data(text.utf8)) }

        append("--\(boundary)\r\n")
        append("Content-Disposition: form-data; name=\"note\"\r\n\r\n")
        append(note + "\r\n")
        append("--\(boundary)\r\n")
        append("Content-Disposition: form-data; name=\"visibility\"\r\n\r\n")
        append(visibility + "\r\n")
        append("--\(boundary)\r\n")
        append("Content-Disposition: form-data; name=\"file\"; filename=\"\(fileName)\"\r\n")
        append("Content-Type: \(mime)\r\n\r\n")
        body.append(data)
        append("\r\n--\(boundary)--\r\n")

        let url = try makeURL(baseUrl, "/api/projects/\(projectId)/facts/files")
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.setValue(String(body.count), forHTTPHeaderField: "Content-Length")
        request.httpBody = body

        let (responseData, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            return .failed("投递无 HTTP 响应")
        }
        if http.statusCode == 401 { return .unauthorized }
        guard (200..<300).contains(http.statusCode) else {
            return .failed("HTTP \(http.statusCode) \(Self.preview(responseData))")
        }
        return .ok
    }

    private static func preview(_ data: Data) -> String {
        guard let text = String(data: data, encoding: .utf8) else { return "\(data.count) 字节" }
        let clipped = text.count > 300 ? String(text.prefix(300)) + "…" : text
        return clipped.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
