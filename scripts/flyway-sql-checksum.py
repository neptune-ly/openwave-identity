#!/usr/bin/env python3
"""Calculate Flyway 9.x's signed CRC-32 for one UTF-8 SQL migration."""

from __future__ import annotations

import pathlib
import sys
import zlib


def flyway_checksum(path: pathlib.Path) -> int:
    if not path.is_file() or path.is_symlink():
        raise SystemExit("migration source must be a regular non-symlink file")

    # Flyway's ChecksumCalculator reads UTF-8 through BufferedReader, strips a
    # possible BOM from the first line, and feeds each line without its line
    # terminator into one cumulative CRC32. Keep this line-ending independent.
    text = path.read_text(encoding="utf-8-sig")
    checksum = 0
    for line in text.splitlines():
        checksum = zlib.crc32(line.encode("utf-8"), checksum)

    return checksum if checksum < 2**31 else checksum - 2**32


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: flyway-sql-checksum.py MIGRATION.sql")
    print(flyway_checksum(pathlib.Path(sys.argv[1])))
