#!/usr/bin/env python3
"""Build or check the Jetty + Netty guide."""

from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from pdf_guide import main


if __name__ == "__main__":
    raise SystemExit(main(
        docs_dir=Path(__file__).resolve().parent,
        title="Jetty + Netty — руководство",
        header_label="app + proxy + command-support",
        expected_pages=17,
        pdf_filename="jetty-netty-guide.pdf",
    ))
