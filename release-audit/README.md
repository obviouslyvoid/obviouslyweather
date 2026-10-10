# Release tools

Run these commands from the project root.

## Credential scan

```sh
python release-audit/scan_credentials.py --apk PATH_TO_APK
```

Scans the source and APK for possible credentials. The report identifies matches without showing their values. Use `--known-secret-file PATH` to compare against credentials kept in a private file.

## Source archive

```sh
python release-audit/package_source.py --output dist/obviouslyweather-source.zip
```

Packages the files intended for publication, leaving out build output, local configuration, signing files and private backups. It refuses to create the archive if the source scan has unreviewed matches.

## Startup check

```sh
python release-audit/check_apk_startup.py --apk PATH_TO_APK --serial SERIAL
```

Installs the APK on a test device or emulator, checks the installed checksum, and opens the app normally and through the widget. It installs over an existing copy when the signing key matches.

## Scanner tests

```sh
python -m unittest discover -s release-audit -p 'test_*.py'
```
