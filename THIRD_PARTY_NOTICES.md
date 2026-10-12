# Third-party notices

obviouslyweather's own code is licensed under GPL-3.0-or-later. The components below keep their own licenses.

| Component | License | Source |
| --- | --- | --- |
| Inter, bundled widget font | SIL Open Font License 1.1 | [Inter](https://github.com/rsms/inter), distributed through [Google Fonts](https://github.com/google/fonts/tree/main/ofl/inter) |
| Google Sans, app font | SIL Open Font License 1.1 | [Google Fonts](https://github.com/google/fonts/tree/main/ofl/googlesans) |
| Meteocons, adapted widget and launcher artwork | MIT | [Bas Milius / Meteocons](https://github.com/basmilius/weather-icons) |
| Phosphor, app icons | MIT | [Phosphor Icons](https://github.com/phosphor-icons/core) |
| AndroidX, Compose, Lifecycle, WorkManager | Apache License 2.0 | [AndroidX](https://android.googlesource.com/platform/frameworks/support/) |
| Kotlin and kotlinx.coroutines | Apache License 2.0 | [Kotlin](https://github.com/JetBrains/kotlin), [coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| OkHttp and Okio | Apache License 2.0 | [OkHttp](https://github.com/square/okhttp), [Okio](https://github.com/square/okio) |
| Gradle wrapper | Apache License 2.0 | [Gradle](https://github.com/gradle/gradle) |

Full copyright notices and license texts are in `app/src/main/assets/licenses/`, including Google's font trademark notice. The fonts are unmodified. Weather artwork has been adapted for the app.

Build tools use Gradle and Android Gradle Plugin (Apache-2.0); asset generation uses Sharp (Apache-2.0). Tests use JUnit (EPL-1.0), MockWebServer (Apache-2.0), Robolectric (MIT), and JSON-java (public domain). These test dependencies aren't bundled in release APKs.

One UI Sans can come from the device or a user import. Samsung font files aren't bundled.

Weather data is provided by [OpenWeather](https://openweathermap.org/). Its terms apply to API access and weather data. This project is not affiliated with OpenWeather, Samsung, or Google.
