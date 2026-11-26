# FilmCraft Android

Android port of the [FilmCraft](https://github.com/storytold/filmcraft) non-linear video editor locked to landscape mode (`sensorLandscape`), packaged for `arm64-v8a` with native `wgpu` rendering, `AAudio` playback/recording, and Storage Access Framework integration.

## Highlights

- **Desktop Feature Parity UI**: Runs `FilmcraftApp` (`eframe` + `egui` + `wgpu`) via Android `GameActivity` (`androidx.games:games-activity:4.4.0`) with the full in-window menu bar, workspaces, panels, preferences, and hardware keyboard shortcuts.
- **Clean Startup Timeline**: Initializes every launch with a blank `Untitled` project and an empty timeline state with zero active clips or auto-loaded projects.
- **Native Android Host Bridge**:
  - Locked `sensorLandscape` orientation with short-edge display cutout & sticky immersive fullscreen support.
  - Storage Access Framework (SAF) file/project/folder pickers with URI staging and `FileObserver` flush-to-`Uri` export sync.
  - Android `ClipboardManager` (text, file URIs, and decoded bitmap RGBA images), `FileProvider` path viewing, external URL launching, system dark/light theme detection, and locale list sync.
  - Runtime permission handling for `RECORD_AUDIO` (voice-over recording) and media read permissions.
  - Android OS TLS certificate validation via `org.rustls:rustls-platform-verifier:0.2.0`.

## Tech Stack

- **Android Shell**: Kotlin (`2.1.0`), Android Gradle Plugin (`8.13.0`), `minSdk = 26`, `targetSdk = 35`, `compileSdk = 36`
- **Native Core**: `app/src/main/jniLibs/arm64-v8a/libfilmcraft_android.so` (Rust `filmcraft-android` targeting `aarch64-linux-android26`)
- **Graphics & Audio**: `wgpu` (Vulkan / GLES), `cpal` (`libaaudio.so`)

## Project Structure

```text
FilmcraftAndroid/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── jniLibs/arm64-v8a/libfilmcraft_android.so
│       ├── kotlin/com/filmcraftandroid/MainActivity.kt
│       └── res/
│           ├── values/strings.xml
│           ├── values/themes.xml
│           ├── values-v28/themes.xml
│           └── xml/file_paths.xml
├── build.gradle.kts
├── gradle/libs.versions.toml
├── gradle.properties
├── gradlew
└── settings.gradle.kts
```

## Build Instructions

```bash
./gradlew assembleDebug
```

The assembled APK will be written to `app/build/outputs/apk/debug/app-debug.apk`.
