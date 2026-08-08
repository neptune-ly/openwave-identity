#!/usr/bin/env python3
from __future__ import annotations

from html.parser import HTMLParser
import pathlib
import re
import sys


class ReleaseAssets(HTMLParser):
    def __init__(self):
        super().__init__()
        self.paths: set[str] = set()

    def handle_starttag(self, _tag: str, attrs: list[tuple[str, str | None]]) -> None:
        for key, value in attrs:
            if key not in {"href", "src"} or not value or not value.startswith("/_app/"):
                continue
            path = pathlib.PurePosixPath(value)
            if (
                not re.fullmatch(r"/_app/[A-Za-z0-9._/-]+", value)
                or value != str(path)
                or any(part in {".", ".."} for part in path.parts)
            ):
                raise SystemExit("unsafe release asset path")
            self.paths.add(value)


if len(sys.argv) != 2:
    raise SystemExit("usage: list-static-entry-assets.py INDEX_HTML")

parser = ReleaseAssets()
parser.feed(pathlib.Path(sys.argv[1]).read_text())
for asset_path in sorted(parser.paths):
    print(asset_path)
