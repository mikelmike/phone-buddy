# Phone Buddy (`v0.6.0`)

Clean minimalist mobile AI buddy with 3 characters (**Jay**, **Mack**, **Nora**), small mic input, 1-tap full conversation export (`Save`), and a fast **100% offline 1.2B brain** (`LFM2.5-1.2B-Instruct`).

## Direct Links (Phone)

- **Open Web App Directly (GitHub Pages):** https://mikelmike.github.io/phone-buddy/
- **Download 100% Offline Android APK (`phone-buddy.apk`):** https://github.com/mikelmike/phone-buddy/releases/latest/download/phone-buddy.apk

## Offline Android Setup (Samsung S24 / ARM64)

1. Tap the **Android APK** link above on your phone and install **Phone Buddy**.
2. Open the app and tap **Download Offline Brain** once (~1.2 GB 8-bit `LFM2.5-1.2B-Instruct` GGUF).
3. After the one-time download finishes, Phone Buddy runs **100% offline** natively on your phone via `llama.cpp` (`ARMv8.2 dotprod + fp16` + prefix snapshot cache).
4. Tap **Save** at the top right anytime to save the entire conversation to `Downloads/PhoneBuddy/` and copy it to your clipboard.
