import Foundation
import Network

struct HTTPRequest {
    let method: String
    let path: String
    let query: [String: String]
    let headers: [String: String]
    let body: Data
}

struct HTTPResponse {
    var status: Int
    var contentType = "text/plain; charset=utf-8"
    var body = Data()

    static func json(_ object: [String: Any]) -> HTTPResponse {
        HTTPResponse(status: 200, contentType: "application/json", body: (try? JSONSerialization.data(withJSONObject: object)) ?? Data())
    }
}

/// Minimal HTTP/1.1 server: one request per connection, Content-Length or chunked bodies.
final class HTTPServer: @unchecked Sendable {
    static let maxRequestBytes = 512 * 1024 * 1024

    private let queue = DispatchQueue(label: "copycat.http", attributes: .concurrent)
    private let handler: (HTTPRequest) -> HTTPResponse
    private var listener: NWListener?

    init(handler: @escaping (HTTPRequest) -> HTTPResponse) {
        self.handler = handler
    }

    func start(port: UInt16) throws {
        let params = NWParameters.tcp
        params.allowLocalEndpointReuse = true
        let listener = try NWListener(using: params, on: NWEndpoint.Port(rawValue: port)!)
        listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
        listener.start(queue: queue)
        self.listener = listener
    }

    private func accept(_ connection: NWConnection) {
        connection.start(queue: queue)
        read(connection, RequestReader())
    }

    private func read(_ connection: NWConnection, _ reader: RequestReader) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 1 << 20) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            switch reader.feed(data ?? Data()) {
            case .complete(let request): self.respond(connection, self.handler(request))
            case .invalid: self.respond(connection, HTTPResponse(status: 400))
            case .tooLarge: self.respond(connection, HTTPResponse(status: 413))
            case .incomplete:
                if isComplete || error != nil { connection.cancel() } else { self.read(connection, reader) }
            }
        }
    }

    private func respond(_ connection: NWConnection, _ response: HTTPResponse) {
        let head = "HTTP/1.1 \(response.status) \(HTTPServer.reason(response.status))\r\n" +
            "Content-Type: \(response.contentType)\r\nContent-Length: \(response.body.count)\r\nConnection: close\r\n\r\n"
        var data = Data(head.utf8)
        data.append(response.body)
        connection.send(content: data, completion: .contentProcessed { _ in connection.cancel() })
    }

    private static func reason(_ status: Int) -> String {
        switch status {
        case 200: return "OK"
        case 400: return "Bad Request"
        case 401: return "Unauthorized"
        case 403: return "Forbidden"
        case 404: return "Not Found"
        case 413: return "Payload Too Large"
        case 429: return "Too Many Requests"
        default: return "Error"
        }
    }
}

private final class RequestReader {
    enum Result {
        case incomplete, invalid, tooLarge
        case complete(HTTPRequest)
    }

    private struct Head {
        let method: String
        let path: String
        let query: [String: String]
        let headers: [String: String]
        let bodyStart: Int
    }

    private static let crlf = Data("\r\n".utf8)
    private static let headerEnd = Data("\r\n\r\n".utf8)
    private static let maxHeaderBytes = 64 * 1024

    private var data = Data()
    private var head: Head?
    private var chunkPos = 0
    private var chunkedBody = Data()

    func feed(_ newData: Data) -> Result {
        data.append(newData)
        if data.count > HTTPServer.maxRequestBytes { return .tooLarge }

        if head == nil {
            guard let end = data.range(of: RequestReader.headerEnd) else {
                return data.count > RequestReader.maxHeaderBytes ? .invalid : .incomplete
            }
            guard let parsed = RequestReader.parseHead(data[data.startIndex..<end.lowerBound], bodyStart: end.upperBound) else {
                return .invalid
            }
            head = parsed
            chunkPos = end.upperBound
        }
        guard let head else { return .incomplete }

        if head.headers["transfer-encoding"]?.lowercased().contains("chunked") == true {
            return readChunks(head)
        }
        let length = Int(head.headers["content-length"] ?? "0") ?? 0
        guard data.count >= head.bodyStart + length else { return .incomplete }
        return .complete(request(head, body: data.subdata(in: head.bodyStart..<(head.bodyStart + length))))
    }

    private func readChunks(_ head: Head) -> Result {
        while true {
            guard let lineEnd = data.range(of: RequestReader.crlf, in: chunkPos..<data.count) else { return .incomplete }
            let line = String(decoding: data.subdata(in: chunkPos..<lineEnd.lowerBound), as: UTF8.self)
            let sizeText = line.split(separator: ";").first.map { $0.trimmingCharacters(in: .whitespaces) } ?? ""
            guard let size = Int(sizeText, radix: 16) else { return .invalid }
            if size == 0 { return .complete(request(head, body: chunkedBody)) }
            let start = lineEnd.upperBound
            guard data.count >= start + size + 2 else { return .incomplete }
            chunkedBody.append(data.subdata(in: start..<(start + size)))
            chunkPos = start + size + 2
        }
    }

    private func request(_ head: Head, body: Data) -> HTTPRequest {
        HTTPRequest(method: head.method, path: head.path, query: head.query, headers: head.headers, body: body)
    }

    private static func parseHead(_ raw: Data, bodyStart: Int) -> Head? {
        let lines = String(decoding: raw, as: UTF8.self).components(separatedBy: "\r\n")
        let parts = lines.first?.split(separator: " ") ?? []
        guard parts.count >= 2 else { return nil }
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { continue }
            headers[line[..<colon].lowercased()] = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
        }
        guard let components = URLComponents(string: "http://localhost" + parts[1]) else { return nil }
        var query: [String: String] = [:]
        for item in components.queryItems ?? [] { query[item.name] = item.value ?? "" }
        return Head(method: String(parts[0]), path: components.path, query: query, headers: headers, bodyStart: bodyStart)
    }
}
