# CopyCat

Send what you copy, share or screenshot on your Android phone straight to your Windows laptop's clipboard, over your own Wi-Fi. No account, no cloud — the phone talks directly to the laptop.

## Install

Phone and laptop must be on the **same Wi-Fi**.

**Laptop (Windows 10/11)**
1. Download **[CopyCat.exe](https://github.com/BeathovenGala/copycat/releases/latest/download/CopyCat.exe)** and run it.
2. If Windows says "Windows protected your PC", click **More info → Run anyway**.
3. If a firewall popup appears, tick both boxes and click **Allow**. (Or later: tray icon → Settings → Allow through firewall.)

CopyCat now lives in the system tray and starts with Windows.

**Phone (Android 8+)**
1. Open this page on your phone and download **[CopyCat.apk](https://github.com/BeathovenGala/copycat/releases/latest/download/CopyCat.apk)**.
2. Tap it and allow installing from your browser if asked. If Play Protect warns, tap **Install anyway**.
3. Open CopyCat — it finds your laptop by itself.

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

## Updates

Both apps check for new versions and update themselves.

## Privacy

Data only travels over your local network, unencrypted. Phones on the same Wi-Fi connect automatically; turn that off in the tray menu → Settings → *Connect new phones automatically* on networks you don't trust.

## Building

- Desktop: .NET 8 — `dotnet build desktop`
- Android: JDK 17 + Android SDK — `android/gradlew assembleDebug`
- Release: bump `VERSION`, then `tools/release.ps1`
