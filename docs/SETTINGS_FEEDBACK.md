# Navelo 1.0.3: settings feedback and automatic matching

The TV Settings screen groups playback preferences, library maintenance, phone connection and provider attribution. The phone owns provider access, title matching and artwork retrieval. Fix Match on the TV sends search and selection requests to the paired phone; the TV contains no TMDB credential field or provider search client. Obsolete TV-side automatic scoring code has been removed.

Refresh library, Rescan media and Clear movie and show matches show pending, success or failure messages beside the action. Duplicate network actions are disabled while a request is pending. Playback toggles show their current On/Off state immediately. Clearing matches describes its library-wide effect on the phone and connected TVs, without claiming to erase the TV's image cache.

Refresh reloads the current phone library. Rescan asks the phone to walk its selected media folders, then waits for scan completion before refreshing. A reserved scan generation distinguishes completion from a changed library revision, so a scan with no additions or removals still reports completion. Older phone builds receive request-only wording. Failures, phone restarts and timeouts receive actionable feedback.

Filename parsing already precedes automatic provider search. For example, `500 Miles 2026 1080p WEB-DL HEVC x265 5.1 BONE.mkv` becomes title `500 Miles` and year `2026`. The earlier scoring margin rejected that result when TMDB also returned an identically titled 2025 movie. An exact title/year now scores sufficiently above an adjacent or missing year to choose the unique exact result. Identical title/year candidates remain ambiguous and require Fix Match; distant-year matches remain rejected.

Both apps use version code 4, version name 1.0.3. Update in place to preserve pairing, media folder permissions and watch state. Historical 1.0.2 migration evidence remains in [METADATA_MIGRATION.md](METADATA_MIGRATION.md).

## Validation

`./gradlew test :server-app:assembleDebug :tv-app:assembleDebug :server-app:lintDebug :tv-app:lintDebug` passed in 21 seconds. There are 67 unique JVM tests, run in debug and release for 134 executions: shared 10, server 39, TV 18 per variant, with zero failures, errors or skips. Unicode matching regressions now live with the phone matcher. Both APKs assemble and both lint tasks pass.

| Debug APK | Bytes | SHA-256 |
| --- | ---: | --- |
| `server-app/build/outputs/apk/debug/server-app-debug.apk` | 17,845,720 | `e0a7fbf25d3dae98002244a6fb27d4a8bb148553a58c4559eaad64c7f771a411` |
| `tv-app/build/outputs/apk/debug/tv-app-debug.apk` | 22,796,933 | `e187c9dd19b023367030001794293de455026cd52d4dae6057b1f49a42d68597` |

Both APKs were installed in place on the Pixel 9 Pro and Xiaomi MiTV. Phone sharing resumed with one video, and the TV retained pairing and the saved 500 Miles artwork. The automatic-match regression uses the parsed release filename and competing provider candidates; the existing live manual match was preserved.

Live TV rescan displayed `Scanning media on your phone…` and then `Scan complete · 1 video`, even though no media changed. The pending state disabled competing library actions; the completed message remained below the Rescan option. Evidence: `.tools/settings-rescan-pending.png` and `.tools/settings-rescan-result.png`. The Settings screen also shows the phone's role in providing metadata and has no provider credential field.

Live TV refresh was also verified using D-pad navigation: Refresh was enabled and focusable, showed `Refreshing from your phone…`, then `Library is up to date · 1 video`. Evidence: `.tools/settings-root-refresh-focus.png`, `.tools/settings-refresh-pending.png`, and `.tools/settings-refresh-result.png`. An earlier intermediate screenshot had shown a dimmed Refresh row; the bounded follow-up confirmed it was usable and completed successfully. Saved matches, playback preferences and the paired phone were not cleared or changed. Error UI and Clear matches were not exercised on the physical TV during this update.
