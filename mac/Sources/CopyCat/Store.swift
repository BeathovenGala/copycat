import Foundation

struct HistoryItem: Codable {
    let kind: String
    let text: String
    let filePath: String?
    let at: Date
    let from: String
}

struct Config: Codable {
    var pin = Store.newPin()
    var tokens: [String] = []
    var autoAccept = true
    var notify = true
}

/// Settings and history, shared between the server queue and the main thread.
final class Store: @unchecked Sendable {
    static let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("CopyCat", isDirectory: true)
    static let receivedDir = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("CopyCat", isDirectory: true)
    private static let maxHistory = 50

    private let lock = NSLock()
    private let configURL = Store.dir.appendingPathComponent("config.json")
    private let historyURL = Store.dir.appendingPathComponent("history.json")
    private var _config: Config
    private var _history: [HistoryItem]

    var onChange: (() -> Void)?

    var config: Config { lock.locked { _config } }
    var history: [HistoryItem] { lock.locked { _history } }

    init() {
        try? FileManager.default.createDirectory(at: Store.dir, withIntermediateDirectories: true)
        _config = Store.load(Config.self, from: configURL) ?? Config()
        _history = Store.load([HistoryItem].self, from: historyURL) ?? []
        Store.save(_config, to: configURL)
    }

    static func newPin() -> String { String(format: "%06d", Int.random(in: 0..<1_000_000)) }

    func isValid(token: String?) -> Bool {
        guard let token, !token.isEmpty else { return false }
        return lock.locked { _config.tokens.contains(token) }
    }

    func pair(pin: String) -> String? {
        let token: String? = lock.locked {
            guard _config.autoAccept || pin == _config.pin else { return nil }
            let token = (0..<24).map { _ in String(format: "%02X", UInt8.random(in: 0...255)) }.joined()
            _config.tokens.append(token)
            Store.save(_config, to: configURL)
            return token
        }
        return token
    }

    func add(_ item: HistoryItem) {
        lock.locked {
            _history = Array(([item] + _history.filter { !($0.kind == item.kind && $0.text == item.text) }).prefix(Store.maxHistory))
            Store.save(_history, to: historyURL)
        }
        onChange?()
    }

    func update(_ change: (inout Config) -> Void) {
        lock.locked {
            change(&_config)
            Store.save(_config, to: configURL)
        }
        onChange?()
    }

    func clearHistory() {
        lock.locked {
            _history = []
            Store.save(_history, to: historyURL)
        }
        onChange?()
    }

    private static func load<T: Decodable>(_ type: T.Type, from url: URL) -> T? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try? decoder.decode(type, from: data)
    }

    private static func save<T: Encodable>(_ value: T, to url: URL) {
        let encoder = JSONEncoder()
        encoder.outputFormatting = .prettyPrinted
        encoder.dateEncodingStrategy = .iso8601
        guard let data = try? encoder.encode(value) else { return }
        try? data.write(to: url, options: .atomic)
    }
}

extension NSLock {
    func locked<T>(_ body: () throws -> T) rethrows -> T {
        lock()
        defer { unlock() }
        return try body()
    }
}
