"""Render app_icon.svg into the PNGs the app and the Play listing use.

Headless Chrome does the rendering: it supports the SVG filters the glow and
shadows use, which ImageMagick's built-in renderer does not.  The results are
then run through pngcrush, natively or through WSL, when it can be found.

    python files/render_icons.py

Set CHROME to the browser's path if it is not in the usual place.
"""
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
RES = HERE.parent / "app" / "src" / "main" / "res"
SVG = HERE / "app_icon.svg"

DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

# app_icon, the plain drive the About screen shows, is 72dp as the original
# bitmap was; one PNG per density keeps it sharp on every screen.
APP_ICON_DP = 72

# The launcher's adaptive icon foreground is a 108dp square, of which a
# launcher may show as little as the 66dp circle in the middle, so the drive
# is kept small enough for its corners to survive a round mask.
# drawable/ic_launcher_monochrome.xml places its silhouette with the same
# figure: scale 108 * FOREGROUND_ART / 256, the rest split either side.
FOREGROUND_DP = 108
FOREGROUND_ART = 0.54

# Play wants an opaque, full-square 512px icon and rounds the corners itself.
# The launcher background, drawable/ic_launcher_background.xml, uses the same
# gradient.
PLAY_SIZE = 512
PLAY_BACKGROUND = "linear-gradient(#96d6f7, #17548a)"
PLAY_ART = 0.86


def chrome():
    for path in (os.environ.get("CHROME"),
                 r"C:\Program Files\Google\Chrome\Application\chrome.exe",
                 r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
                 "/usr/bin/google-chrome", "/usr/bin/chromium"):
        if path and os.path.exists(path):
            return path
    raise SystemExit("no Chrome found; set CHROME")


def page(size, art, background):
    return f"""<!doctype html>
<html><body style="margin:0;width:{size}px;height:{size}px;overflow:hidden;
  background:{background or 'transparent'};display:flex;align-items:center;
  justify-content:center">
<img src="{SVG.as_uri()}" style="width:{size * art}px;height:{size * art}px">
</body></html>"""


def render(out, size, art=1.0, background=None):
    out.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        html = pathlib.Path(tmp) / "icon.html"
        html.write_text(page(size, art, background), encoding="utf-8")
        shot = pathlib.Path(tmp) / "shot.png"
        subprocess.run([
            chrome(), "--headless=new", "--disable-gpu", "--hide-scrollbars",
            "--force-device-scale-factor=1",
            "--default-background-color=00000000",
            f"--window-size={size},{size}", f"--screenshot={shot}",
            "--allow-file-access-from-files", html.as_uri(),
        ], check=True, capture_output=True)
        out.write_bytes(shot.read_bytes())
    print(out.relative_to(HERE.parent), f"{size}x{size}")
    return out


def crush(paths):
    args = ["-q", "-brute", "-rem", "alla", "-ow"]
    if shutil.which("pngcrush"):
        cmd = ["pngcrush"] + args
        names = [str(p) for p in paths]
    elif os.name == "nt" and shutil.which("wsl"):
        cmd = ["wsl", "-e", "pngcrush"] + args
        # C:\a\b.png -> /mnt/c/a/b.png
        names = ["/mnt/" + p.drive[0].lower() + p.as_posix()[2:] for p in paths]
    else:
        print("pngcrush not found; the PNGs are left uncrushed")
        return
    for name in names:
        subprocess.run(cmd + [name], check=True, capture_output=True)
    print("crushed", len(names), "files")


def main():
    made = []
    for name, scale in DENSITIES.items():
        made.append(render(RES / f"drawable-{name}" / "app_icon.png",
                           round(APP_ICON_DP * scale)))
        made.append(render(RES / f"mipmap-{name}" / "ic_launcher_foreground.png",
                           round(FOREGROUND_DP * scale), FOREGROUND_ART))
    made.append(render(HERE / "play" / "icon-512.png", PLAY_SIZE, PLAY_ART,
                       PLAY_BACKGROUND))
    crush(made)


if __name__ == "__main__":
    sys.exit(main())
