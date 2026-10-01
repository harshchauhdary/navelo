# Navelo verification

This records the original emulator baseline. The newer 1.0.1 build, real-TV fixes and thumbnail checks are recorded in [DEVICE_FIXES.md](DEVICE_FIXES.md); use its results and checksums for the current APKs.

Verified in this workspace on 27 September 2026. These are debug APKs for installation and device testing, not signed store releases.

## Final build

After sourcing `scripts/env.sh`, the following command completed with **BUILD SUCCESSFUL**:

```sh
./gradlew test :server-app:assembleDebug :tv-app:assembleDebug :server-app:lintDebug :tv-app:lintDebug
```

The local build log is `.tools/build-final.log`. The toolchain is JDK 17, Gradle 8.11.1, AGP 8.10.1, Kotlin 2.1.20 and compile/target SDK 36. The optional test fixture also compiled and installed successfully using `-PincludeQa=true :qa-fixture:assembleDebug`.

| Module | Unique tests | Debug executions | Release executions | Failures/errors/skips |
| --- | ---: | ---: | ---: | ---: |
| shared | 10 | 10 | 10 | 0 |
| server-app | 18 | 18 | 18 | 0 |
| tv-app | 12 | 12 | 12 | 0 |
| Total | **40** | **40** | **40** | **0** |

JUnit XML is under each module's `build/test-results/testDebugUnitTest` and `testReleaseUnitTest`; HTML reports are under `build/reports/tests`. Both app lint tasks pass with no errors. Remaining warnings concern dependency updates, API style recommendations, TV banner dimensions and a false positive on Android's `Context.stopService(Intent)` (the call uses an explicit service Intent, not a listener callback).

## Installable artifacts

| App | Debug APK | SHA-256 |
| --- | --- | --- |
| Phone | [server-app-debug.apk](../server-app/build/outputs/apk/debug/server-app-debug.apk) | `b97e370c540ec35ed0c898aa0f2cdab7a8cfc6551df94903fa881950f7c0af89` |
| TV | [tv-app-debug.apk](../tv-app/build/outputs/apk/debug/tv-app-debug.apk) | `dce27213b1eb7bcd33193d3157a4e9861579d123bf1770958e77bb3b6b0f097a` |

The APKs are approximately 17 MiB and 22 MiB. Both final APKs were installed successfully on the test emulator.

## Automated coverage

- Range parsing: closed, open-ended, suffix, empty/invalid/multipart/unsatisfiable ranges and 64-bit offsets.
- Real HTTP socket tests: authentication, `HEAD`, exact `206`/`416` headers and body boundaries, streaming capacity/resource cleanup, malformed requests, provider MIME sanitization and `nosniff`.
- Actual sparse files at 5, 20 and 45 GiB: seek directly to the tail with `FileChannel`, read the bounded payload and verify its bytes. This verifies large offsets and random access without allocating full-size media in RAM or on disk.
- Pairing: approval, request secret, expiration, revocation and bounded rate-limiter key storage.
- Discovery: live loopback UDP response and validated/sanitized beacons. NSD has timeout recovery; discovery cache and incoming replies are bounded.
- Stable IDs, wire serialization, filename parsing, metadata confidence/ambiguity/Unicode handling, reconnect backoff, watch/settings serialization and external subtitle association/language labels.

## Executed emulator acceptance checks

The environment was an Android 11/API 30 ARM64 ATD emulator, 720p landscape for TV. Both applications ran on this one emulator and communicated over real local HTTP sockets. This exercises application integration, but does **not** simulate two physical radios, a router or hotspot firmware.

ATD lacks the normal document picker. The isolated `qa-fixture` APK provided an actual Android DocumentsProvider and picker with persisted URI permissions and seekable file descriptors. No fixture code is included in the normal product build. It exposed a movie, external English SRT and two episodes in different seasons. Short video came from the Android emulator's bundled sample; the longer playback test used the [Media3 Big Buck Bunny test asset](https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4). Test titles are synthetic and screenshots show fallback artwork because no TMDB token was configured.

| Flow | Observed result |
| --- | --- |
| Phone setup and SAF selection | Folder chooser returned a persisted read grant; five distinct directory/media/subtitle entries were indexed; three videos appeared in the phone count. |
| Discovery and first pairing | TV found Media Phone automatically. Connect showed the verification code; Allow on the phone granted access and the TV loaded the structured library. |
| Activity closure and reinstall update | Trust and selected folders survived reopening and APK updates. Starting the service again restored access without re-pairing. |
| Movie playback | Original media streamed from the provider through the phone endpoint and rendered in Media3. |
| External subtitles | English SRT rendered over the movie; screenshot records visible subtitle text. |
| Remote seek/resume | Fast-forward transport keys advanced position; Back showed Resume. Persisted progress included position 97,439 ms and duration 596,459 ms during the check. |
| Durable watch state | Force-stopping/reopening the TV retained progress and displayed Continue Watching. |
| Offline browsing and recovery | Force-stopping the phone left cached Home available. Play showed a human recovery dialog. Restarting phone sharing allowed playback to recover without a new pairing. |
| Remote seasons | D-pad, Select and Back navigated Home → show → Season 2, with visible episode focus. |
| Episode progression | The 8.104-second first episode advanced automatically to the next episode across the season boundary; both episode progress records reached their durations and no duplicate MediaSession crash occurred. |

The emulator pass exposed and fixed provider package visibility after an app update, dark-theme text contrast, small-display poster sizing, offline dialog overflow, and MediaSession identity collisions on player replacement. The final source also preserves known duration across temporary playback errors and keeps scan work separate from published library reads.

## Screenshots

- [Phone ready](screenshots/phone-home.png)
- [Continue Watching](screenshots/tv-continue.png)
- [Movie detail](screenshots/tv-detail.png)
- [Playback with external subtitles](screenshots/tv-player.png)
- [Season 2 and remote focus](screenshots/tv-season2.png)
- [Offline recovery](screenshots/tv-offline.png)

## Verification boundaries

No physical phone or television was available. Router restart, IP roaming, phone-hotspot discovery, multicast filtering, OEM screen-off/power management, SD-card removal, sustained real 20+ GiB playback, a 45-minute media seek, multiple embedded audio/subtitle combinations, TalkBack/large-font acceptance and 1,000 movies/10,000 episodes still need the [device test matrix](DEVICE_TESTS.md). Discovery implements both shared Wi-Fi and phone-hotspot routes; the emulator run is not evidence that every OEM networking stack supports them.

Live TMDB matching/artwork needs an owner-supplied API Read Access Token. No credential was supplied, so network metadata/artwork retrieval and manual Fix Match were not live-tested. Matching logic, parsing and persistence are tested; the integration, encrypted token setting, caches, attribution and owner UI are implemented.

The next-episode automatic path was exercised. Separate physical-remote acceptance remains for every player menu, countdown Cancel/Play Now timing, alternate audio tracks and codec-specific failures. Cleartext authenticated LAN transport and provider/OEM limitations are documented in the [README](../README.md).
