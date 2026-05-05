"""PyInstaller entry point.

PyInstaller runs the bundled script as `__main__`, which breaks the relative
imports inside src/. This wrapper imports `src.main` as a proper package member
so all the `from .secret import ...` lines resolve correctly.

Don't merge this file into src/main.py — running `python -m src.main` from the
project root (the dev workflow) still uses the package-style entry there.
"""
import sys
from src.main import main


if __name__ == "__main__":
    sys.exit(main())
