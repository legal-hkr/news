# News

A private, full-text feed reader for Android (RSS and Atom) that keeps articles on the phone for reading offline.

**Status**: in development, nothing released yet.

Source code: [git.hnet.sh/hkr/news](https://git.hnet.sh/hkr/news)

Bug reports and feature requests: [github.com/legal-hkr/news](https://github.com/legal-hkr/news/issues)

## Requirements

- Android 14 (API level 34) or higher

## Building

```bash
git clone https://git.hnet.sh/hkr/news.git
cd news
./gradlew assembleDebug
```

Set the Android SDK path in `local.properties` (`sdk.dir=…`) first. Unit tests run with `./gradlew test`.

## Contributing

Development happens on [git.hnet.sh](https://git.hnet.sh/hkr/news). The [GitHub repository](https://github.com/legal-hkr/news) is a read-only mirror, updated on every push, and the place to report problems:

- **Issues**: Open them on [GitHub](https://github.com/legal-hkr/news/issues), with the app version and your Android version
- **Pull requests**: Welcome on GitHub as well, but they can’t be merged there – accepted changes are applied on git.hnet.sh and reach the mirror from there, so the pull request itself ends up closed rather than merged

## Tech stack

- **Language**: Kotlin
- **Min SDK**: Android 14 (API 34), **target SDK**: API 37
- **UI**: Jetpack Compose with Material 3 Expressive

## License

[GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.en.html)

## Acknowledgments

- [Claude](https://claude.ai) by Anthropic – AI assistant that provided valuable support during the development process, helping with code implementation, architecture decisions, and documentation
