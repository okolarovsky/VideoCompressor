# VideoCompressor (Android)

A minimalist, offline-only Android application **(with the entire user interface in Czech)** for compressing videos directly on a mobile device to strict target file sizes without losing essential quality.

## About the Project
This project was built as a personal side project using **AI-assisted development** (Claude, Cline, Gemini / Google AI Studio) and Android Studio. It focuses on clean Material 3 UI, reliable hardware-accelerated video processing, and real-device testing and debugging via **ADB** (tested on Samsung Galaxy S24).

## Key Features
- **Strict Target File Size Guarantee:** Two-pass adaptive compression logic targeting **10 MB, 25 MB, or 50 MB** limits with automatic bitrate calculation and resolution downscaling.
- **100% Offline & Hardware-Accelerated:** Local video transcoding powered by **AndroidX Media3 Transformer** (`MediaCodec`).
- **Audio & Storage Control:** Option to keep or strip audio tracks, save directly to `DCIM/VideoCompressor`, or export to custom folders via Storage Access Framework (SAF).
- **Modern Android Permissions:** Full support for Android 14+ selective media access (*Allow limited access*) and original filename resolution via `MediaStore`.
- **Custom Material 3 UI:** Warm beige theme built entirely in **Jetpack Compose** with a step-by-step workflow localized in **Czech**.

## Tech Stack & Tools
- **Language & UI:** Kotlin, Jetpack Compose, Material 3
- **Architecture & Async:** MVVM (`ViewModel`, `StateFlow`), Kotlin Coroutines
- **Media Processing:** AndroidX Media3 Transformer, `MediaStore`, `ContentResolver`
- **IDE & QA/Debugging:** Android Studio, ADB (Android Debug Bridge), Gradle (Kotlin DSL)
- **AI Tools:** Claude, Cline, Gemini / Google AI Studio
