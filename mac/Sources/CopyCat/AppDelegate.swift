import AppKit
import CoreImage
import ServiceManagement
import UserNotifications

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private static let httpPort: UInt16 = 47820
    private static let discoveryPort: UInt16 = 47821
    private static let menuHistoryItems = 15
    private static let labelLength = 50
    private static let updateInterval: TimeInterval = 12 * 60 * 60
    private static let imageExtensions: Set<String> = ["png", "jpg", "jpeg", "gif", "heic", "webp", "bmp", "tiff"]

    private let store = Store()
    private var statusItem: NSStatusItem!
    private var server: HTTPServer?
    private var isUpdating = false

    func applicationDidFinishLaunching(_ notification: Notification) {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        statusItem.button?.image = NSImage(systemSymbolName: "doc.on.clipboard", accessibilityDescription: "CopyCat")
        store.onChange = { [weak self] in Task { @MainActor in self?.buildMenu() } }

        let launchedMarker = Store.dir.appendingPathComponent(".launched")
        if !FileManager.default.fileExists(atPath: launchedMarker.path) {
            try? SMAppService.mainApp.register()
            FileManager.default.createFile(atPath: launchedMarker.path, contents: nil)
        }
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }

        startServer()
        buildMenu()

        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 30 * 1_000_000_000)
            await checkForUpdates(manual: false)
        }
        Timer.scheduledTimer(withTimeInterval: AppDelegate.updateInterval, repeats: true) { [weak self] _ in
            Task { @MainActor in await self?.checkForUpdates(manual: false) }
        }
    }

    // MARK: Receiving

    private func startServer() {
        let router = Router(
            store: store,
            onText: { [weak self] text, from in Task { @MainActor in self?.received(text: text, from: from) } },
            onFile: { [weak self] url, mime, from in Task { @MainActor in self?.received(file: url, mime: mime, from: from) } },
            onPaired: { [weak self] device in Task { @MainActor in self?.notify("Phone connected", "\(device) can now send to this Mac") } }
        )
        let server = HTTPServer(handler: router.handle)
        do {
            try server.start(port: AppDelegate.httpPort)
            self.server = server
        } catch {
            notify("CopyCat couldn't start", error.localizedDescription)
        }
        let reply = (try? JSONSerialization.data(withJSONObject: [
            "app": "copycat", "name": Router.machineName, "port": Int(AppDelegate.httpPort),
        ])) ?? Data()
        Discovery.start(port: AppDelegate.discoveryPort, reply: reply)
    }

    private func received(text: String, from: String) {
        Clipboard.setText(text)
        store.add(HistoryItem(kind: "text", text: text, filePath: nil, at: Date(), from: from))
        notify("Text from \(from)", shorten(text))
    }

    private func received(file url: URL, mime: String, from: String) {
        let isImage = mime.hasPrefix("image/") || AppDelegate.imageExtensions.contains(url.pathExtension.lowercased())
        Clipboard.setFile(url, isImage: isImage)
        store.add(HistoryItem(kind: isImage ? "image" : "file", text: url.lastPathComponent, filePath: url.path, at: Date(), from: from))
        notify(isImage ? "Image from \(from)" : "File from \(from)", "\(url.lastPathComponent) — copied, saved to Downloads/CopyCat")
    }

    private func notify(_ title: String, _ body: String) {
        guard store.config.notify else { return }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
    }

    // MARK: Menu

    private func buildMenu() {
        let config = store.config
        let menu = NSMenu()
        menu.addItem(disabled("CopyCat v\(Updater.current)"))
        menu.addItem(disabled(config.autoAccept ? "Phones on this Wi-Fi connect automatically" : "Pairing PIN: \(config.pin)"))
        menu.addItem(disabled("Address: \(AppDelegate.localAddresses().joined(separator: ", "))"))
        menu.addItem(.separator())

        let history = store.history
        if history.isEmpty { menu.addItem(disabled("Nothing received yet")) }
        for item in history.prefix(AppDelegate.menuHistoryItems) {
            let prefix = item.kind == "image" ? "🖼 " : item.kind == "file" ? "📄 " : ""
            let entry = ActionItem(prefix + shorten(item.text)) { [weak self] in self?.recopy(item) }
            entry.toolTip = "\(item.from) · \(DateFormatter.localizedString(from: item.at, dateStyle: .short, timeStyle: .short))"
            menu.addItem(entry)
        }
        menu.addItem(.separator())

        menu.addItem(ActionItem("Connect iPhone or another phone…") { [weak self] in self?.showConnect() })
        menu.addItem(ActionItem("Open received folder") {
            try? FileManager.default.createDirectory(at: Store.receivedDir, withIntermediateDirectories: true)
            NSWorkspace.shared.open(Store.receivedDir)
        })

        let settings = NSMenu()
        settings.addItem(toggle("Start at login", SMAppService.mainApp.status == .enabled) { on in
            if on { try? SMAppService.mainApp.register() } else { try? SMAppService.mainApp.unregister() }
        })
        settings.addItem(toggle("Connect new phones automatically", config.autoAccept) { [weak self] on in
            self?.store.update { $0.autoAccept = on }
        })
        settings.addItem(toggle("Show notifications", config.notify) { [weak self] on in
            self?.store.update { $0.notify = on }
        })
        settings.addItem(ActionItem("Check for updates") { [weak self] in
            Task { @MainActor in await self?.checkForUpdates(manual: true) }
        })
        settings.addItem(ActionItem("New pairing PIN") { [weak self] in self?.store.update { $0.pin = Store.newPin() } })
        settings.addItem(ActionItem("Unpair all phones") { [weak self] in self?.store.update { $0.tokens = [] } })
        settings.addItem(ActionItem("Clear history") { [weak self] in self?.store.clearHistory() })
        let settingsItem = NSMenuItem(title: "Settings", action: nil, keyEquivalent: "")
        settingsItem.submenu = settings
        menu.addItem(settingsItem)

        menu.addItem(ActionItem("Quit CopyCat") { NSApp.terminate(nil) })
        statusItem.menu = menu
    }

    private func disabled(_ title: String) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        item.isEnabled = false
        return item
    }

    private func toggle(_ title: String, _ isOn: Bool, _ set: @escaping (Bool) -> Void) -> NSMenuItem {
        let item = ActionItem(title) { set(!isOn) }
        item.state = isOn ? .on : .off
        return item
    }

    private func recopy(_ item: HistoryItem) {
        if item.kind == "text" {
            Clipboard.setText(item.text)
        } else if let path = item.filePath, FileManager.default.fileExists(atPath: path) {
            Clipboard.setFile(URL(fileURLWithPath: path), isImage: item.kind == "image")
        } else {
            notify("File is gone", item.text)
            return
        }
        notify("Copied", shorten(item.text))
    }

    private func showConnect() {
        let url = "http://\(AppDelegate.localAddresses().first ?? "localhost"):\(AppDelegate.httpPort)/"
        let alert = NSAlert()
        alert.messageText = "Connect a phone to CopyCat"
        alert.informativeText = "Scan with the phone's camera, then in Safari tap Share → Add to Home Screen.\nAndroid phones can use the CopyCat app instead.\n\n\(url)"
        if let qr = AppDelegate.qrImage(url) {
            let view = NSImageView(frame: NSRect(origin: .zero, size: qr.size))
            view.image = qr
            alert.accessoryView = view
        }
        NSApp.activate(ignoringOtherApps: true)
        alert.runModal()
    }

    // MARK: Updates

    private func checkForUpdates(manual: Bool) async {
        guard !isUpdating else { return }
        guard let release = await Updater.check() else {
            if manual { notify("You're up to date", "CopyCat v\(Updater.current)") }
            return
        }
        isUpdating = true
        notify("Updating CopyCat", "Installing v\(release.version)…")
        do {
            try await Updater.install(release)
            NSApp.terminate(nil)
        } catch {
            isUpdating = false
            notify("Update failed", error.localizedDescription)
        }
    }

    // MARK: Helpers

    private func shorten(_ text: String) -> String {
        let flat = text.components(separatedBy: .newlines).joined(separator: " ").trimmingCharacters(in: .whitespaces)
        return flat.count <= AppDelegate.labelLength ? flat : String(flat.prefix(AppDelegate.labelLength - 1)) + "…"
    }

    private static func qrImage(_ text: String) -> NSImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(text.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)) else { return nil }
        let rep = NSCIImageRep(ciImage: output)
        let image = NSImage(size: rep.size)
        image.addRepresentation(rep)
        return image
    }

    static func localAddresses() -> [String] {
        var result: [String] = []
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return ["offline"] }
        defer { freeifaddrs(list) }
        for pointer in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let ifa = pointer.pointee
            guard let addr = ifa.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET),
                  ifa.ifa_flags & UInt32(IFF_LOOPBACK) == 0, ifa.ifa_flags & UInt32(IFF_UP) != 0 else { continue }
            let hostSize = Int(NI_MAXHOST)
            var host = [CChar](repeating: 0, count: hostSize)
            if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(hostSize), nil, 0, NI_NUMERICHOST) == 0 {
                let ip = String(cString: host)
                if !ip.hasPrefix("169.254") { result.append(ip) }
            }
        }
        return result.isEmpty ? ["offline"] : result
    }
}

/// NSMenuItem that runs a closure, so menus don't need a selector per item.
final class ActionItem: NSMenuItem {
    private let handler: () -> Void

    init(_ title: String, handler: @escaping () -> Void) {
        self.handler = handler
        super.init(title: title, action: #selector(fire), keyEquivalent: "")
        target = self
    }

    required init(coder: NSCoder) { fatalError("not used") }

    @objc private func fire() { handler() }
}
