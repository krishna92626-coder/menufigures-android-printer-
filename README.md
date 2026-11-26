# MenuFigures Android Printer Companion

Android WebView companion for MenuFigures with a secure Bluetooth Classic (SPP) ESC/POS bridge and native Firebase Cloud Messaging support.

## Download the latest installable APK

- **Direct APK:** [Download the latest signed MenuFigures APK](https://github.com/krishna92626-coder/menufigures-android-printer-/releases/latest/download/app-release.apk)
- **Release details:** [GitHub Releases](https://github.com/krishna92626-coder/menufigures-android-printer-/releases)
- **Build artifacts:** [GitHub Actions](https://github.com/krishna92626-coder/menufigures-android-printer-/actions) → latest successful **Build Android APK** run → `menufigures-release-apk`.

The APK is built as a signed release (`app-release.apk`), not as `app-debug.apk`. Install updates over an existing installation only when they are signed with the same private release keystore.

## Included

- Full-screen MenuFigures WebView.
- Secure origin-restricted JavaScript message bridge.
- Paired Bluetooth Classic printer discovery and RFCOMM/SPP connection.
- ESC/POS receipt printing with bitmap rendering for Hindi-capable printer output.
- Printer paper width, copies and receipt configuration through MenuFigures Settings.
- Firebase Cloud Messaging device token and per-store topic subscription support.
- Android notification channel, runtime notification permission and tap-to-open internal MenuFigures routes.

## Build requirements

GitHub Actions expects these repository secrets for a signed release:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The Firebase configuration for project `menufigures-955d5` and package `com.menufigures.printer` is included in the Android app source branch. Keep the private signing keystore and its passwords backed up securely; never commit them to this repository.

## Printer compatibility

The prototype assumes Bluetooth Classic SPP and ESC/POS. EZO is a brand, not a guaranteed protocol; some models may use BLE or proprietary protocols. Pair the printer in Android Settings first. Physical printing must be verified with each printer model. Hindi output depends on the printer's bitmap/raster support and paper configuration.
