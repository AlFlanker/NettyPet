#!/usr/bin/env python3
"""Build or check the Servlet + Jetty HttpClient guide."""

from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "docs"))
from pdf_guide import main


if __name__ == "__main__":
    raise SystemExit(main(
        docs_dir=Path(__file__).resolve().parent,
        title="Servlet + Jetty HttpClient — руководство",
        header_label="jetty-client-example",
        expected_pages=15,
        pdf_filename="jetty-client-guide.pdf",
    ))
