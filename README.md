# Xperia Torch Booster rev002

Android root utility for the Xperia 1 III. This revision implements the XQ-BC62 latch behavior reported by the handset owner. Android Studio project, Kotlin, JDK 17, SDK 34.

Tap Refresh and grant root, enable the service, and toggle the Android flashlight. A framework ON callback allows a boost even if both torch nodes read zero. With both channels enabled, the app writes the requested level to torch_0 and torch_3 and then writes 1 to switch_0. Single-channel mode writes torch_0 and the latch. The switch readback is diagnostic only.

The fresh-install target is 100. Saved brightness preferences are retained and clamped into 25–200, or 25–300 with the extended range enabled. Each selected channel's driver maximum also caps the write. While the light is on, moving the slider updates brightness in either direction. Optional video monitoring looks for stable positive readings below the target. The 750 ms debounce and the separate 10-second manual preview are retained.

Boost continues for the active torch session. Turning off an external torch relies on Sony's HAL to reset the channels. Pause, Stop, and a thermal pause cancel further adjustments. An already latched external light can stay at its last intensity until switched off. The driver maximum of 500 does not establish a continuous thermal rating.

Build on Linux or another host with POSIX sh available for the shell regression tests:

    ./gradlew testDebugUnitTest lintDebug assembleDebug

The owner confirmed all functions working on a Sony Xperia 1 III XQ-BC62 on November 4, 2026. Compatibility with other devices requires testing.

Download the ready-to-install APK from the repository Releases page.

The APK is generated at app/build/outputs/apk/debug/app-debug.apk. This is a debug-signed build. On-device output and thermal behavior require handset verification.
