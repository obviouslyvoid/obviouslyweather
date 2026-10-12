# SPDX-License-Identifier: GPL-3.0-or-later
"""Install and cold-launch an APK through the launcher and widget entry points."""

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path


PACKAGE = "com.weather.obviouslyweather"


def resolve_adb(explicit=None):
    if explicit:
        return explicit
    on_path = shutil.which("adb")
    if on_path:
        return on_path
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk and os.environ.get("LOCALAPPDATA"):
        sdk = str(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk")
    executable = "adb.exe" if sys.platform == "win32" else "adb"
    return str(Path(sdk) / "platform-tools" / executable) if sdk else executable


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--serial", required=True, help="Explicit adb device or emulator serial")
    parser.add_argument("--adb")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    adb = resolve_adb(args.adb)

    def run(*arguments):
        result = subprocess.run([adb, "-s", args.serial, *arguments],
                                capture_output=True, text=True, timeout=60)
        if result.returncode:
            if arguments == ("shell", "pidof", PACKAGE):
                return ""
            raise RuntimeError((result.stderr or result.stdout).strip() or
                               "adb command failed: " + " ".join(arguments))
        return result.stdout.strip()

    try:
        digest = hashlib.sha256(args.apk.read_bytes()).hexdigest()
        installed = run("install", "-r", str(args.apk.resolve()))
        if "Success" not in installed:
            raise RuntimeError("APK installation did not succeed: " + installed)
        apk_path = run("shell", "pm", "path", PACKAGE).splitlines()[0].removeprefix("package:")
        installed_digest = run("shell", "sha256sum", apk_path).split()[0]
        if installed_digest != digest:
            raise RuntimeError("The installed APK does not match the selected file.")

        results = []
        for entry, action in [("launcher", None), ("widget", PACKAGE + ".OPEN_WEATHER")]:
            run("shell", "am", "force-stop", PACKAGE)
            command = ["shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity"]
            if action:
                command += ["-a", action]
            launch = run(*command)
            if "Error:" in launch:
                raise RuntimeError(launch)
            time.sleep(3)
            pid = run("shell", "pidof", PACKAGE)
            activity = run("shell", "dumpsys", "activity", "activities")
            resumed = re.search(r"(?:mResumedActivity|topResumedActivity|ResumedActivity)[=:].*" + re.escape(PACKAGE) +
                                r"/(?:\.MainActivity|" + re.escape(PACKAGE) + r"\.MainActivity)", activity)
            if not pid or not resumed:
                raise RuntimeError(entry + " launch did not keep MainActivity open. Check the device's crash log.")
            results.append({"entry": entry, "passed": True})
            print(entry + " cold launch passed")

        report = {"apk_sha256": digest, "installed_apk_sha256": installed_digest,
                  "android_api": run("shell", "getprop", "ro.build.version.sdk"), "checks": results}
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print("Installed APK SHA-256: " + digest)
        return 0
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        print("Startup check failed: " + str(error))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
