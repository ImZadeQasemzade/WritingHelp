"""Builds WritingHelp.exe with PyInstaller and copies it to ../exe/.

    python -m venv .venv
    .venv\\Scripts\\pip install -r requirements.txt
    .venv\\Scripts\\python build.py
"""

import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(HERE, "build")
ICON = os.path.join(BUILD, "WritingHelp.ico")
OUT_DIR = os.path.join(HERE, "..", "exe")


def make_icon():
    from PySide6.QtWidgets import QApplication

    sys.path.insert(0, HERE)
    from writinghelp.ui import app_icon

    app = QApplication.instance() or QApplication([])  # noqa: F841 - QPixmap needs an app
    os.makedirs(BUILD, exist_ok=True)
    if not app_icon(256).pixmap(256, 256).save(ICON, "ICO"):
        raise SystemExit("Couldn't write the .ico (Qt's ico image plugin is missing)")


def main():
    make_icon()
    subprocess.run(
        [
            sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean", "--onefile", "--windowed",
            "--name", "WritingHelp", "--icon", ICON,
            "--workpath", os.path.join(BUILD, "pyinstaller"), "--specpath", BUILD,
            "--distpath", os.path.join(HERE, "dist"),
            os.path.join(HERE, "run.py"),
        ],
        check=True,
        cwd=HERE,
    )
    os.makedirs(OUT_DIR, exist_ok=True)
    target = os.path.join(OUT_DIR, "WritingHelp.exe")
    shutil.copy2(os.path.join(HERE, "dist", "WritingHelp.exe"), target)
    print(f"Built {os.path.abspath(target)}")


if __name__ == "__main__":
    main()
