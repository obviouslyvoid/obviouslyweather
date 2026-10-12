# SPDX-License-Identifier: GPL-3.0-or-later
import os
import unittest
from pathlib import Path
from unittest.mock import patch

from check_apk_startup import resolve_adb


class AdbLookupTest(unittest.TestCase):
    def test_explicit_binary_takes_priority(self):
        with patch("check_apk_startup.shutil.which", return_value="path-adb"):
            self.assertEqual("chosen-adb", resolve_adb("chosen-adb"))

    def test_path_takes_priority_over_sdk(self):
        with patch.dict(os.environ, {"ANDROID_HOME": "/sdk"}, clear=True), \
                patch("check_apk_startup.shutil.which", return_value="path-adb"):
            self.assertEqual("path-adb", resolve_adb())

    def test_sdk_fallback_uses_the_host_executable_name(self):
        for platform, executable in [("linux", "adb"), ("darwin", "adb"), ("win32", "adb.exe")]:
            with self.subTest(platform=platform), \
                    patch.dict(os.environ, {"ANDROID_HOME": "/sdk"}, clear=True), \
                    patch("check_apk_startup.shutil.which", return_value=None), \
                    patch("check_apk_startup.sys.platform", platform):
                self.assertEqual(str(Path("/sdk") / "platform-tools" / executable), resolve_adb())


if __name__ == "__main__":
    unittest.main()
