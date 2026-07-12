---
name: release
description: Build and publish an rl2 playtest release (Windows zip + Android APK to a GitHub Release). Use when the user asks to cut, build, or ship a release or playtest build.
---

# Cut an rl2 release

Follow `docs/releasing.md` (authoritative). Checklist:

1. Confirm a clean `git status` on `main` and that CI / `./gradlew test` is green.
2. Bump `rl2VersionName` and `rl2VersionCode` in `gradle.properties`; commit.
3. `./gradlew :desktop:jar` → wrap with `jpackage --type app-image` → zip as
   exactly `rl2-windows-x64.zip`.
4. `./gradlew :android:assembleDebug` → rename APK to exactly `rl2.apk`.
   (Play Store AAB instead: `:android:bundleRelease` with the `RL2_*`
   signing env vars — see docs/releasing.md.)
5. **Sanity-check artifact sizes** (jar ~31 MB). A ~380 MB artifact means the
   `assets/sfx/wav` / `assets/scratch` excludes broke — see the trap section
   in docs/releasing.md. After changing Android exclude patterns, run
   `:android:clean` first.
6. Write release notes to a file, then:
   `gh release create v<version>-playtestN rl2-windows-x64.zip rl2.apk --notes-file notes.md`
   (never inline `--notes` — PowerShell mangles multi-line strings).
7. Asset names are load-bearing: README.md links use
   `releases/latest/download/<asset-name>`.

Ask the user before publishing the GitHub Release — it's outward-facing.
