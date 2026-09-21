# Changelog

- 2026-09-21 13:51 Kept manual selected-file compression alive when `HandleMediaActivity` is temporarily covered by removing the `onStop()` FFmpeg cancellation path; explicit Cancel and `finish()` cancellation remain. Source/static verified only; no compile, install, device UI, or runtime compression test was run.

- 2026-09-21 13:38 Fixed automatic folder compression not waking for MediaStore image, video, and audio collection changes by observing descendants of the provider root; source/static verified only, with compilation and device behavior still unverified.

- 2026-09-21 00:33 GitHub run `35545890479` successfully built FFShare debug APKs at `e77fe46fe57af9d4a8de3556f90b1ca794ba8cec` and published Release `build-5-1` with `app-arm64-v8a-debug.apk`, `app-armeabi-v7a-debug.apk`, `app-universal-debug.apk`, `SHA256SUMS`, and `BUILD-INFO.txt`. ARM64 APK SHA-256 matched `ee94086c68be46872ee3adaa74b03e65c91249b78a20b9281c36d81fea97b164`. Local-only, unpushed, unrun workflow correction now splits FFmpegKitNext cache restore/save so the verified native AAR is saved immediately before downstream APK compilation.

- 2026-09-20 23:50 Corrected the Kotlin compilation blockers exposed by GitHub run `35544053134`: `PreferencesFragment.kt` now imports and launches `AutoCompressSettingsActivity` through its explicit package, and `MediaCompressor.kt` uses FFmpegKit public session getter APIs plus the public coroutine `resume` API. Three-stage official/source contract research was completed for the Kotlin import and FFmpegKit/coroutine API corrections. Static verification passed with `git diff --check` and exact source readback; GitHub APK compilation and Release publication remain pending.

- 2026-09-20 19:21 Escaped the apostrophe in the automatic-compression Settings summary using Android's documented string-resource syntax. GitHub run `35529832199` proved the preceding FFmpegKitNext correction: native AAR build, diagnostics upload, and AAR verification all passed before `:app:mergeDebugResources` rejected the unescaped apostrophe. Primary Android documentation and AAPT2 source confirm the escape requirement. APK compilation and Release publication require the next push-triggered run.

- 2026-09-20 18:35 Corrected the FFmpegKitNext Nix archive build to use the pinned SDK Build Tools 35.0.0 AAPT2 through Android Gradle Plugin's supported `android.aapt2FromMavenOverride` property. The wrapper now applies the same contract to host-Nix and Docker runs, emits a bounded `build.log` tail on failure, and GitHub Actions retains the complete native build log as a diagnostic artifact. Verified before publication: the failed run's real `build.log` identifies `verifyReleaseResources` and Maven AAPT2 daemon startup as the first failure; Nixpkgs documentation prescribes the override; and the exact Nix-packaged AAPT2 executable launched successfully in the pinned `android-r27d` environment. Full AAR/APK compilation and Release publication remain pending.

- 2026-09-20 15:05 Automated successful-master-push APK builds and GitHub Releases: `.github/workflows/android-build.yml` now triggers on pushes to `master` while retaining manual dispatch, grants `contents: write`, publishes only after successful prior build/upload steps and only for `refs/heads/master`, creates unique run/attempt release tags targeting the triggering SHA, and uploads APKs, `SHA256SUMS`, and `BUILD-INFO.txt`. Static verification only: YAML/contract assertions, embedded Bash syntax, `git diff --check`, exact diff review, and no DONT TOUCH markers passed; no Android/APK build, workflow dispatch, master push, merge, or release run was performed.

- Added FFShare automatic folder compression source integration; XML/static checks passed, Android compilation and device/UI execution remain unverified.

- 2026-09-20 13:23 Completed reference-first automatic folder compression source implementation with generation-safe queueing, backup-first replacement, and static-only verification; no Gradle/Android compile.

- 2026-09-20 15:05 Automated successful-master-push APK builds and GitHub Releases after static verification only; no Android build.

- 2026-09-20 17:38 Fix the FFmpegKitNext GitHub Actions build by trusting the runner-owned /workspace bind mount inside the ephemeral Nix container; exact ownership failure and corrected flake evaluation verified, full APK/release pending.

- 2026-09-20 23:50 Corrected the Kotlin compilation blockers exposed by GitHub run 35544053134; GitHub APK compilation and Release publication remain pending.

- 2026-09-21 00:33 — Verified GitHub APK release build-5-1 and moved FFmpegKitNext cache save immediately after AAR verification.

- 2026-09-21 13:51 Kept manual selected-file compression alive when HandleMediaActivity is temporarily covered by removing the onStop FFmpeg cancellation path; explicit Cancel and finish cancellation remain. Source/static verified only; no compile, install, device UI, or runtime compression test was run.

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
