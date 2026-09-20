# Changelog

- 2026-09-20 15:05 Automated successful-master-push APK builds and GitHub Releases: `.github/workflows/android-build.yml` now triggers on pushes to `master` while retaining manual dispatch, grants `contents: write`, publishes only after successful prior build/upload steps and only for `refs/heads/master`, creates unique run/attempt release tags targeting the triggering SHA, and uploads APKs, `SHA256SUMS`, and `BUILD-INFO.txt`. Static verification only: YAML/contract assertions, embedded Bash syntax, `git diff --check`, exact diff review, and no DONT TOUCH markers passed; no Android/APK build, workflow dispatch, master push, merge, or release run was performed.

- Added FFShare automatic folder compression source integration; XML/static checks passed, Android compilation and device/UI execution remain unverified.

- 2026-09-20 13:23 Completed reference-first automatic folder compression source implementation with generation-safe queueing, backup-first replacement, and static-only verification; no Gradle/Android compile.

- 2026-09-20 15:05 Automated successful-master-push APK builds and GitHub Releases after static verification only; no Android build.

## 2026-09-20 13:23 UTC — Reference-first automatic folder compression implementation
- Completed the source implementation for reference-first automatic compression of new media in selected local shared-storage folders.
- Added the local-tree-only watcher/settings/workflow path, baseline and generation-safe queueing, reused FFShare compression bridge, foreground `dataSync` WorkManager execution, per-session FFmpeg cancellation, and backup-first verified same-folder replacement with rollback and recovery state.
- Static verification passed: `git diff --check`; XML parsing; resource/reference assertions; manifest/service/receiver/foreground-service checks; replacement and JobScheduler ordering checks; two-trigger/no-periodic watcher assertion; SQLite generation simulation; bridge signature check; Bash syntax; workflow YAML parse.
- Unverified/not run: Gradle/Android compilation, APK generation, install, Settings UI/picker, JobScheduler callback delivery, WorkManager/FFmpeg runtime, physical SAF replacement, and GitHub push were not performed.

## 2026-09-19 15:50 — FFShare automatic folder compression
- Added source integration for automatic compression of newly added media in selected SAF folders.
- Changed files: `app/build.gradle`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/caydey/ffshare/PreferencesFragment.kt`, `app/src/main/java/com/caydey/ffshare/utils/MediaCompressor.kt`, `app/src/main/java/com/caydey/ffshare/utils/Utils.kt`, `app/src/main/res/values/strings.xml`, `app/src/main/res/xml/preferences.xml`, `app/src/main/java/com/caydey/ffshare/autocompress/AutoCompressPrefs.kt`, `app/src/main/java/com/caydey/ffshare/autocompress/AutoCompressScheduler.kt`, `app/src/main/java/com/caydey/ffshare/autocompress/AutoCompressSettingsActivity.kt`, `app/src/main/java/com/caydey/ffshare/autocompress/AutoCompressWorker.kt`, `app/src/main/java/com/caydey/ffshare/autocompress/BootReceiver.kt`, `app/src/main/java/com/caydey/ffshare/autocompress/CompressionBridge.kt`, `app/src/main/java/com/caydey/ffshare/autocompress/FolderScanner.kt`, `app/src/main/java/com/caydey/ffshare/autocompress/MediaCandidate.kt`, `app/src/main/java/com/caydey/ffshare/autocompress/MediaChangeJobService.kt`, `app/src/main/java/com/caydey/ffshare/autocompress/SeenDb.kt`, `docs/FFSHARE_AUTOCOMPRESS_INTEGRATION.md`, `docs/FFSHARE_AUTOCOMPRESS_TASK_JOURNAL.md`, and `CHANGELOG.md`.
- Verified: XML parsing, static contract assertions, `git diff --check`, unfinished-hook scan, and trailing-whitespace scan passed before bookkeeping.
- Unverified: Android compilation, APK build, Settings UI/device execution, folder picker grants, MediaStore wakeups, WorkManager runtime execution, FFmpegKit runtime compression, and same-folder replacement on a device were not run under the current gate.

## 2026-09-19 18:08 — FFShare build prerequisites and FFmpegKit builder preflight
- Changed source/config prerequisites: `.gitignore`, `app/build.gradle`, `build_ffmpegkit.sh`, and `docs/FFSHARE_AUTOCOMPRESS_INTEGRATION.md`; bookkeeping also updated `docs/FFSHARE_AUTOCOMPRESS_TASK_JOURNAL.md` and `CHANGELOG.md`.
- Verified: release signing now reads all four `FFSHARE_RELEASE_*` values with `findProperty`, creates release signing only when all four are nonblank, and leaves debug independent of private release secrets.
- Verified: ignored `local.properties` points `sdk.dir` at `/opt/android-sdk`, which resolves to `/mnt/HC_Volume_105518598/android-sdk`; `android-34/android.jar` and build tools `34.0.0` are present.
- Verified: `build_ffmpegkit.sh` passed Bash syntax and `--check`, pins `v8.1.1` plus `nixos/nix:2.35.2`, preserves an ignored resumable checkout, verifies exact tag/clean tracked state/nonempty expected AAR, and atomically installs the verified-size AAR.
- Verified: `git diff --check`, ignore checks, exact reference scans, SDK file checks, and Docker tag manifest lookup passed.
- Unverified/not run: no Gradle, Android, APK, or native FFmpeg compilation ran; no AAR or APK is present; runtime/device/UI behavior remains unverified.
- Build-route note: Docker image `nixos/nix:2.35.2` is not locally cached, and Docker data-root `/var/lib/docker` is on root-backed `/dev/sda1`; before authorized native build, use host Nix or a Docker daemon whose data-root is on the mounted data volume. The image was not pulled.
