# llMenu Android App — inbuilt Bluetooth printer bridge

This branch packages the existing llMenu Light Pulse web app inside one Android APK and embeds the Bluetooth Classic SPP / ESC/POS bridge directly in that APK. The user does not need to install a separate MenuFigures Printer companion app.

## App identity

- Android application ID: `com.llmenu.app`
- App label: `llMenu`
- Hosted app: `https://llmenu-light-pulse.apper.so`
- Minimum Android: API 23
- Target Android: API 35
- Native bridge: `window.LlMenuPrinter`

## Included native capabilities

- Lists already-paired Bluetooth devices.
- Connects to Bluetooth Classic RFCOMM/SPP printers.
- Sends ESC/POS data over the connected socket.
- Uses Android 12+ Nearby devices permission.
- Restricts the injected JavaScript bridge to the exact llMenu HTTPS origin.
- Rejects WebView navigation outside the trusted llMenu host.
- Closes the Bluetooth socket when the app is destroyed.

## Web app integration

The Apper project contains the matching `src/lib/printerBridge.js` client. It sends bridge requests for:

- `getPairedPrinters`
- `connect`
- `printEscPos`
- `disconnect`

Receipts and kitchen tickets are rasterised in the WebView and sent as ESC/POS raster data. This allows Android's available fonts to render Devanagari text as an image instead of relying on the printer's text code page.

## Build and download

GitHub Actions builds a signed release APK on pushes to `llmenu-inbuilt-printing` and `main`.

- Latest llMenu APK: open the [llMenu releases](https://github.com/krishna92626-coder/menufigures-android-printer-/releases) page and download `app-release.apk` from the latest `llmenu-release-*` release.
- Build workflow: [GitHub Actions](https://github.com/krishna92626-coder/menufigures-android-printer-/actions).

The release workflow verifies the APK signature before publishing it.

## Physical printer validation still required

The APK build and signature are verified by CI, but a successful build does not prove that a particular thermal printer works. Pair the printer in Android Settings, connect it from llMenu → Settings → Printer, and use Test print. Validate actual paper output, alignment, paper width, Devanagari rendering, and reconnect behavior on the target printer model before production use.

Some printer models use BLE or proprietary protocols instead of Bluetooth Classic SPP. This bridge currently targets Bluetooth Classic SPP with ESC/POS.
