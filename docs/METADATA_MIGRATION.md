# Navelo 1.0.2: phone-owned metadata

The phone now owns TMDB matching, saved corrections, episode details and provider-artwork downloads. The TV requests metadata and artwork from the paired phone, and keeps its own offline cache. The TV Settings token field and personal-token override are removed. The app credential is compiled into the phone APK only; it is not a secret once that APK is distributed.

Metadata uses a separate persisted revision from the media library. Background matching does not hold library or playback requests open. Provider concurrency, retries and response sizes are bounded; ambiguous titles receive a retry delay. Provider artwork has a 128 MiB disk budget and an 8 MiB per-image limit. Local video-frame thumbnails remain the fallback. Shared show corrections preserve each episode's own details as hydration completes.

The TV accepts only local artwork references, including when reading old saved metadata, and never sends the phone's pairing token to an external image host. Metadata polling errors do not mark video playback offline. Stale responses from another phone or an older revision cannot replace the current metadata snapshot.

## Automated verification — September 29, 2026

```sh
. scripts/env.sh
./gradlew test :server-app:assembleDebug :tv-app:assembleDebug :server-app:lintDebug :tv-app:lintDebug
```

The final command passed in 19 seconds. Both APKs assemble and both lint tasks pass. There are 62 unique JVM tests, run in debug and release for 124 executions: shared 10, server 30, TV 22 per variant, with zero failures, errors or skips. The log is `.tools/phone-metadata-final.log`.

Regression coverage includes authenticated metadata/artwork endpoints, persistence across owner recreation, matching across episodes, independent movie/show IDs, immediate snapshots during stalled provider work, stale background results after manual corrections or clearing, concurrent artwork handling, local artwork routing and TV revision guards. A large-library regression retains 1,025 active artwork references while registering new search and matched images.

Inspection of the assembled TV DEX files found neither the saved TMDB token nor the direct TMDB API/image host strings. The server build contains the app credential as intended.

Both apps are version code 3, version name 1.0.2. Install updates in place to retain pairing, folder permissions and watch state.

| APK | Bytes | SHA-256 |
| --- | ---: | --- |
| [Phone](../server-app/build/outputs/apk/debug/server-app-debug.apk) | 18,061,860 | `99ec6e1f285de68754e6de88c47d051df3183bed8b3816ce12cf8bfa21f98689` |
| [TV](../tv-app/build/outputs/apk/debug/tv-app-debug.apk) | 22,495,631 | `2b946d65024010038a2f3a28380bc0a387b5d80e95a9a742b0a59996bb434b8a` |

## Device verification

Both final 1.0.2 APKs were installed in place on the Pixel 9 Pro and Xiaomi MiTV after the owner authorized TV testing. Existing folder grants, pairing and viewing progress remained available. Phone sharing was restarted after its update, and the TV reconnected to Media Phone.

The phone advertised `phone-metadata-v1`. Automatic matching had not saved a result for the existing *500 Miles* file during the observation window. A manual Fix Match search returned multiple candidates with posters through the phone. Selecting *500 Miles (2026)* saved metadata revision 1 on the phone; its poster reference was a local `/api/v1/artwork/…` path. The phone registry contained 11 artwork sources and its artwork directory contained five image files. The TV then displayed the official poster and backdrop in its home rows. This verifies live phone-to-TMDB lookup, persisted phone ownership, authenticated local artwork delivery and TV display. It does not establish why the earlier automatic pass had not matched that item.

The updated TV Settings source and assembled APK contain no token controls or credential. Live Settings inspection confirmed the updated owner tools. Local evidence remains in `.tools/metadata-tv-current.png`; the manual result screenshot is `/private/tmp/navelo-tv-results.png`. No provider-disconnected cold-start test was repeated for this migration; the prior offline-thumbnail evidence and the automated cache/persistence tests cover different parts of that behavior.

Before this migration, a direct terminal request to TMDB returned HTTP 200 and accepted the app token after the computer's DNS configuration changed. The owner confirmed that DNS-only 1.1.1.1 worked without WARP. This validates that token, but does not replace verification of the new phone-to-TMDB and TV-to-phone paths.
