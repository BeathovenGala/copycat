import Foundation

/// Same endpoints as the Windows app, so the Android app and web page work unchanged.
final class Router: @unchecked Sendable {
    private static let maxPairFailures = 5
    private static let pairLockout: TimeInterval = 60

    private let store: Store
    private let onText: (String, String) -> Void
    private let onFile: (URL, String, String) -> Void
    private let onPaired: (String) -> Void
    private let lock = NSLock()
    private var pairFailures = 0
    private var pairLockedUntil = Date.distantPast

    init(store: Store,
         onText: @escaping (String, String) -> Void,
         onFile: @escaping (URL, String, String) -> Void,
         onPaired: @escaping (String) -> Void) {
        self.store = store
        self.onText = onText
        self.onFile = onFile
        self.onPaired = onPaired
    }

    func handle(_ req: HTTPRequest) -> HTTPResponse {
        switch (req.method, req.path) {
        case ("GET", "/"): return page()
        case ("GET", "/ping"): return .json(["app": "copycat", "name": Router.machineName])
        case ("POST", "/pair"): return pair(req)
        case ("POST", "/text"): return text(req)
        case ("POST", "/file"): return file(req)
        default: return HTTPResponse(status: 404)
        }
    }

    static var machineName: String { Host.current().localizedName ?? ProcessInfo.processInfo.hostName }

    private func page() -> HTTPResponse {
        guard let url = Bundle.main.url(forResource: "index", withExtension: "html"),
              let html = try? Data(contentsOf: url) else { return HTTPResponse(status: 404) }
        return HTTPResponse(status: 200, contentType: "text/html; charset=utf-8", body: html)
    }

    private func pair(_ req: HTTPRequest) -> HTTPResponse {
        let isLocked = lock.locked { Date() < pairLockedUntil }
        if isLocked { return HTTPResponse(status: 429) }
        let json = (try? JSONSerialization.jsonObject(with: req.body)) as? [String: Any] ?? [:]
        let pin = (json["pin"] as? String ?? "").trimmingCharacters(in: .whitespaces)
        guard let token = store.pair(pin: pin) else {
            lock.locked {
                pairFailures += 1
                if pairFailures >= Router.maxPairFailures {
                    pairFailures = 0
                    pairLockedUntil = Date().addingTimeInterval(Router.pairLockout)
                }
            }
            return HTTPResponse(status: 403)
        }
        lock.locked { pairFailures = 0 }
        onPaired(json["device"] as? String ?? "phone")
        return .json(["token": token, "name": Router.machineName])
    }

    private func text(_ req: HTTPRequest) -> HTTPResponse {
        guard store.isValid(token: req.headers["x-copycat-token"]) else { return HTTPResponse(status: 401) }
        let text = String(decoding: req.body, as: UTF8.self)
        guard !text.isEmpty else { return HTTPResponse(status: 400) }
        onText(text, device(req))
        return HTTPResponse(status: 200)
    }

    private func file(_ req: HTTPRequest) -> HTTPResponse {
        guard store.isValid(token: req.headers["x-copycat-token"]) else { return HTTPResponse(status: 401) }
        do {
            try FileManager.default.createDirectory(at: Store.receivedDir, withIntermediateDirectories: true)
            let url = Router.uniqueURL(for: Router.safeName(req.query["name"] ?? ""))
            try req.body.write(to: url)
            onFile(url, req.headers["content-type"] ?? "application/octet-stream", device(req))
            return HTTPResponse(status: 200)
        } catch {
            return HTTPResponse(status: 500)
        }
    }

    private func device(_ req: HTTPRequest) -> String {
        let d = req.headers["x-copycat-device"]?.trimmingCharacters(in: .whitespaces) ?? ""
        return d.isEmpty ? "phone" : d
    }

    private static func safeName(_ name: String) -> String {
        let base = (name as NSString).lastPathComponent
            .replacingOccurrences(of: ":", with: "_")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if base.isEmpty || base == "." || base == ".." {
            let f = DateFormatter()
            f.dateFormat = "yyyyMMdd-HHmmss"
            return "shared-\(f.string(from: Date()))"
        }
        return base
    }

    private static func uniqueURL(for name: String) -> URL {
        let first = Store.receivedDir.appendingPathComponent(name)
        guard FileManager.default.fileExists(atPath: first.path) else { return first }
        let stem = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        for i in 2... {
            let candidate = Store.receivedDir.appendingPathComponent(ext.isEmpty ? "\(stem) (\(i))" : "\(stem) (\(i)).\(ext)")
            if !FileManager.default.fileExists(atPath: candidate.path) { return candidate }
        }
        return first
    }
}
