# obviouslyweather

An open-source weather app for Android with a black-and-white interface, home-screen widgets and rain alerts.

**[Download the APK](https://github.com/obviouslyvoid/obviouslyweather/releases)**

## Features

- Current conditions, hourly forecasts and a daily outlook.
- Hourly and daily widgets with adjustable backgrounds, text colors and fonts.
- Rain notifications for upcoming rain, estimated ending times and changes to the forecast. Quiet hours are optional.
- Light and dark themes, with an option to follow the device theme.

## Screenshots

| Light theme | Dark theme | Widget settings |
| :---: | :---: | :---: |
| [<img src="docs/screenshots/1screenshot_homescreen_light.jpg" width="160" alt="Home screen in light theme">](docs/screenshots/1screenshot_homescreen_light.jpg) | [<img src="docs/screenshots/2screenshot_homescreen_dark.jpg" width="160" alt="Home screen in dark theme">](docs/screenshots/2screenshot_homescreen_dark.jpg) | [<img src="docs/screenshots/3screenshot_widget_settings.jpg" width="160" alt="Widget settings and preview">](docs/screenshots/3screenshot_widget_settings.jpg) |

| Hourly widget | Daily widget |
| :---: | :---: |
| [<img src="docs/screenshots/4screenshot_widget_hourly_fc.jpg" width="245" alt="Widget showing an hourly forecast">](docs/screenshots/4screenshot_widget_hourly_fc.jpg) | [<img src="docs/screenshots/5screenshot_widget_daily_fc.jpg" width="245" alt="Widget showing a daily forecast">](docs/screenshots/5screenshot_widget_daily_fc.jpg) |

## Setup

Install the APK, enter your [OpenWeather API key](https://home.openweathermap.org/api_keys), and choose a city.

Hourly forecasts need One Call API 4.0 access. Daily forecasts can use One Call 4.0 or 3.0; you choose which one in settings.

The app has no ads or analytics. Weather requests go directly to OpenWeather. See [Privacy](PRIVACY.md) for details.

## Building

Open the project in Android Studio and let Gradle sync. You can build there or use the Gradle wrapper:

```sh
./gradlew :app:assembleDebug
```

On Windows, run `gradlew.bat :app:assembleDebug`. The debug APK is in `app/build/outputs/apk/debug/`.

## Contributing

Use [Issues](https://github.com/obviouslyvoid/obviouslyweather/issues) for bug reports and feature requests. For code changes, see [Contributing](CONTRIBUTING.md). Report vulnerabilities through [Security](SECURITY.md).

## License

[GPL-3.0-or-later](LICENSE). Fonts, icons and dependencies have their own licenses, listed in [Third-party notices](THIRD_PARTY_NOTICES.md).
