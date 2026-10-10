# SPDX-License-Identifier: GPL-3.0-or-later
"""Scan publishable files and APK contents without recording secret values."""

import argparse
import hashlib
import json
import os
import re
import struct
import zipfile
from pathlib import Path

SKIP_DIRS = {".git", ".gradle", ".kotlin", ".idea", "build", "dist", "Backups", ".codex-remote-attachments", "__pycache__", "node_modules"}
GENERATED_DIRS = {"app/release"}
PRIVATE_FILES = {"local.properties", "signing.properties", "keystore.properties"}
PRIVATE_SUFFIXES = {".jks", ".keystore", ".p12", ".pfx", ".key", ".pem"}
RULES = {
    "possible-openweather-key": re.compile(rb"(?<![a-zA-Z0-9])[a-fA-F0-9]{32}(?![a-zA-Z0-9])"),
    "github-token": re.compile(rb"(?:gh[pousr]_[A-Za-z0-9]{30,255}|github_pat_[A-Za-z0-9_]{30,255})"),
    "aws-access-key": re.compile(rb"(?:AKIA|ASIA)[A-Z0-9]{16}"),
    "private-key": re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
    "credential-assignment": re.compile(rb"(?i)(?:api[_ -]?key|appid|access[_ -]?token|password|secret)\s*[:=]\s*[\"']([A-Za-z0-9_+/=-]{16,})[\"']"),
}
TEST_VALUE = re.compile(rb"^(?:LOCAL_|OTHER_LOCAL_|TEST_|test[-_]|example[-_]|placeholder)", re.I)
FOOJAY_DOWNLOAD = re.compile(rb"^toolchainUrl\.[A-Z_]+\.[A-Z0-9_]+=https\\://api\.foojay\.io/disco/v3\.0/ids/[a-f0-9]{32}/redirect\r?$")
WORK_DATABASE_HASHES = {
    "f63a8b4be82f6a8084c2a3e31b65bc8b11281df55516a1913f218f0d2aa53cfe",
    "7987482088ea2bdf1361cccd86df7c4b16024ebb6a85ba1cc624fc02e84973b1",
}
DEX_ANNOTATION_HASHES = {
    "ca27ec85c490fdae500ba7197876cc0de7ec7f7e44caceea456ac26b2ed8e0eb",
    # Kotlin Metadata d2 array: encoded string indices, not literal string data.
    "f1fa6caa3d50a43cbcf4e42126700b2119b6fbd021e3f36c118cd433bd7a7530",
}


def in_dex_annotations(data, start, end):
    if not data.startswith(b"dex\n") or len(data) < 112:
        return False
    try:
        map_offset = struct.unpack_from("<I", data, 52)[0]
        count = struct.unpack_from("<I", data, map_offset)[0]
        if count > 64:
            return False
        entries = [struct.unpack_from("<HHII", data, map_offset + 4 + index * 12) for index in range(count)]
        for index, entry in enumerate(entries):
            limit = entries[index + 1][3] if index + 1 < count else len(data)
            if entry[0] == 0x2004 and entry[3] <= start < end <= limit:
                return True
    except struct.error:
        pass
    return False


def scan_bytes(data, label, known, reviewed=None):
    findings = []
    for secret in known:
        start = 0
        while (offset := data.find(secret, start)) >= 0:
            findings.append({"file": label, "line": data[:offset].count(b"\n") + 1, "rule": "known-secret"})
            start = offset + len(secret)
    for rule, pattern in RULES.items():
        for match in pattern.finditer(data):
            value = match.group(1) if match.lastindex else match.group()
            line = data[:match.start()].count(b"\n") + 1
            if rule == "possible-openweather-key" and label == "gradle/gradle-daemon-jvm.properties":
                if FOOJAY_DOWNLOAD.fullmatch(data.splitlines()[line - 1]):
                    if reviewed is not None:
                        reviewed.append({"file": label, "line": line, "reason": "Public Foojay JDK download identifier"})
                    continue
            if rule == "possible-openweather-key" and re.search(r"\.apk!classes(?:\d+)?\.dex$", label):
                fingerprint = hashlib.sha256(value).hexdigest()
                if fingerprint in WORK_DATABASE_HASHES:
                    if reviewed is not None:
                        reviewed.append({"file": label, "line": line, "reason": "WorkManager 2.12.0 Room schema identity constant"})
                    continue
                if fingerprint in DEX_ANNOTATION_HASHES and in_dex_annotations(data, match.start(), match.end()):
                    if reviewed is not None:
                        reviewed.append({"file": label, "line": line, "reason": "Reviewed DEX annotation index sequence, outside string data"})
                    continue
            if rule == "credential-assignment" and TEST_VALUE.match(value):
                continue
            findings.append({"file": label, "line": line, "rule": rule})
    return findings


def source_files(root):
    for folder, dirs, files in os.walk(root):
        relative_folder = Path(folder).relative_to(root)
        dirs[:] = sorted(name for name in dirs if name not in SKIP_DIRS
                         and (relative_folder / name).as_posix() not in GENERATED_DIRS)
        for name in sorted(files):
            path = Path(folder, name)
            if name in PRIVATE_FILES or name.startswith(".env") or path.suffix in PRIVATE_SUFFIXES:
                continue
            if path.suffix in {".class", ".apk", ".aab", ".idsig", ".tgz"}:
                continue
            if path.name == "credentials-result.json":
                continue
            yield path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--known-secret-file", type=Path, action="append", default=[])
    parser.add_argument("--apk", type=Path, action="append", default=[])
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    known = set()
    for path in args.known_secret_file:
        data = path.read_bytes()
        known.update(match.group() for match in RULES["possible-openweather-key"].finditer(data))
    findings = []
    reviewed = []
    count = 0
    for path in source_files(root):
        label = path.relative_to(root).as_posix()
        findings.extend(scan_bytes(path.read_bytes(), label, known, reviewed))
        count += 1
    artifacts = []
    for apk in args.apk:
        entries = 0
        with zipfile.ZipFile(apk) as archive:
            for entry in archive.infolist():
                if not entry.is_dir():
                    findings.extend(scan_bytes(archive.read(entry), apk.name + "!" + entry.filename, known, reviewed))
                    entries += 1
        artifacts.append({"file": apk.name, "entries": entries})
    unique = sorted({(item["file"], item["line"], item["rule"]) for item in findings})
    result = {
        "source_files_scanned": count,
        "known_secrets_compared": len(known),
        "artifacts_scanned": artifacts,
        "findings": [{"file": file, "line": line, "rule": rule} for file, line, rule in unique],
        "reviewed_non_credentials": reviewed,
        "contains_secret_values": False,
    }
    text = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.write_text(text, encoding="utf-8")
    print(text, end="")
    return bool(unique)


if __name__ == "__main__":
    raise SystemExit(main())
