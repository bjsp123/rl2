# Releasing rl2

How playtest releases are built and shipped (first used for
v0.1.0-playtest1, July 2026).

## Version

Lives in `gradle.properties`: `rl2VersionName` / `rl2VersionCode`. Bump both
before a release. The in-game build stamp comes from `AppVersion` /
`BuildInfo.java`, generated at compile time from the git commit count by
the `:rlib:generateBuildInfo` task.

## Windows (x64)

1. `./gradlew :desktop:jar` — fat jar (~31 MB) including `assets/`
   (with `sfx/wav/**` and `scratch/**` excluded — see the trap below).
2. Wrap with `jpackage --type app-image` to produce a self-contained
   `rl2.exe` app folder.
3. Zip as **exactly** `rl2-windows-x64.zip` — README.md download links use
   `releases/latest/download/<asset-name>`, so asset names must not change.

## Android

- **Playtest APK:** `./gradlew :android:assembleDebug` → ship the
  debug-signed APK as **exactly** `rl2.apk` (fine for sideloading testers).
- **Play Store AAB:** `./gradlew :android:bundleRelease`. Signing reads env
  vars `RL2_KEYSTORE_PATH`, `RL2_KEYSTORE_PASSWORD`, `RL2_KEY_ALIAS`,
  `RL2_KEY_PASSWORD`. The upload keystore is
  `C:\Users\hwach\keystores\rl2-upload.jks` (alias `rl2`, password in
  `rl2-upload-password.txt` alongside it — both backed up externally by the
  owner, never committed). The env vars are persistent user vars, but a
  long-lived shell may predate them — set them inline from the password
  file if signing fails. Verify the AAB with `jarsigner -verify`.
  Output: `android/build/outputs/bundle/release/android-release.aab`.

## Publish

Create a GitHub Release on tag `v<version>-playtestN` with both assets:

```
gh release create v0.1.0-playtestN rl2-windows-x64.zip rl2.apk --notes-file notes.md
```

Use `--notes-file` — multi-line `--notes` strings get mangled by
PowerShell here-string quoting.

## ⚠ The assets-packaging trap

`assets/sfx/wav/` (~350 MB of source WAVs, gitignored) and
`assets/scratch/` live inside the assets tree that BOTH the desktop fat
jar and the Android asset merger bundle wholesale. Excludes exist in two
places and must be kept in sync:

- `desktop/build.gradle` jar task: `exclude 'sfx/wav/**'`, `'scratch/**'`
- `android/build.gradle`: `androidResources { ignoreAssetsPattern '!wav:!scratch' }`

If a new working folder appears under `assets/`, add it to **both**.
Android's asset merge is incrementally cached — after changing the
pattern, run `:android:clean` or the exclude silently won't take effect.
Sanity-check the shipped jar/APK size before uploading (jar ~31 MB; a
~380 MB artifact means the excludes broke).
