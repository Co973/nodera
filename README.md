# Nodera

Nodera is an Android-first experimental local messaging app. It is built around direct local connections and keeps identity and encrypted storage on the device.

## Android preview

The signed preview APK is available from the project website. It supports Android 12 and newer. The installed app may still appear as **Mesh** while the app name catches up with Nodera.

## Build from source

Open `android/` in Android Studio, install SDK Platform 35 and Build Tools 35.0.0, then use JDK 17 and Gradle 8.11.1. With Gradle installed, run:

```powershell
gradle :app:assembleDebug
```

For the portable signed preview build used by the website:

```powershell
python scripts/bootstrap-build-tools.py
python scripts/build-android.py
```

## Project status

Nodera is early preview software. The current implementation uses experimental encryption that has not been audited and does not provide forward secrecy. Bluetooth behavior still needs broader device validation. Do not use it for sensitive or safety-critical conversations.

Architecture references: [Noise specification](https://noiseprotocol.org/noise.html), [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions), [AGP 8.9 compatibility](https://developer.android.com/build/releases/agp-8-9-0-release-notes).
