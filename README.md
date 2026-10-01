# Navelo

Navelo is a native Android product for watching video files stored on a phone from an Android TV or Google TV. The phone app exposes owner-selected folders and streams their original bytes; the TV app discovers the phone, presents a poster-first library, and plays through Media3. Everyday use is designed to be **open Navelo on TV → choose a title → Play**. There are no accounts, cloud media uploads, IP addresses, URLs, or server controls in the parent flow.

This repository builds two independent applications:

- **Navelo Server** (`app.navelo.server`) for the Android phone
- **Navelo TV** (`app.navelo.tv`) for Android TV / Google TV

Both require Android 8.0/API 26 or newer and target API 36. The implementation uses Kotlin 2.1.20, Java 17, Compose, coroutines, DataStore, Android's Storage Access Framework, Android NSD, NanoHTTPD, Media3, OkHttp, and Coil.

The current update is **1.0.4**, adding device-based server names, custom naming in phone settings, duplicate-name labels on TV, and paired-TV management from the phone home screen. [Physical-device follow-up](docs/DEVICE_FIXES.md) preserves the historical 1.0.1 Xiaomi TV crash, remote-control, splash, and offline-thumbnail evidence.

## Build and install

With JDK 17 and Android SDK 36 configured, run:

```sh
./gradlew test :server-app:assembleDebug :tv-app:assembleDebug lint
```

To enable richer metadata, set a TMDB API Read Access Token in the environment or in the ignored root `local.properties` file before building. The environment takes precedence:

```properties
NAVELO_TMDB_READ_TOKEN=your_tmdb_api_read_access_token
```

The app-owned credential is compiled only into the phone/server APK and can be extracted by someone who receives that APK. Local configuration remains ignored and no credential is committed to this repository. Omitting the setting produces a valid offline-capable build; filename metadata and phone-generated thumbnail artwork continue to work. The TV APK contains no TMDB credential or personal-token setting.

For this workspace, `. scripts/env.sh` selects the repository-local JDK, Android SDK, and Gradle cache before running Gradle. The normal product build contains only `shared`, `server-app`, and `tv-app`. The isolated document-provider fixture used for emulator acceptance testing is included only with `-PincludeQa=true`:

```sh
./gradlew -PincludeQa=true :qa-fixture:assembleDebug
```

Install the phone and TV builds on their respective devices:

```sh
adb install -r server-app/build/outputs/apk/debug/server-app-debug.apk
adb install -r tv-app/build/outputs/apk/debug/tv-app-debug.apk
```

The first phone setup is three short steps: choose one or more media folders, start Navelo, then approve the TV once. The TV remembers that phone by stable server identity and reconnects in the background.

## 1. Architecture overview

`shared` contains only code used by both apps: versioned wire models, including the metadata protocol, range and filename parsers, stable IDs, Android Keystore-backed secret storage, NSD, and UDP fallback discovery. `server-app` owns SAF scanning, the private library index, TMDB matching and artwork, pairing, the authenticated HTTP server, streaming, the foreground service, and phone UI. `tv-app` owns cached startup, reconnect, offline metadata presentation, watch state, couch UI, and Media3 playback.

The phone never transcodes, re-encodes, hashes whole files, or uploads media. Its streaming path is essentially `ContentResolver file descriptor → bounded stream → LAN socket`. The TV owns decoding and presentation. See [the engineering guide](docs/ARCHITECTURE.md) for lifecycle, storage, security, and performance details.

## 2. Implementation ownership

Three specialized GPT-5.6 Sol work streams were used with Extra High reasoning:

- **Server/backend:** SAF, scanning, stable media access, HTTP, ranges, pairing, foreground service, and server tests.
- **TV core:** repository, discovery/reconnect integration, metadata, watch state, Media3 playback, and core tests.
- **TV UI:** Compose for TV screens, remote navigation, focus behavior, branding, accessibility, and visual polish.

The lead defined shared contracts, implemented shared discovery/security/parsing and the phone UI, integrated and reviewed all work, fixed cross-module issues, and performed build and emulator acceptance passes. Final review and delivery documentation were separate passes rather than unreviewed agent output.

## 3. Shared protocol and API

Protocol v1 uses JSON for structured data and opaque IDs for media. A media ID is SHA-256 over its source root and provider document identity; filenames and raw `content://` URIs are never used as remote locators. Library generations increase only when public library data changes.

| Endpoint | Access | Purpose |
| --- | --- | --- |
| `GET /api/v1/server` | Public | Stable server identity, API version, capabilities, current revision |
| `POST /api/v1/pair` | Public, rate-limited | Start an approval request with a 256-bit client secret |
| `GET /api/v1/pair/{requestId}` | Pair-secret protected, rate-limited | Poll approval and receive the bearer token during the request lifetime |
| `GET /api/v1/library` | Bearer token | Configured roots and availability |
| `GET /api/v1/items` | Bearer token | Revision-consistent pages, up to 500 items per page |
| `POST /api/v1/rescan` | Bearer token | Queue a background scan and return `202 Accepted` with the current snapshot |
| `GET`, `HEAD`, `OPTIONS /media/{id}` | Bearer token | Original video or subtitle bytes with single-range support |
| `GET /api/v1/thumbnail/{id}` | Bearer token | Cached JPEG video frame, up to 640×360 |
| `GET /api/v1/metadata` | Bearer token | Return the cached metadata snapshot immediately and continue matching in the background |
| `GET /api/v1/metadata/search` | Bearer token | Search provider metadata for one library item and query |
| `POST /api/v1/metadata/match` | Bearer token | Save an owner-selected provider match for a library item |
| `POST /api/v1/metadata/clear` | Bearer token | Clear phone-owned provider metadata and artwork caches |
| `GET /api/v1/artwork/{id}` | Bearer token | Serve cached provider artwork through an opaque local URL |
| `POST /api/v1/playback` | Bearer token | Best-effort playback status for the phone's human-readable status UI |

Media supports closed, open-ended, and suffix byte ranges. Valid ranges return `206`, `Accept-Ranges`, `Content-Range`, exact 64-bit `Content-Length`, and the original MIME type. Invalid or multipart ranges return `416` with `Content-Range: bytes */SIZE`. A nonseekable provider fails a seek explicitly; Navelo never reads from byte zero to fake a large seek. The exact request, pagination, status, and pairing rules are in [the v1 contract](docs/CONTRACT.md).

## 4. Discovery and pairing

Discovery works in both required topologies:

- phone and TV connected to the same Wi-Fi router;
- phone hotspot enabled, with the TV connected directly to that hotspot.

The server advertises `_navelo._tcp.` over Android NSD/mDNS. A small UDP fallback also probes interface broadcasts, the limited broadcast address, and default gateways on port 8766. This covers hotspot and router firmware that filters multicast. The TV accepts the datagram's source address, then validates the public `/api/v1/server` identity; it never trusts an advertised URL. Discovery has no internet or validated-network requirement.

Server names default to the Android device name when available, otherwise the manufacturer and model. In phone **Settings → Server name**, save a custom name or select **Use device name** to reset it. Names update in discovery and on the TV without changing the server UUID or pairing. The TV adds a short stable ID to discovery labels when multiple servers have the same name.

Pairing resembles connecting a household device: the TV shows the discovered phone and a six-digit verification code, the phone names the requesting TV, and the owner taps **Allow**. Requests expire after five minutes. Approval also requires the request's random client secret, so the displayed code alone cannot grant access. The TV encrypts its bearer credential with Android Keystore; the phone stores only its SHA-256 hash and lets the owner revoke a device. Trust follows the server UUID across router restarts, hotspot changes, and new IP addresses.

## 5. Navelo Server features

- Multiple Movies, TV Shows, or Other roots through `ACTION_OPEN_DOCUMENT_TREE`, with persisted read grants for internal storage, SD cards, nested folders, and `content://` providers.
- Breadth-first batched `DocumentsContract` scans, structured filename hints, stable IDs, private locators, retained revision snapshots, and cached content when a root is temporarily unavailable.
- Background rescans that leave the last immutable snapshot readable and playable; document/storage notifications are debounced and a manual **Find new videos** action remains available.
- Phone-owned, bounded background metadata matching and provider-artwork caching. Metadata responses never wait for a provider refresh.
- Authenticated NanoHTTPD with bounded request bodies, workers, active media streams, buffers, and resource locks. There is no full-file buffering or transcoding.
- A connected-device foreground service that survives activity closure, screen off, and phone lock, with Open and Stop notification actions.
- Three-step onboarding; simple Ready/Paused status; media counts; pairing approval; trusted-device revocation; folder management; light, dark, system, and dynamic color; technical details under Advanced.

## 6. Navelo TV features

- Cached Home renders immediately while discovery, reconnect, library revision checks, and an independent metadata poll continue in the background.
- Continue Watching, Recently Added, Movies, TV Shows, and Recently Watched rows; search across movies, shows, and episodes.
- Movie details with Play/Resume/Start Over and metadata; show details with numeric season selection, episode names, thumbnails, summaries, duration, and progress.
- Exact row/item and horizontal focus restoration, visible scale/border/contrast focus, predictable D-pad/Select/Back behavior, and layouts adjusted for smaller 720p-class displays.
- Cached offline browsing, automatic identity-based recovery when the phone reappears, human error messages, Retry actions, Switch Server, Rescan, Fix Match, cache clearing, and diagnostics in owner settings.
- Local watch state, configurable auto-next and subtitles, and settings stored separately from the larger library cache so frequent progress updates remain small.

## 7. Metadata approach

Filename and folder parsing supplies readable titles, years, shows, seasons, and episodes without internet. Optional richer metadata is resolved by the phone through TMDB. Release builders can include one app credential in the phone/server APK; the TV never receives it and has no personal-token override. No credential is committed to this repository. Exact, high-confidence title/year matches can resolve automatically in bounded background work. Ambiguous results stay out of the parent flow until the owner chooses **Library → Fix Match**, which searches and saves the selection through the paired phone.

The phone exposes a versioned `MetadataSnapshot` and local relative artwork URLs under `/api/v1/artwork/`. Its provider requests, matching work, and artwork cache are bounded. The TV polls metadata independently from the media-library revision, persists the last complete snapshot and artwork for offline browsing, and does not mark video playback offline when a metadata poll fails. Episode detail hydration includes show/season context, episode titles, descriptions, stills, and air dates when available. Navelo includes TMDB attribution but is not endorsed or certified by TMDB. Only the phone's optional metadata and artwork downloads leave the local network.

If TMDB is unconfigured, unavailable or has no image, TV artwork falls back to a video frame from the phone through `/api/v1/thumbnail/`. This needs only the local connection. The phone extracts a small frame on demand and retains at most 48 MiB of thumbnails; TV caches use stable phone/media identity, so address changes do not invalidate the artwork. One active extraction and one short wait bound phone work; temporary busy responses receive a few staggered retries. Videos remain original-byte streams. Unsupported providers/codecs keep the generic placeholder. Android 8.0 skips full-frame extraction for large or unknown dimensions to bound memory.

## 8. Playback implementation

Media3/ExoPlayer reads the authenticated `/media/{id}` endpoint through OkHttp with redirects disabled, preserving the bearer credential on the intended LAN host. It uses TV hardware decoding and the server's true range seeking for pause, resume, rewind, fast-forward, and arbitrary seeks. Embedded Media3 tracks and same-folder external SRT, VTT, SSA/ASS, TTML, and SubRip files are available with human language labels; one obvious external subtitle is selected by default when subtitles are enabled.

Position, duration, watched status, and last-played time are durable local TV state. Progress is saved periodically and on pause/back/lifecycle changes. An unfinished title defaults to Resume, while Start Over is explicit. Episodes are numerically ordered across seasons, with a configurable 10-second next-episode prompt and focused Play Now/Cancel controls. Media sessions support remote transport keys. Errors distinguish unavailable media, revoked pairing, temporary folder access, network recovery, and unsupported TV codecs.

## 9. UI and UX decisions

The design uses a distinct deep-navy, mint, and warm-amber identity with a play-and-wave icon. Phone screens follow Material 3 and keep network details under Advanced. TV screens favor artwork and readable titles over chrome, use lazy rows, preserve focus across navigation, and always expose focus through shape/scale plus contrast rather than color alone. Primary actions use parent language such as **Play**, **Resume**, **Try Again**, and **Find new videos**; raw filenames and connection diagnostics stay in owner tools.

Dedicated UX passes reduced setup length, clicks, poster size on short screens, row spacing, and technical error wording; checked Back behavior, default action focus, offline cached Home, and Season 2/next-episode access; and added phone TalkBack descriptions, large touch targets, and TV couch-distance focus states.

## 10. Test results

The JVM suite covers 64-bit closed/open/suffix/invalid ranges, exact HTTP headers and payload boundaries, real sparse `FileChannel` seeks at 5, 20, and 45 GiB offsets, stable IDs, serialization boundaries, live loopback UDP discovery, bearer access, pairing secrets/expiry/revocation, request rate limits, filename parsing, Unicode metadata matching, ambiguity rejection, reconnect backoff, watch-state persistence, external subtitle association, and language labels.

The test-only document provider enables emulator checks with a real persisted SAF tree while remaining absent from production builds. Executed emulator flows and screenshots, including what was and was not verified, are recorded in [verification evidence](docs/VERIFICATION.md). Physical-device acceptance procedures are in [the device test plan](docs/DEVICE_TESTS.md).

## 11. Build results

The 1.0.4 update includes regression coverage for device-name fallback, duplicate-name labels, and renamed UDP discovery with unchanged server identity. The 1.0.3 settings update passed all 67 JVM tests in debug and release (134 executions). See [settings update evidence](docs/SETTINGS_FEEDBACK.md) for validation and device checks, and [metadata migration evidence](docs/METADATA_MIGRATION.md) for the earlier 1.0.2 provider tests. The original emulator baseline remains in [verification evidence](docs/VERIFICATION.md).

## 12. Server APK

[server-app/build/outputs/apk/debug/server-app-debug.apk](server-app/build/outputs/apk/debug/server-app-debug.apk)

## 13. TV APK

[tv-app/build/outputs/apk/debug/tv-app-debug.apk](tv-app/build/outputs/apk/debug/tv-app-debug.apk)

## 14. Known limitations

- **LAN transport:** media and API traffic use authenticated cleartext HTTP because household Android devices do not share a pre-established certificate trust root. Pairing prevents ordinary unauthorized use and credentials are encrypted at rest, but a hostile device controlling or monitoring the LAN could observe traffic or impersonate a host. Use private Wi-Fi or a password-protected hotspot; certificate-pinned transport is future work.
- **Device/OEM behavior:** Android force-stop requires reopening the phone app. Some OEM battery managers may stop foreground services. Guest/client isolation can block local devices. SAF providers may be nonseekable or omit change notifications. Navelo reports these cases and offers a manual rescan, but cannot override the platform.
- **Codec support:** the TV must decode the original file. There is intentionally no phone-side transcoding fallback.
- **Metadata:** live TMDB calls require a credential-bearing phone/server build and phone internet access. Phone-owned manual lookup, saved matching, local artwork delivery and TV display were verified on the Pixel and MiTV. Version 1.0.3 corrects the automatic title/year scoring behind the test item's earlier ambiguity; regression coverage is described in [settings update evidence](docs/SETTINGS_FEEDBACK.md). See [migration evidence](docs/METADATA_MIGRATION.md) for the original live provider checks. Voice search is not part of this release. Browsing, cached metadata, filename-derived titles, and phone-generated thumbnails work without provider access; metadata failures must not mark media playback offline.
- **Scale and hardware coverage:** automated range/seeking tests exercise >40 GiB offsets, but sustained playback of real 20+ GiB media, 1,000 movies/10,000 episodes, physical router-to-hotspot roaming, OEM screen-off behavior, SD-card removal, multicast-blocked networks, and multiple audio/codec combinations remain device acceptance work.
- **Library synchronization:** revision checks avoid unnecessary startup downloads, but a changed generation currently replaces one complete, consistently paged snapshot rather than transferring item-level deltas.

## 15. Highest-priority improvements

1. Add mutually authenticated, certificate-pinned local transport with a migration path for paired devices.
2. Run the full [device acceptance matrix](docs/DEVICE_TESTS.md) across representative Google TV/Android TV hardware, phone OEM power managers, routers, and hotspot implementations; profile the 10,000-episode case.
3. Expand codec diagnostics and device compatibility guidance while preserving original-byte streaming and the no-transcoding architecture.
4. Add item-level revision deltas and bounded persistent metadata indexes for still larger libraries.
5. Expand owner metadata tools and optional voice search while keeping configuration out of everyday playback.

Navelo has no account, ads, analytics SDK, WebDAV dependency, or cloud media service. Watch state remains local to the TV; playback reports only drive the phone's current status and do not sync viewing history.
