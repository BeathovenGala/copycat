// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "CopyCat",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(name: "CopyCat", path: "Sources/CopyCat"),
    ]
)
