# Privacy

obviouslyweather has no ads, analytics or automatic crash reporting. It doesn't access your phone's location; you choose a city.

City searches and weather requests go directly to OpenWeather over HTTPS. These requests include your API key, the search text or selected city's coordinates, and the weather units where applicable. OpenWeather receives your IP address and the app version too. Its [privacy policy](https://openweathermap.org/privacy-policy) applies to those requests.

The app saves your city, preferences, cached forecasts and rain-alert state on your device. The API key is encrypted using Android Keystore. Imported widget fonts are copied into the app's storage and aren't uploaded.

Android backups and device transfers are disabled for app data. Uninstalling the app or clearing its storage removes the saved data and imported font copies. It doesn't delete the original font files.
