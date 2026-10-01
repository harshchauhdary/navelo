# Navelo engineering guide

Navelo is one Android repository with two installable apps. The server is an appliance on an Android phone; the television owns the viewing experience. Android 8/API 26 is the minimum; compile/target SDK is 36. Kotlin, Compose, coroutines and DataStore are used throughout. There is no account, analytics SDK, advertising, cloud media upload, FFmpeg or transcoding.

## Modules and ownership

- `shared`: serializable wire models, API version, 64-bit byte range parser, filename parser, stable IDs, Android Keystore secret storage, Android NSD and a local UDP discovery fallback. No app-specific UI.
- `server-app`: persisted SAF roots, breadth-first library scanning, private document locators, library and metadata snapshots/revisions, TMDB matching and provider-artwork caching, approval pairing, authenticated embedded NanoHTTPD, foreground connected-device service, phone onboarding and settings.
- `tv-app`: cached library and metadata snapshots, identity-based reconnect, local artwork consumption, movie/show/episode navigation, local watch state, search, owner maintenance and Media3 playback. It has no provider credential or direct TMDB client.

Three GPT-5.6 Sol agents were requested with Extra High reasoning: server/backend; television UI; television repository/playback/metadata. The lead defined the contract, built shared discovery/security/parsing, created the phone UI, set up the toolchain, inspected and integrated changes, and owns final verification. Implementation reviews caught and corrected document-ID collisions, one-shot reconnect behavior, oversized progress-cache writes and missing actionable playback controls.

## Storage and streaming

The owner chooses one or more Movies, TV Shows or Other document trees using Android's folder picker. Only persisted URI grants are used: no root access or broad storage permissions. Internal storage and SD cards work through `ContentResolver`. Folder listings request document columns together. IDs hash the source root and provider document identity, never file contents; raw `content://` locators remain private to the phone. Files renamed/moved by providers that change document identity can acquire a new ID.

The server publishes immutable library revisions. A rescan is queued in the background, returns the current snapshot with `202 Accepted`, and leaves current item lookup and playback available while storage is walked. The TV compares revisions, fetches paged JSON only when changed, and atomically replaces its cached listing after a coherent snapshot is received. There is no destructive partial refresh. Current revision pagination is optimized; a changed revision currently refreshes the complete paged listing rather than per-item deltas.

Metadata has its own revision and synchronization loop. `GET /api/v1/metadata` immediately returns the phone's current immutable cache and may schedule bounded matching work in the background. The TV polls that snapshot independently from the library revision and persists the last complete metadata map for offline use. Provider timeouts or metadata polling failures do not alter media connectivity or prevent playback from the cached library.

Authenticated `/media/{id}` streams original bytes from a file descriptor to the socket. Single byte ranges use 64-bit offsets and descriptor/channel seeking. Stream buffers and simultaneous connections are bounded. GET, HEAD, OPTIONS, closed/open-ended/suffix ranges, 206 and 416 are implemented. A provider that cannot seek receives an explicit range error; the phone never simulates a 40 GB seek by discarding preceding bytes. Supported playback codecs are determined by the television's decoders.

## Connection and pairing

See [the exact API contract](CONTRACT.md). Discovery covers both a shared Wi-Fi router and a TV joined to the phone's hotspot. NSD advertises `_navelo._tcp.` with identity and API version. UDP probes to local broadcasts and default gateways supplement mDNS, especially for hotspot firmware that filters multicast. No internet connection or validated-network flag is needed for media playback.

The television selects a discovered phone once. A five-minute request includes a 256-bit client secret. The phone shows the requesting TV and a matching six-digit verification code. Only Allow on the phone grants access. Polling requires the request secret. The returned random bearer credential is encrypted in Android Keystore-backed TV storage; the phone persists only its hash. The owner can revoke trust. Reconnection keys on server UUID, allowing network addresses to change.

Media traffic is HTTP on the private LAN, not TLS. Approval protects ordinary access, but does not protect against a hostile device monitoring or impersonating hosts on that LAN. Use a private Wi-Fi network or password-protected hotspot. A future release should add certificate-pinned transport. Paired-device bearer credentials remain on the LAN and are never forwarded to TMDB or provider artwork hosts; authenticated playback redirects are disabled.

## Metadata and viewing

Filename parsing provides readable movie/show names and season/episode numbers immediately. TMDB is optional and runs only on the phone. The build credential is compiled into the phone/server APK, never the TV APK; it is not committed, but like any bundled app secret it can be extracted from a distributed APK. Exact/high-confidence results are matched by bounded phone background work. Ambiguous matches remain unobtrusive until the owner uses Library → Fix Match, whose search and selection calls go to the paired phone.

Shared `MediaMetadata` wire values contain the display fields formerly owned by the TV. Artwork fields are authenticated, phone-local relative URLs under `/api/v1/artwork/{opaqueId}`, never direct provider URLs. The phone downloads and bounds provider artwork storage; the TV caches metadata and rendered artwork for offline browsing. When provider artwork is absent or unavailable, the existing authenticated `/api/v1/thumbnail/{mediaId}` video-frame path remains the fallback. A metadata failure therefore degrades artwork and descriptions without changing whether a video is online.

The interface uses deep navy, mint focus outlines and warm highlights. Lazy rows keep poster navigation predictable; details prioritize Play or Resume. Shows group by human-readable name and numeric seasons. Large controls and focused borders support couch viewing. Phone setup has three steps, with connection diagnostics under Advanced. The parent does not need addresses, ports or URLs.

Media3 uses the original HTTP media and subtitle endpoints. TV controls support remote transport, seeking, subtitle/audio selection, resume and configurable next-episode countdown. Position/duration are saved locally and reported to the phone separately, so transferred bytes are never presented as playback progress. Watch updates are persisted separately from the large library cache. Artwork caches have explicit memory/disk bounds. Backup policies keep credentials and watch history out of Android cloud backup.

## Android behavior

The server uses a connected-device foreground service with a persistent Open/Stop notification. Closing the activity does not stop the service. Active stream resource locks support screen-off streaming; provider notifications and storage events support media refresh. Some device makers impose extra battery restrictions; Android force-stop requires opening the app again. Android's folder picker cannot grant arbitrary protected storage locations. Wi-Fi guest/client isolation can block all local communication, beyond what an app can recover.

## Sources used for implementation

- [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android foreground service declarations](https://developer.android.com/develop/background-work/services/fgs/declare)
- [Android Gradle Plugin 8.10 release notes](https://developer.android.com/build/releases/agp-8-10-0-release-notes)
- [TMDB application authentication](https://developer.themoviedb.org/docs/authentication-application)
- [TMDB attribution requirements](https://developer.themoviedb.org/docs/faq)

Build/test evidence is in VERIFICATION.md; hardware acceptance steps are in [DEVICE_TESTS.md](DEVICE_TESTS.md). Do not infer hardware certification from a successful APK build.
