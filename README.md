# CopyCat

Send what you copy, share or screenshot on your phone straight to your laptop's clipboard (Windows or Mac; Android app, or any phone's browser), over your own Wi-Fi. No account, no cloud — the phone talks directly to the laptop.

## Install

Phone and laptop must be on the **same Wi-Fi**.

**Laptop (Windows 10/11)**
1. Download **[CopyCat.exe](https://github.com/BeathovenGala/copycat/releases/latest/download/CopyCat.exe)** and run it.
2. If Windows says "Windows protected your PC", click **More info → Run anyway**.
3. If a firewall popup appears, tick both boxes and click **Allow**. (Or later: tray icon → Settings → Allow through firewall.)

CopyCat now lives in the system tray and starts with Windows.

**Laptop (Mac, macOS 13+)** — *new, not yet tested on a real Mac*
1. Download **[CopyCat-mac.zip](https://github.com/BeathovenGala/copycat/releases/latest/download/CopyCat-mac.zip)**, open it, and drag **CopyCat.app** into **Applications**.
2. The app isn't notarized by Apple, so the first time **right-click it → Open → Open**. (On macOS 15+: try to open it once, then System Settings → Privacy & Security → **Open Anyway**.)
3. If asked to allow incoming network connections, click **Allow**.

CopyCat lives in the menu bar and starts at login.

**Phone (Android 8+)**
1. Open this page on your phone and download **[CopyCat.apk](https://github.com/BeathovenGala/copycat/releases/latest/download/CopyCat.apk)**.
2. Tap it and allow installing from your browser if asked. If Play Protect warns, tap **Install anyway**.
3. Open CopyCat — it finds your laptop by itself.

**iPhone / iPad (or any phone without the app)**

Apple doesn't allow installing apps outside the App Store, so iPhones use a web page the laptop serves:
1. On the laptop: tray / menu bar icon → **Connect iPhone or another phone…** and scan the QR code.
2. In Safari tap **Share → Add to Home Screen**. Open it any time to paste text or send photos and files.
3. Optional: under *Use with Apple Shortcuts* on that page you'll find the address and key to build a Shortcut (e.g. "Get Clipboard → Get Contents of URL") and run it from the share sheet or **Back Tap** (Settings → Accessibility → Touch → Back Tap).

iOS doesn't let any app read the clipboard or watch for screenshots in the background, so iPhone sending is always one tap.

## Use it

- **Share** any photo, file, link or text → **CopyCat → Laptop**. It lands on the laptop's clipboard (Ctrl+V) and in `Downloads\CopyCat`.
- **Screenshots:** turn on *Send screenshots automatically* — every new screenshot appears on the laptop.
- **Copied text:** tap *Send clipboard* in the notification or the Quick Settings tile.
- The laptop tray menu keeps your recent items; click one to copy it again.

### Optional: auto-send everything you copy

Android hides the clipboard from background apps. To let CopyCat send every copy automatically, connect the phone to a computer with [USB debugging](https://developer.android.com/studio/debug/dev-options) on and run once:

```
adb shell pm grant com.copycat.app android.permission.READ_LOGS
```

(On OnePlus/Oppo/Realme first enable *Developer options → Disable permission monitoring*.) Then turn on *Send automatically when I copy*.

## Connecting

The phone finds the laptop by itself on the same Wi-Fi — no PIN. If it sees several CopyCat laptops the first time, it asks which one is yours. After that it only ever sends to that laptop; use **Find laptop** to switch.

Not on the same Wi-Fi? Anything you send waits on the phone and is delivered automatically once you're back (within seconds if a background switch is on, otherwise within ~5 minutes or when you open the app).

## Updates

Both apps check for new versions and update themselves.

## Privacy

Data only travels over your local network, unencrypted. Phones on the same Wi-Fi connect automatically; turn that off in the tray menu → Settings → *Connect new phones automatically* on networks you don't trust.

## Building

- Windows: .NET 8 — `dotnet build desktop`
- Mac: Swift 5.9 on macOS — `mac/build.sh` (CI builds it on every push and attaches it to releases)
- Android: JDK 17 + Android SDK — `android/gradlew assembleDebug`
- Release: bump `VERSION`, then `tools/release.ps1`
