# Making a release

Set `versionName` in `app/build.gradle.kts` and increase `versionCode`.

## Build and sign

On Windows with Android Studio installed:

```powershell
./build.ps1 -Release -Check
```

Or build with Gradle:

```sh
./gradlew :app:assembleRelease :app:testDebugUnitTest :app:lintRelease
```

Sign the APK in Android Studio using **Generate Signed Bundle / APK**. Use the existing release key so installed copies can receive the update. Install the signed APK and check that the app starts, loads weather and displays its widgets.

## Package and publish

Run these from the project root, using the signed APK you plan to upload:

```sh
python release-audit/scan_credentials.py --apk PATH_TO_SIGNED_APK
python release-audit/package_source.py --output dist/obviouslyweather-source.zip
```

Resolve any scanner findings. The [release tools](../release-audit/README.md) also include a startup check.

Create `SHA256SUMS.txt` with one line per asset: its SHA-256 hash, two spaces, then its filename. Include the signed APK and source ZIP.

Create a `v<version>` tag for the final source commit and a GitHub release from that tag. Attach the APK, matching source ZIP and `SHA256SUMS.txt`. Describe the changes and list the APK's Android and device requirements.

Download the uploaded files and compare their SHA-256 hashes with `SHA256SUMS.txt`.
