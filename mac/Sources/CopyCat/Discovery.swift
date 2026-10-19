import Darwin
import Foundation

/// Answers the phone's "COPYCAT_DISCOVER" UDP broadcast. BSD sockets, since Network.framework
/// listeners don't reliably receive broadcast packets.
enum Discovery {
    static func start(port: UInt16, reply: Data) {
        Thread.detachNewThread {
            let fd = socket(AF_INET, SOCK_DGRAM, 0)
            guard fd >= 0 else { return }
            var yes: Int32 = 1
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
            setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &yes, socklen_t(MemoryLayout<Int32>.size))

            var addr = sockaddr_in()
            addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
            addr.sin_family = sa_family_t(AF_INET)
            addr.sin_port = port.bigEndian
            addr.sin_addr = in_addr(s_addr: 0)
            let bound = withUnsafePointer(to: &addr) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
            }
            guard bound == 0 else {
                close(fd)
                return
            }

            let bufferSize = 1024
            var buffer = [UInt8](repeating: 0, count: bufferSize)
            while true {
                var from = sockaddr_storage()
                var fromLength = socklen_t(MemoryLayout<sockaddr_storage>.size)
                let count = withUnsafeMutablePointer(to: &from) {
                    $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { recvfrom(fd, &buffer, bufferSize, 0, $0, &fromLength) }
                }
                guard count > 0, String(decoding: buffer[0..<count], as: UTF8.self) == "COPYCAT_DISCOVER" else { continue }
                reply.withUnsafeBytes { bytes in
                    _ = withUnsafePointer(to: &from) {
                        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { sendto(fd, bytes.baseAddress, reply.count, 0, $0, fromLength) }
                    }
                }
            }
        }
    }
}
