# DQMS-Outlet-Display-APK (Native Android TV)

This repository contains the **100% Native Kotlin** Android TV application for the Digital Queue Management Platform (DQMP).

## Features
- **Native Performance:** Built with Jetpack Compose for TV (no slow WebViews).
- **Audio Announcements:** Integrated Text-to-Speech (TTS) for "Customer Proceed" alerts.
- **Premium UI:** Dark-mode optimized dashboard following modern TV design standards.
- **Resilient Polling:** Automatic fallback to HTTP polling if WebSocket connection fails.
- **Admin Setup:** Secret reset handshake (Tap BACK button 5 times) to clear branch settings.

## Tech Stack
- **Kotlin & Jetpack Compose (TV)**
- **Modern Jetpack Libraries:** DataStore, Retrofit, Coroutines, ViewModel.
- **Networking:** OkHttp & WebSocket integration.
- **Audio:** MediaPlayer (Ding) & TTS (Announcements).

## How to Build
1. Open this folder in **Android Studio (Ladybug 2024.2.1 or newer)**.
2. Sync Project with Gradle.
3. Build > Build APK(s) > Build APK.
4. Install `DQMP_Outlet_Display.apk` on your target Android TV box/hardware.

---
