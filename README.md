# MenuFigures Android Printer Companion — starter source

Android Studio project for an initial Bluetooth Classic (SPP) + ESC/POS prototype.

## Included
- Android app shell
- Lists already-paired Bluetooth devices
- Attempts Bluetooth Classic RFCOMM/SPP connection
- ESC/POS text receipt + test receipt
- 58mm / 80mm width selection
- 1–3 copies
- Receives MenuFigures print intents using `menufigures://print?paper=58&copies=1&text=URL_ENCODED_TEXT`

## Build APK
GitHub Actions builds a debug APK on every push and when manually started. Open the **Actions** tab, choose **Build Android APK**, and download the `menufigures-debug-apk` artifact after the workflow succeeds. You can also open this project in Android Studio and choose **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

## Important limits — not production-certified
- EZO is a brand, not a guaranteed protocol. This prototype assumes Bluetooth Classic SPP and ESC/POS. Some EZO models may use BLE or proprietary protocols.
- Pair the printer in Android Settings first; the app only lists bonded devices.
- Android 12+ requires Nearby devices permission.
- This source has not yet been compiled or tested on a physical EZO printer. A successful socket write does not prove that paper actually printed.
- UTF-8 text support varies by printer firmware/code page. Hindi and other non-Latin text may need raster/bitmap rendering.
- Production work still required: reliable persisted printer choice, retry/reconnect, permission UX, bitmap receipt rendering, status/error handling, security validation of inbound print intents, and physical tests across printer models.
