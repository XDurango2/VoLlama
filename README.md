# VoLlama

Android app for peer-to-peer voice calls over local networks — no internet required. Uses Google's [Nearby Connections API](https://developers.google.com/nearby/connections/overview) to connect devices via Wi-Fi and Bluetooth.

## Features

- **Call mode** — Full-duplex voice call between two devices
- **Walkie-Talkie mode** — Push-to-talk (in progress)
- **No server required** — Direct device-to-device connection
- Mute and speakerphone controls during calls
- Incoming call dialog with auto-reject countdown

## Requirements

- Android 8.0+ (API 25)
- Microphone
- Wi-Fi and/or Bluetooth

## How it works

1. Tap **+** on the main screen and grant permissions
2. Choose a mode:
   - **"Quiero conectarme"** — search for nearby devices
   - **"Espero una conexión"** — wait for someone to connect to you
3. Once connected, tap **Llamar** to start a voice call

Both devices must have the app open and be within Nearby Connections range (~100m Wi-Fi, ~30m Bluetooth).

## Build

Este repo usa un submódulo git ([KoOpus](https://github.com/Steegler/KoOpus), pinneado a `v0.1.2`) para el codec Opus — hay que inicializarlo antes de compilar:

```bash
git submodule update --init --recursive
./gradlew assembleDebug
./gradlew installDebug
```

Requiere NDK `27.0.11902837` (Android Studio lo descarga solo si falta).

## Tech Stack

- Kotlin + Jetpack Compose + Material 3
- Hilt (dependency injection)
- Google Nearby Connections API
- Codec **Opus** ([KoOpus](https://github.com/Steegler/KoOpus), JNI sobre libopus 1.5.2) — captura a 16 kHz mono, frames de 20ms fusionados en paquetes de 40ms
- `AudioRecord` / `AudioTrack` (PCM 16-bit, 16 kHz, mono) como entrada/salida del codec