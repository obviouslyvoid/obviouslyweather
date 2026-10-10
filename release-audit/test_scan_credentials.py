# SPDX-License-Identifier: GPL-3.0-or-later
import unittest
import hashlib
import struct
import tempfile
from pathlib import Path
from unittest.mock import patch

from scan_credentials import scan_bytes, source_files


class CredentialScanTest(unittest.TestCase):
    def test_generated_release_output_is_excluded_but_variant_source_is_kept(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('app/release/mapping.txt', 'app/release/output-metadata.json',
                         'app/src/release/Variant.kt', 'docs/release/notes.md', 'download.apk.idsig'):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('fixture', encoding='utf-8')
            included = {path.relative_to(root).as_posix() for path in source_files(root)}
            self.assertEqual(included, {'app/src/release/Variant.kt', 'docs/release/notes.md'})

    def test_finds_secret_without_including_value(self):
        key = b"0123456789abcdef" * 2
        findings = scan_bytes(b"configuration\nappid=" + key, "config.txt", {key})
        self.assertEqual({item["rule"] for item in findings}, {"known-secret", "possible-openweather-key"})
        self.assertTrue(all(item["line"] == 2 for item in findings))
        self.assertNotIn(key.decode(), str(findings))

    def test_reviewed_download_id_does_not_hide_known_secrets(self):
        key = b"0123456789abcdef" * 2
        line = b"toolchainUrl.LINUX.X86_64=https\\://api.foojay.io/disco/v3.0/ids/" + key + b"/redirect"
        reviewed = []
        self.assertEqual(scan_bytes(line, "gradle/gradle-daemon-jvm.properties", set(), reviewed), [])
        self.assertEqual(len(reviewed), 1)
        findings = scan_bytes(line, "gradle/gradle-daemon-jvm.properties", {key})
        self.assertEqual(findings[0]["rule"], "known-secret")
        self.assertTrue(scan_bytes(line, "other.properties", set()))

    def test_other_credential_types_and_safe_fixtures(self):
        for token in (b"ghp_" + b"a" * 36, b"AKIA" + b"A" * 16, b"-----BEGIN " + b"PRIVATE KEY-----"):
            self.assertTrue(scan_bytes(token, "file.txt", set()))
        self.assertFalse(scan_bytes(b'apiKey = "LOCAL_TEST_FIXTURE_KEY"', "test.kt", set()))
        assignment = b'apiKey = "' + b"unexpected_token_value" + b'"'
        self.assertTrue(scan_bytes(assignment, "test.kt", set()))

    def test_schema_review_is_scoped_to_dex_and_known_keys_still_fail(self):
        key = b"0123456789abcdef" * 2
        with patch("scan_credentials.WORK_DATABASE_HASHES", {hashlib.sha256(key).hexdigest()}):
            self.assertFalse(scan_bytes(key, "app.apk!classes2.dex", set()))
            self.assertTrue(scan_bytes(key, "config.txt", set()))
            self.assertEqual(scan_bytes(key, "app.apk!classes.dex", {key})[0]["rule"], "known-secret")

    def test_annotation_review_requires_the_reviewed_fingerprint_and_metadata_section(self):
        key = b"0123456789abcdef" * 2
        dex = bytearray(177)
        dex[:8] = b"dex\n035\0"
        struct.pack_into("<I", dex, 52, 112)
        struct.pack_into("<I", dex, 112, 2)
        struct.pack_into("<HHII", dex, 116, 0x2004, 0, 1, 144)
        struct.pack_into("<HHII", dex, 128, 0x2005, 0, 1, 176)
        dex[144:176] = key
        data = bytes(dex)
        self.assertTrue(scan_bytes(data, "app.apk!classes.dex", set()))
        with patch("scan_credentials.DEX_ANNOTATION_HASHES", {hashlib.sha256(key).hexdigest()}):
            reviewed = []
            self.assertFalse(scan_bytes(data, "app.apk!classes.dex", set(), reviewed))
            self.assertEqual(len(reviewed), 1)
            self.assertTrue(scan_bytes(data, "source.dat", set()))
            self.assertTrue(scan_bytes(key, "app.apk!classes.dex", set()))
            self.assertEqual(scan_bytes(data, "app.apk!classes.dex", {key})[0]["rule"], "known-secret")
            struct.pack_into("<H", dex, 116, 0x2002)
            self.assertTrue(scan_bytes(bytes(dex), "app.apk!classes.dex", set()))


if __name__ == "__main__":
    unittest.main()
