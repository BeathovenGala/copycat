import AppKit

struct Release {
    let version: String
    let zipURL: URL
}

enum Updater {
    private static let latestURL = URL(string: "https://api.github.com/repos/BeathovenGala/copycat/releases/latest")!
    private static let assetName = "CopyCat-mac.zip"

    static var current: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0" }

    static func check() async -> Release? {
        var request = URLRequest(url: latestURL)
        request.setValue("CopyCat", forHTTPHeaderField: "User-Agent")
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        guard let result = try? await URLSession.shared.data(for: request),
              (result.1 as? HTTPURLResponse)?.statusCode == 200,
              let json = (try? JSONSerialization.jsonObject(with: result.0)) as? [String: Any],
              let tag = json["tag_name"] as? String,
              let assets = json["assets"] as? [[String: Any]],
              let asset = assets.first(where: { $0["name"] as? String == assetName }),
              let link = asset["browser_download_url"] as? String,
              let url = URL(string: link) else { return nil }
        let version = tag.hasPrefix("v") ? String(tag.dropFirst()) : tag
        return isNewer(version, than: current) ? Release(version: version, zipURL: url) : nil
    }

    static func isNewer(_ remote: String, than local: String) -> Bool {
        let r = remote.split(separator: ".").map { Int($0) ?? 0 }
        let l = local.split(separator: ".").map { Int($0) ?? 0 }
        for i in 0..<max(r.count, l.count) {
            let a = i < r.count ? r[i] : 0
            let b = i < l.count ? l[i] : 0
            if a != b { return a > b }
        }
        return false
    }

    /// Unzips the new app next to the old one, then a detached shell swaps them once this process exits.
    static func install(_ release: Release) async throws {
        let (zip, _) = try await URLSession.shared.download(from: release.zipURL)
        let work = FileManager.default.temporaryDirectory.appendingPathComponent("copycat-update-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
        try run("/usr/bin/ditto", ["-x", "-k", zip.path, work.path])
        let newApp = work.appendingPathComponent("CopyCat.app")
        guard FileManager.default.fileExists(atPath: newApp.path) else { throw CocoaError(.fileNoSuchFile) }

        let target = Bundle.main.bundlePath
        let pid = ProcessInfo.processInfo.processIdentifier
        let script = """
            while kill -0 \(pid) 2>/dev/null; do sleep 0.5; done
            rm -rf "\(target)"
            mv "\(newApp.path)" "\(target)"
            xattr -dr com.apple.quarantine "\(target)" 2>/dev/null
            open "\(target)"
            """
        let swap = Process()
        swap.executableURL = URL(fileURLWithPath: "/bin/sh")
        swap.arguments = ["-c", script]
        try swap.run()
    }

    private static func run(_ tool: String, _ args: [String]) throws {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: tool)
        process.arguments = args
        try process.run()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else { throw CocoaError(.fileReadCorruptFile) }
    }
}
