# SPDX-License-Identifier: GPL-3.0-or-later
"""Create a source archive using the credential audit's publication scope."""

import argparse
import zipfile
from pathlib import Path

from scan_credentials import RULES, scan_bytes, source_files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--known-secret-file", type=Path, action="append", default=[])
    args = parser.parse_args()
    root = args.root.resolve()
    files = list(source_files(root))
    known = set()
    for reference in args.known_secret_file:
        known.update(match.group() for match in RULES["possible-openweather-key"].finditer(reference.read_bytes()))
    findings = []
    for path in files:
        findings.extend(scan_bytes(path.read_bytes(), path.relative_to(root).as_posix(), known))
    if findings:
        print(f"Source archive withheld: {len(findings)} credential candidates need review.")
        return 1
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            if path.resolve() != args.output.resolve():
                archive.write(path, "obviouslyweather/" + path.relative_to(root).as_posix())
    print(f"Source archive: {args.output.name} ({len(files)} files)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
