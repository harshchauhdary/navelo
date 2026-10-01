# Navelo 1.0.1: device follow-up

This update follows testing on a Xiaomi MiTV AXSO0 running Android 9 and a Pixel 9 Pro running Android 17, plus the isolated Android 11 document-provider fixture. The original emulator-only baseline is in [VERIFICATION.md](VERIFICATION.md).

## Playback crash and unstable connection

The TV crash log showed `IllegalArgumentException: Invalid URL host` for an IPv6 link-local address containing `%wlan0`, originating in `TvRepository.mediaUrl()` before ExoPlayer was created. NSD and UDP discovery alternated between that address and the phone's usable IPv4 address. Discovery previously replaced the active route immediately, causing both offline-banner flicker and the Play crash.

The TV now keeps a working route, rejects addresses unsupported by its HTTP stack, validates replacement identities before promotion, and constructs media URLs without throwing. A single delayed health response no longer marks an otherwise healthy phone offline. Regression tests cover the exact scoped-IPv6 failure, normal IPv6, encoded media IDs, stable routes and hotspot address recovery. Encrypted credentials are read off the UI thread and cached for the active connection, and startup store reads run concurrently.

## Remote controls

On the real TV, focus could leave the embedded Media3 view when its focused control disappeared. `MainActivity` now routes playback navigation/media keys to the active player even if that happens. Routing is removed on exit and yields to next-episode/retry overlays. The player also restores focus when controls hide. Back first hides visible controls; another Back leaves playback.

Real-TV checks verified controls reappearing after auto-hide with Up, visible pause/play controls, transport state changing between paused and playing, seeking, and Back returning from playback. The earlier IPv6 crash did not recur during these checks.

## Launch appearance

Phone launch resources now provide explicit branded day/night backgrounds. Android 12+ synchronizes the saved app appearance through `UiModeManager`; older versions follow the system appearance during the platform launch window. The TV has a branded navy launch window instead of an empty black screen. The owner confirmed playback, connection and both splash screens working after the first device fix.

## Offline cover fallback

The TV had no TMDB token configured. Without one, downloaded provider posters are unavailable. Navelo now falls back to a frame from the video when provider artwork is absent or fails:

- The paired phone serves authenticated `GET /api/v1/thumbnail/{id}` JPEGs, no larger than 640×360.
- Frames come from the existing SAF descriptor. The video stream is never transcoded or copied.
- The phone cache is capped at 48 MiB. One extraction and one waiter (maximum two seconds) prevent cover requests from occupying the server's streaming workers.
- Full-frame fallback has an 8 MiB decoded-frame guard; oversized/unknown frames are skipped on devices lacking scaled extraction.
- TV caches use phone identity, media ID, modification time and size, so changing Wi-Fi addresses does not discard artwork.
- Temporary busy responses get at most four attempts with staggered delays; permanent failures keep a placeholder.
- Pairing credentials go only to the local thumbnail request, and redirects are disabled.

Integration testing found that Coil's default network observer forced `only-if-cached` requests when Android reported no internet, producing HTTP 504 without contacting the phone. It is now disabled so LAN requests work on a hotspot or Wi-Fi without internet. Actual frame extraction produced cached JPEGs in the isolated test, and artwork remained visible after a cold TV restart with the phone process stopped. See [offline thumbnail evidence](screenshots/tv-thumbnails-offline.png).

Final hardware verification on September 28 installed both APKs in place on the Pixel and MiTV. The Pixel generated a 6,554-byte JPEG from the user's existing video, and the TV displayed it in Continue Watching, Recently Added and the movie detail poster/backdrop. The selected frame was opening credits, so this fallback is a video still rather than official poster art. The final TV build also restored controls with Up after auto-hide while the media session remained playing.

TMDB remains optional for official posters, descriptions and episode metadata. Local frames work without its token. Unreadable providers or unsupported codecs can still retain a placeholder.

## Build evidence

Version code 2, version name 1.0.1. The following completed successfully:

```sh
. scripts/env.sh
./gradlew test :server-app:assembleDebug :tv-app:assembleDebug :server-app:lintDebug :tv-app:lintDebug
```

49 unique JVM tests pass in both debug and release: 98 executions, zero failures/errors/skips. Breakdown: shared 10, server 22, TV 17. Both Android lint tasks pass with no errors. The log is `.tools/artwork-controls-final.log`.

| APK | SHA-256 |
| --- | --- |
| [Navelo Server](../server-app/build/outputs/apk/debug/server-app-debug.apk) | `edfb899a7340478204daca7a9029895ac7588add630ec7230eaaf0e877c15b32` |
| [Navelo TV](../tv-app/build/outputs/apk/debug/tv-app-debug.apk) | `87192231307a1037a126336241074b9f480e099d2d1014f156cc93df23cec9b6` |

Both apps need this update for the thumbnail fallback. Install in place to retain folder grants, pairing and viewing progress. Hardware coverage is still limited to the devices above; large libraries, other OEMs and broader codec combinations remain in the [acceptance matrix](DEVICE_TESTS.md).

## App-owned TMDB credential follow-up

On September 28, the TV APK was rebuilt with the ignored local app credential and installed in place on the MiTV with `adb install -r`, preserving its pairing and viewing state. The real Settings screen reported that movie and show information was available through Navelo's included TMDB access, confirming the credential-bearing BuildConfig path reached the installed app. The home screen continued to show the existing phone-generated frame for *500 Miles*, and sanitized inspection of the library cache reported one item and zero TMDB metadata entries.

Direct HTTPS checks from the development computer did not complete a connection to the TMDB API during the bounded verification attempts. This does not establish whether the TV has the same connectivity issue. A manual TV **Fix Match** search for *500 Miles* was started from the focused search action, but the observed screen returned to Home before a result or error could be recorded. Recent logs contained no Navelo fatal exception, and sanitized inspection still showed zero cached metadata entries. Automatic provider metadata, poster download, and credential acceptance by TMDB therefore remain unverified; the app's offline title and thumbnail behavior continued to work. The credential was never written to tracked files or verification output.

The rebuilt TV APK is 22,785,316 bytes with SHA-256 `34235973ee98584569a47d77a474f1f5cafc31817fb649b0d9c7d973aee084e6`. `:tv-app:assembleDebug` completed successfully; its log is `.tools/tmdb-live-build.log`. The earlier hashes above remain the historical thumbnail-fallback build evidence.
