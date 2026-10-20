"""Builds the Miro canvas-composer SVG for the handover board from the .mmd sources."""
from html import escape
from pathlib import Path

HERE = Path(__file__).parent

VIEWS = [
    ("00-legend", "0. Legend", "Colours and shapes mean the same thing in every view."),
    ("01-system-overview", "1. System overview", "Phone and laptop talk directly over Wi-Fi. GitHub is only used for downloads and updates."),
    ("02-discovery-pairing", "2. Finding and pairing the laptop", "Runs every time the phone needs the laptop. No PIN unless the laptop owner turns auto-connect off."),
    ("03-send-path", "3. Sending something", "Every way to send ends in the same two HTTP calls on the laptop."),
    ("04-auto-copy", "4. Auto-send on copy (Android trick)", "Android hides the clipboard from background apps, so CopyCat reacts to the system's own denial log."),
    ("05-offline-queue", "5. Offline queue and reconnect", "Nothing sent while apart is lost. Items wait up to 24 hours and go out on the next trigger."),
    ("06-auto-updates", "6. Auto-updates", "Each app checks GitHub for a newer release. Laptops update silently; Android asks the user to confirm."),
    ("07-release-pipeline", "7. Shipping a new version", "One script builds and publishes Windows and Android; GitHub Actions adds the Mac build."),
]

FRAME_W, FRAME_H = 2600, 1800
GAP = 320
COLS = 4
PAD = 64
TOP = 600


def frame(i: int, key: str, title: str, caption: str) -> str:
    x = (i % COLS) * (FRAME_W + GAP)
    y = TOP + (i // COLS) * (FRAME_H + GAP)
    mermaid = escape((HERE / f"{key}.mmd").read_text(encoding="utf-8"), quote=False)
    return f"""  <g id="f{i}" transform="translate({x},{y})" data-frame="{escape(title)}">
    <rect data-type="frame" x="0" y="0" width="{FRAME_W}" height="{FRAME_H}" fill="#ffffff" data-title="{escape(title)}" />
    <text x="{PAD}" y="{PAD + 56}" font-family="noto_sans" font-size="64" font-weight="bold" fill="#1a1a1a">{escape(title)}</text>
    <textArea x="{PAD}" y="{PAD + 90}" width="{FRAME_W - 2 * PAD}" font-family="noto_sans" font-size="28" fill="#595959">{escape(caption)}</textArea>
    <foreignObject id="d{i}" x="{PAD}" y="{PAD + 180}" width="1600" height="900" data-type="diagram" data-title="{escape(title)}">
{mermaid}</foreignObject>
  </g>"""


def header() -> str:
    return f"""  <text x="0" y="120" font-family="noto_sans" font-size="120" font-weight="bold" fill="#1a1a1a">CopyCat knowledge handover</text>
  <textArea x="0" y="180" width="3600" font-family="noto_sans" font-size="36" fill="#595959">Send what you copy, share or screenshot on a phone straight to a laptop clipboard, over the same Wi-Fi, no cloud.<br/>Android app, Windows tray app, Mac menu bar app, and a web page for iPhones. Code and downloads: https://github.com/BeathovenGala/copycat<br/>Read the views in order. Every number on these diagrams comes from a constant in the code.</textArea>"""


svg = "<svg>\n" + header() + "\n" + "\n".join(frame(i, *v) for i, v in enumerate(VIEWS)) + "\n</svg>\n"
out = HERE / "board.svg"
out.write_text(svg, encoding="utf-8")
print(out, len(svg))
