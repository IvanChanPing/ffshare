# FFShare 2.0.0 automatic folder compression patch

## Confirmed upstream facts
- App id/package: com.caydey.ffshare
- Current release: 2.0.0
- Current upstream uses FFmpegKitNext / FFmpeg 8.1.2.

## Why monitoring uses JobScheduler TriggerContentUri
This is event-driven, not a timer/poll loop. Android wakes the app when MediaStore content changes.
TriggerContentUri jobs cannot be periodic or persisted. The JobService therefore re-schedules the
watch job after each callback, and BootReceiver restores it after reboot/app replacement.

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
    launchShareSheet: Boolean

The manual callback path remains unchanged; automatic work calls the file-output seam and waits for
FFmpegKit success. For auto mode the output type is selected from the original filename extension so
the requested
behavior is exact same filename, including extension.

Compilation and device execution were intentionally not run in this source-only turn.

## Important replacement behavior
The worker never deletes the original first. It encodes to app cache, creates and fully writes a staged
file in the same SAF folder, verifies its size, then deletes the source and renames the staged output to
the original name. A rename failure therefore leaves the compressed bytes recoverable instead of losing
both files.
