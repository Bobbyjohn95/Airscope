"""Entry point for the packaged build (PyInstaller) and for `python run.py`."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from airscope_desktop.__main__ import main  # noqa: E402

if __name__ == "__main__":
    sys.exit(main())
