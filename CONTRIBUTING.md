# Contributing

Bug reports and feature requests go in [Issues](https://github.com/obviouslyvoid/obviouslyweather/issues). For a larger change, open an issue to discuss it before starting work. Security issues should be [reported privately](SECURITY.md).

## Pull requests

Keep each pull request focused on one change. Describe the problem it solves and how you checked the result. Add screenshots for changes to the interface.

The [README](README.md#building) covers building the app. Run the tests relevant to your change; the unit tests are available through `./gradlew :app:testDebugUnitTest`. Widget layout changes also need a check in a launcher and in the settings preview.

Don't include API keys, passwords or signing files in reports or commits. If you add a font, icon or other third-party asset, include its source and license.
