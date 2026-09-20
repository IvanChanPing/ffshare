# FFShare 2.0.0 automatic folder compression patch

## Confirmed upstream facts
- App id/package: com.caydey.ffshare
- Current release: 2.0.0
- Current upstream uses FFmpegKitNext / FFmpeg 8.1.2.

## Why monitoring uses JobScheduler TriggerContentUri
This is event-driven, not a timer/poll loop. Android wakes the app when MediaStore content changes.
TriggerContentUri jobs cannot be periodic or persisted. The JobService therefore re-schedules the
watch job after each callback, and BootReceiver restores it after reboot/app replacement.

The selectable scope is deliberately limited to local shared-storage trees provided by
`com.android.externalstorage.documents`. Cloud and virtual document providers are rejected because
they do not guarantee MediaStore wakeups or synchronous close/rename behavior. Each wakeup enumerates
the direct children of every selected directory once; repeated `DocumentFile.findFile` IPC scans are
not used.

## Integrated dependencies
The repository now contains these dependencies in `app/build.gradle`:

    implementation "androidx.documentfile:documentfile:1.0.1"
    implementation "androidx.work:work-runtime-ktx:2.9.1"
    implementation "androidx.activity:activity-ktx:1.9.3"

They were absent from the inspected upstream dependency list, so each appears once.

## Integrated manifest contract
The repository now declares these permissions:

    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

Inside `<application>`, the repository now declares:

    <activity
        android:name=".autocompress.AutoCompressSettingsActivity"
        android:exported="false" />

    <service
        android:name=".autocompress.MediaChangeJobService"
        android:permission="android.permission.BIND_JOB_SERVICE"
        android:exported="true" />

    <receiver
        android:name=".autocompress.BootReceiver"
        android:enabled="true"
        android:exported="false">
        <intent-filter>
            <action android:name="android.intent.action.BOOT_COMPLETED" />
            <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
        </intent-filter>
    </receiver>

The WorkManager `SystemForegroundService` is explicitly merged with
`foregroundServiceType="dataSync"` for the current target SDK 34 contract.

## Integrated Settings screen
The Settings XML contains one preference row titled "Automatic compression". `PreferencesFragment`
opens:

    Intent(requireContext(), AutoCompressSettingsActivity::class.java)

No timer, alarm, or periodic worker should be used for monitoring.

## Integrated CompressionBridge
`CompressionBridge` now calls `MediaCompressor.compressToFile`, which uses FFShare's existing
`Settings`, `FFmpegParamMaker`, `FFprobeKit`, `FFmpegKit`, EXIF, and log paths. It does not create a
second settings store or launch the share sheet. The shared method accepts:

    input Uri
    output File
    originalName: String

The manual callback path remains unchanged; automatic work calls the file-output seam and waits for
FFmpegKit success. For auto mode the output type is selected from the original filename extension so
the requested
behavior is exact same filename, including extension.

Compilation and device execution were intentionally not run in this source-only turn.

## Build prerequisites

Debug builds do not require FFShare's private release-signing properties. The Gradle file creates
the release signing configuration only when all four `FFSHARE_RELEASE_*` project properties exist;
otherwise Android's normal debug key remains available and release output remains unsigned.

This checkout's machine-local `local.properties` must contain a valid `sdk.dir`. It remains ignored
by Git. The application also requires the locally built `app/libs/ffmpeg-kit-next-8.1.1.aar`.
Run `./build_ffmpegkit.sh --check` to validate the builder without compiling, then run
`./build_ffmpegkit.sh` when native Android compilation is authorized. The script uses host Nix when
available or the pinned `nixos/nix:2.35.2` Docker image, preserves an ignored resumable source tree,
verifies upstream tag `v8.1.1`, and copies the AAR only after a non-empty output is present.

When Docker must use a separate daemon without a bridge, point `DOCKER_HOST` at that daemon and set
`FFMPEG_KIT_DOCKER_NETWORK=host`. The builder passes that network mode only to its FFmpegKit container;
normal Docker builds keep Docker's default network behavior.

## Important replacement behavior
The worker never deletes the original first. It encodes to app cache, creates a staged file in the same
SAF directory, and verifies the staged size and SHA-256 digest. It then renames the original to a hidden
backup, promotes the staged file to the exact original name, and re-reads and hash-verifies the promoted
document. Only after that verification succeeds does it delete the original backup. A failed promotion
rolls the backup back to the original name; a provider failure that prevents rollback is recorded as a
visible recovery state while the retained document URI is preserved in the state database.

The durable queue key is `(folder URI, exact display name)`, not the document URI: Android explicitly
permits a rename to return a different URI. Snapshot size and modification time distinguish a later new
file reusing the same name from FFShare's already-produced replacement.

## Reference implementations used

- AOSP `PhotosContentJob`: one-shot content-trigger scheduling, re-register-before-`jobFinished`, and
  specific-plus-generic MediaStore triggers.
- PebbleKitAndroid2 sample: maintained app-level `JobScheduler`/`TriggerContentUri` wiring.
- AndroidX `DocumentFile`: rename failure handling and the contract that rename can change the URI.
- Mihon/UniFile: avoid repeated `findFile` calls because SAF operations are provider IPCs; enumerate once.
- Mihon Harmony and RSAF: temp promotion needs rename failure handling, and non-local providers have
  weaker close/rename semantics. FFShare therefore accepts only Android local shared-storage trees.
