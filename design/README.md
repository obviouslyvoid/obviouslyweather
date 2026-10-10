# Artwork and widget checks

Generated icons are checked in, so building the app doesn't require Node.js. After editing the SVG sources, regenerate the launcher and widget artwork from the project root:

```sh
npm install --prefix design/meteocons
node design/meteocons/build-launcher.cjs
node design/meteocons/build-widget.cjs
```

Font source information is in `design/fonts/`. Asset license texts are in `app/src/main/assets/licenses/`.

## Widget rendering

Build the debug app, then run `design/widget-validation/check.ps1`. It checks alignment, contrast and bitmap size with Android Studio's renderer. Use `-Font PATH` to check a local font file.

For Android resource drawables, run the instrumentation check on a development emulator:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w com.weather.obviouslyweather.test/com.weather.obviouslyweather.widget.WidgetRenderingChecks
```

Replace `SERIAL` with the emulator's serial. This check overwrites its app cache with fixture weather and saves sample PNGs in the app's private files. A successful run reports `PASS`.

Check the widget in a launcher as well as the settings preview before submitting a layout change.
