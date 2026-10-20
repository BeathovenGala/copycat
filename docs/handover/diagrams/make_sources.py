"""Writes the Mermaid sources for the handover diagrams. Edit here, then re-run render.ps1."""
import re
from pathlib import Path

HERE = Path(__file__).parent

CLASSES = """    classDef ok fill:#B3E65F,stroke:#6E9A24,color:#2F440B
    classDef bad fill:#FFC6C6,stroke:#D64545,color:#6B0A0A
    classDef wait fill:#FFE86D,stroke:#A28E26,color:#574900
    classDef auto fill:#9CE6FF,stroke:#2C97BB,color:#1C4657
    classDef ext fill:#DDDDD8,stroke:#8A8A7E,color:#434339
    classDef default fill:#FFFFFF,stroke:#757575,color:#313131
    classDef grp fill:#F7F7F7,stroke:#B0B0B0,color:#313131"""

DIAGRAMS = {
    "00-legend": """flowchart LR
{classes}
    subgraph colour["Colour"]
        c1["Green = worked, the happy path"]:::ok
        c2["Red = failed or stopped"]:::bad
        c3["Yellow = waiting, retrying, or switched off"]:::wait
        c4["Blue = automatic, no human involved"]:::auto
        c5["Grey = outside CopyCat: OS, GitHub, other apps"]:::ext
    end
    subgraph shape["Shape"]
        s1(["Rounded = start or finish"])
        s2["Box = an action"]
        s3{"Diamond = a decision"}
        s4[("Cylinder = stored data")]
    end
""",
    "01-system-overview": """flowchart LR
{classes}
    subgraph phone["Phone"]
        android["Android app<br/>share, clipboard, screenshots"]:::ok
        web["Web page in a browser<br/>iPhone or any phone"]:::ok
    end
    subgraph wifi["Same Wi-Fi network, no cloud"]
        udp["UDP port 47821<br/>find the laptop"]:::auto
        http["HTTP port 47820<br/>pair and send"]:::auto
    end
    subgraph laptop["Laptop"]
        win["Windows tray app<br/>.NET 8, starts with Windows"]:::ok
        mac["Mac menu bar app<br/>Swift, starts at login"]:::ok
    end
    clip(["Laptop clipboard<br/>ready to paste"]):::ok
    files[("Downloads/CopyCat<br/>received files")]
    hist[("History<br/>last 50 items")]
    gh[/"GitHub Releases<br/>downloads and updates"/]:::ext

    android -->|"broadcast: who is CopyCat?"| udp
    android -->|"text and files"| http
    web -->|"page is served by the laptop"| http
    udp --> win
    udp --> mac
    http --> win
    http --> mac
    win --> clip
    mac --> clip
    win --> files
    win --> hist
    mac --> files
    mac --> hist
    gh -.->|"self-update checks"| android
    gh -.->|"self-update checks"| win
    gh -.->|"self-update checks"| mac
""",
    "02-discovery-pairing": """flowchart TD
{classes}
    start(["Phone needs the laptop<br/>app opened, or something to send"]):::ok
    ping{"Saved address answers<br/>GET /ping?"}
    use(["Use saved laptop"]):::ok
    bcast["Broadcast COPYCAT_DISCOVER<br/>listen 1.5 s for replies"]:::auto
    paired{"Paired before?<br/>has a saved key"}
    same{"Saved laptop name<br/>among the replies?"}
    update["Update saved address<br/>laptop IP changed"]:::auto
    gone(["Not reachable<br/>item waits in queue"]):::wait
    count{"How many laptops<br/>replied?"}
    none(["None: type the IP<br/>or retry later"]):::bad
    choose["Ask: which laptop is yours?"]:::wait
    pair["POST /pair<br/>empty PIN"]:::auto
    accept{"Laptop setting:<br/>connect phones automatically?"}
    pin["Ask for 6-digit PIN<br/>5 wrong tries = 1 min lockout"]:::wait
    save(["Save name, address, key<br/>paired"]):::ok

    start --> ping
    ping -->|"yes"| use
    ping -->|"no"| bcast
    bcast --> paired
    paired -->|"yes"| same
    same -->|"yes"| update --> use
    same -->|"no, never switches on its own"| gone
    paired -->|"no"| count
    count -->|"0"| none
    count -->|"exactly 1"| pair
    count -->|"2 or more"| choose --> pair
    pair --> accept
    accept -->|"on, the default"| save
    accept -->|"off"| pin --> save
""",
    "03-send-path": """flowchart LR
{classes}
    subgraph capture["Ways to send from the phone"]
        share["Share sheet<br/>CopyCat to Laptop"]
        tile["Quick Settings tile or<br/>notification button"]
        autoc["Auto-send on copy<br/>see view 4"]:::auto
        shots["New screenshot<br/>MediaStore watcher"]:::auto
        webp["Web page<br/>paste or pick files"]
    end
    queue["Sender<br/>one item at a time, saved to history"]:::auto
    post["POST /text or /file<br/>with the phone key"]:::auto
    token{"Laptop: key valid?"}
    repair["Forget key, pair again<br/>then retry once"]:::wait
    kind{"Text or file?"}
    settext["Put text on clipboard"]:::ok
    savefile[("Save to Downloads/CopyCat")]
    img{"Is it an image?"}
    setimg["Clipboard gets image<br/>and the file"]:::ok
    setfile["Clipboard gets the file"]:::ok
    done(["Add to history,<br/>show notification"]):::ok
    fail(["Not delivered<br/>retried later, see view 5"]):::bad

    share --> queue
    tile --> queue
    autoc --> queue
    shots --> queue
    webp --> post
    queue --> post
    post -->|"laptop unreachable"| fail
    post --> token
    token -->|"no, 401"| repair --> post
    token -->|"yes"| kind
    kind -->|"text"| settext --> done
    kind -->|"file"| savefile --> img
    img -->|"yes"| setimg --> done
    img -->|"no"| setfile --> done
""",
    "04-auto-copy": """sequenceDiagram
    actor User
    participant Other as Any other app
    participant OS as Android clipboard service
    participant Watch as CopyCat WatchService
    participant Clip as ClipActivity (invisible)
    participant Laptop
    Note over Watch: Needs READ_LOGS (granted once over adb)<br/>and Display over other apps
    User->>Other: Copy text
    Other->>OS: Set clipboard
    OS-->>Watch: Clipboard changed, CopyCat is in the background
    OS->>OS: Logs Denying clipboard access to com.copycat.app
    Watch->>Watch: logcat reader spots that line, 400 ms debounce
    Watch->>Clip: Open invisible screen for a moment
    Clip->>OS: Read clipboard, allowed because it has focus
    alt Same text as last time
        Clip->>Clip: Skip
    else New text
        Clip->>Laptop: POST /text
        Laptop-->>Clip: 200 OK, now on the laptop clipboard
    end
    Clip->>Clip: Close, the user app gets focus back
    Note over OS,Watch: Android shows a pasted-from-clipboard notice each time.<br/>After a phone restart, open CopyCat once to allow log access again.
""",
    "05-offline-queue": """flowchart TD
{classes}
    failed(["Send failed<br/>item saved as not delivered"]):::bad
    svc{"Background service on?<br/>either auto switch enabled"}
    wifi["Wi-Fi joined callback<br/>wait 3 s for an IP"]:::auto
    tick["Every 30 s while items wait"]:::auto
    job["Android job on unmetered Wi-Fi<br/>every 5 min"]:::auto
    open["User opens CopyCat"]
    retry["Connect and retry<br/>find laptop, see view 2"]:::auto
    found{"Laptop found?"}
    sendall["Send waiting items<br/>oldest first, last 24 h only"]:::ok
    marked(["Marked sent"]):::ok
    waitmore(["Keep waiting<br/>for the next trigger"]):::wait
    old(["Older than 24 h<br/>tap it in the app to resend"]):::wait

    failed --> svc
    svc -->|"yes"| wifi
    svc -->|"yes"| tick
    svc -->|"no"| job
    failed --> open
    wifi --> retry
    tick --> retry
    job --> retry
    open --> retry
    retry --> found
    found -->|"yes"| sendall --> marked
    found -->|"no"| waitmore
    failed -.->|"after 24 h"| old
""",
    "06-auto-updates": """flowchart LR
{classes}
    gh[/"GitHub latest release<br/>api.github.com"/]:::ext
    subgraph windows["Windows laptop"]
        w1["30 s after start,<br/>then every 12 h"]:::auto
        w2["Download CopyCat.exe<br/>as CopyCat.new.exe"]:::auto
        w3["update.cmd waits for exit,<br/>swaps the file, relaunches"]:::auto
        w4(["Updated silently"]):::ok
    end
    subgraph macos["Mac laptop"]
        m1["30 s after start,<br/>then every 12 h"]:::auto
        m2["Download and unzip<br/>CopyCat-mac.zip"]:::auto
        m3["Shell script swaps the app,<br/>clears quarantine, reopens"]:::auto
        m4(["Updated silently"]):::ok
    end
    subgraph androidp["Android phone"]
        a1["On app open or service tick,<br/>at most every 6 h"]:::auto
        a2["Update button and<br/>one notification per version"]:::wait
        a3["User taps: download APK,<br/>system installer asks to confirm"]
        a4{"Signed with the same<br/>release key?"}
        a5(["Installed, settings kept"]):::ok
        a6(["Rejected by Android"]):::bad
    end
    w1 -->|"newer version?"| gh
    m1 -->|"newer version?"| gh
    a1 -->|"newer version?"| gh
    gh -->|"yes, Windows"| w2 --> w3 --> w4
    gh -->|"yes, Mac"| m2 --> m3 --> m4
    gh -->|"yes, Android"| a2 --> a3 --> a4
    a4 -->|"yes"| a5
    a4 -->|"no"| a6
""",
    "07-release-pipeline": """flowchart LR
{classes}
    dev(["Developer bumps VERSION<br/>e.g. 1.2.0 to 1.2.1"])
    script["Run tools/release.ps1<br/>on the Windows dev machine"]
    apk["Build Android APK<br/>signed with the release key"]
    key[("Release key<br/>~/.config/copycat, back it up")]:::wait
    exe["Build Windows exe<br/>self-contained, about 80 MB"]
    push["git push main"]
    rel["gh release create vX<br/>attach APK and exe"]:::ok
    ci["GitHub Actions on a Mac<br/>runs on release published"]:::auto
    macb["mac/build.sh<br/>universal app, ad-hoc signed"]:::auto
    up["Attach CopyCat-mac.zip<br/>a few minutes later"]:::auto
    users(["Installed apps pick it up<br/>on their next update check"]):::ok

    dev --> script
    script --> apk
    key -.->|"signs"| apk
    script --> exe
    apk --> push
    exe --> push
    push --> rel
    rel -->|"release published event"| ci --> macb --> up --> users
    rel -->|"APK and exe live now"| users
""",
}

def with_group_style(body: str) -> str:
    groups = re.findall(r"^\s*subgraph (\w+)", body, re.M)
    if not groups or body.startswith("sequenceDiagram"):
        return body
    return body.rstrip() + f"\n    class {','.join(groups)} grp\n"


for name, body in DIAGRAMS.items():
    (HERE / f"{name}.mmd").write_text(with_group_style(body.replace("{classes}", CLASSES)), encoding="utf-8")
print(f"wrote {len(DIAGRAMS)} sources")
