# Phone Buddy

Fast, 100% offline on-device AI companion and personal assistant for Android (Samsung Galaxy S24 / ARM64), powered by native `llama.cpp` and `LFM2.5-1.2B-Instruct`.

---

## Editions

Phone Buddy comes in two standalone product flavors that install side-by-side on your device:

| Feature | Phone Buddy Light (`com.phonebuddy.light`) | Phone Buddy Full (`com.phonebuddy.app`) |
| :--- | :--- | :--- |
| **Interface** | Pure ChatGPT-Style `#0d0d0d` Dark UI | 3 Character Persona Strip + Equalizer |
| **Target Use** | Smart Personal Assistant & Productive Tasks | Casual Companion & Dynamic Banter |
| **Capabilities** | Step-by-step intermediate math, drafted emails, notes context | Jay, Mack, Nora characters with equalizer levers |
| **Language** | Automatic Bilingual (English / Russian) | English / Russian |
| **Brain** | 1.2B Q8_0 GGUF via ARM64 `llama.cpp` | 1.2B Q8_0 GGUF via ARM64 `llama.cpp` |
| **Package** | `com.phonebuddy.light` (v0.8.0-light) | `com.phonebuddy.app` (v0.8.0 / v0.6.0) |
| **APK Size** | **7.33 MB** | **7.33 MB** |

---

## Direct Downloads (GitHub Releases)

Download and install directly to your Samsung Galaxy S24 or any ARM64 Android device:

* **[Download Phone Buddy Light APK (`v0.8.0-light`)](https://github.com/mikelmike/phone-buddy/releases/download/v0.8.0-light/phone-buddy-light.apk)** — *Recommended for everyday assistant use*
* **[Download Phone Buddy Full APK (`v0.8.0-light`)](https://github.com/mikelmike/phone-buddy/releases/download/v0.8.0-light/phone-buddy-full.apk)** — *Multi-character companion edition*
* **[GitHub Releases Page](https://github.com/mikelmike/phone-buddy/releases/tag/v0.8.0-light)**

---

## Web vs Native APK Performance

> [!IMPORTANT]
> **Why Native APK?**
> When running local LLMs in a mobile web browser via WebGPU/WebLLM, the browser attempts to fetch ~1.5 GB of sharded weights over HTTP into mobile IndexedDB, often exhausting mobile tab memory limits (1.5–2.0 GB VRAM cap) and causing severe shader compilation lag or browser crashes.
> 
> The **Native Android APK** bypasses browser restrictions completely:
> - Direct memory-mapped file access (`mmap`) from high-speed UFS 4.0 storage.
> - Hand-tuned C++ ARMv8.4-A NEON & DotProd SIMD kernels (`llama.cpp`).
> - Sustained **25–30 tokens/second** on the Snapdragon 8 Gen 3 with zero network lag and 100% privacy.

- **Web Preview:** https://mikelmike.github.io/phone-buddy/
- **Light Assistant Web Preview:** https://mikelmike.github.io/phone-buddy/light/

---

## One-Time Model Setup & Shared Storage

Both editions share the same quantized brain (`LFM2.5-1.2B-Instruct-Q8_0.gguf`, ~1.2 GB). 
Phone Buddy Light automatically checks shared public storage (`/sdcard/Download/PhoneBuddy/`) and sibling app directories. If you already downloaded the offline brain in Phone Buddy Full, Phone Buddy Light reuses it instantly with **0 MB duplicate download**.

---

## Building from Source

Prerequisites: Android SDK (API 34/36), NDK r27c, JDK 17, Gradle 8.9+.

```bash
# Clone the repository
git clone https://github.com/mikelmike/phone-buddy.git
cd phone-buddy/android

# Build Phone Buddy Light Release APK
./gradlew assembleLightRelease

# Build Phone Buddy Full Release APK
./gradlew assembleFullRelease
```

Output APKs:
- Light: `android/app/build/outputs/apk/light/release/app-light-release.apk`
- Full: `android/app/build/outputs/apk/full/release/app-full-release.apk`

---

## Documentation

Detailed technical design, root-cause analysis, and architectural diagrams are available in:
- [PHONE_BUDDY_LIGHT_IMPLEMENTATION.md](docs/PHONE_BUDDY_LIGHT_IMPLEMENTATION.md)
