# 🪃 OpenRang

An open-source, device-agnostic Android Camera App for creating custom, speed-controlled video loops ("Boomerangs"). 

Unlike proprietary apps with rigid speed configurations, **OpenRang** puts control back in the user's hands with real-time, customizable playback speed adjustments.

## Core Features (Concept Spike)
- **Standard Video Burst:** Quick 1.5-second captures using CameraX.
- **The "Loop" (Forward & Reversed):** Seamless, back-to-back concatenation of forward and backward clips using **Jetpack Media3**.
- **Dynamic Speed Slider:** Real-time speed adjustments (from 0.5x to 3.0x) on the preview loop before sharing.
- **Privacy & Speed:** 100% on-device processing with zero latency or external network requirements.

## Architecture & Technology Stack
- **Language:** Kotlin
- **UI:** Jetpack Compose (Modern, responsive declarative layouts)
- **Camera API:** AndroidX CameraX (Device-agnostic compatibility for the Google Play Store)
- **Media Engine:** AndroidX Media3 (ExoPlayer for playback, Transformer for reversing/exporting)

## Setup & Compilation
1. Clone this repository:
   ```bash
   git clone https://github.com/stozo04/OpenRang.git
   ```
2. Open the project in **Android Studio**.
3. Sync Gradle and run the `:app` module on a connected device or emulator (Android 8.0 / API 26+).

## License
OpenRang is available under the **Apache License 2.0**.
