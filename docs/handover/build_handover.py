"""Builds CopyCat-Handover.html (self-contained, diagrams inlined) and the handover zip."""
import base64
import re
import zipfile
from pathlib import Path

import markdown

HERE = Path(__file__).parent
ROOT = HERE.parents[1]
MD = HERE / "CopyCat-Handover.md"
HTML = HERE / "CopyCat-Handover.html"
ZIP = ROOT / "dist" / "CopyCat-Handover.zip"
MIRO_URL = "https://miro.com/app/board/uXjVEX6DqZg=/"

CSS = """
:root { --bg:#ffffff; --text:#1a1a1a; --muted:#595959; --line:#e7e7e7; --code:#f7f7f7; --accent:#6631d7; }
@media (prefers-color-scheme: dark) {
  :root { --bg:#121117; --text:#f1eff8; --muted:#a29fb3; --line:#2e2b3a; --code:#1d1b25; --accent:#9D85FF; }
  img { background:#ffffff; border-radius:8px; }
}
body { margin:0; background:var(--bg); color:var(--text);
  font:16px/1.6 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
main { max-width:980px; margin:0 auto; padding:32px 16px 96px; }
h1 { font-size:34px; line-height:1.2; } h2 { margin-top:48px; font-size:24px; } h3 { font-size:18px; }
p, li { color:var(--text); } em { color:var(--muted); }
a { color:var(--accent); }
img { max-width:100%; height:auto; display:block; margin:16px 0; border:1px solid var(--line); }
table { border-collapse:collapse; width:100%; margin:16px 0; font-size:14px; display:block; overflow-x:auto; }
th, td { border:1px solid var(--line); padding:8px 10px; text-align:left; vertical-align:top; }
th { background:var(--code); }
code { background:var(--code); padding:1px 5px; border-radius:4px; font-size:13px; }
pre { background:var(--code); padding:14px; border-radius:8px; overflow-x:auto; }
pre code { padding:0; }
hr { border:0; border-top:1px solid var(--line); margin:40px 0; }
"""


def inline_images(html: str) -> str:
    def repl(m: re.Match) -> str:
        src = HERE / m.group(1)
        data = base64.b64encode(src.read_bytes()).decode()
        return f'src="data:image/png;base64,{data}"'
    return re.sub(r'src="(diagrams/[^"]+\.png)"', repl, html)


def build_html() -> None:
    body = markdown.markdown(MD.read_text(encoding="utf-8"), extensions=["tables", "fenced_code"])
    page = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>CopyCat Handover</title><style>{CSS}</style></head>
<body><main>{inline_images(body)}</main></body></html>
"""
    HTML.write_text(page, encoding="utf-8")


def build_zip() -> None:
    ZIP.parent.mkdir(exist_ok=True)
    readme = f"""CopyCat knowledge handover
==========================

Start here: CopyCat-Handover.html (open in any browser; diagrams are embedded)
Same content in Markdown: CopyCat-Handover.md

diagrams/
  *.png   exported diagrams (2x resolution)
  *.svg   exported diagrams (vector)
  *.mmd   Mermaid source for each diagram
  make_sources.py, render.ps1, make_board_svg.py   regenerate everything
  board.svg   the Miro board layout source

Miro board (editable): {MIRO_URL}
Code: https://github.com/BeathovenGala/copycat
"""
    with zipfile.ZipFile(ZIP, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("CopyCat-Handover/README.txt", readme)
        z.writestr("CopyCat-Handover/Miro board.url", f"[InternetShortcut]\r\nURL={MIRO_URL}\r\n")
        z.write(HTML, "CopyCat-Handover/CopyCat-Handover.html")
        z.write(MD, "CopyCat-Handover/CopyCat-Handover.md")
        for f in sorted((HERE / "diagrams").iterdir()):
            if f.suffix in {".png", ".svg", ".mmd", ".py", ".ps1"}:
                z.write(f, f"CopyCat-Handover/diagrams/{f.name}")
    print(ZIP, ZIP.stat().st_size)


build_html()
build_zip()
