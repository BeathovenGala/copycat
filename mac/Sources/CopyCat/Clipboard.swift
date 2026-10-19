import AppKit

enum Clipboard {
    static func setText(_ text: String) {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
    }

    /// One pasteboard item carrying both the image data (for apps) and the file URL (for Finder).
    static func setFile(_ url: URL, isImage: Bool) {
        let item = NSPasteboardItem()
        if isImage, let image = NSImage(contentsOf: url), let tiff = image.tiffRepresentation {
            item.setData(tiff, forType: .tiff)
            if let png = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:]) {
                item.setData(png, forType: .png)
            }
        }
        item.setString(url.absoluteString, forType: .fileURL)
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.writeObjects([item])
    }
}
