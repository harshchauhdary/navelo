# Device acceptance tests

These are hardware acceptance tests, not claims that all devices have been tested. Automated tests and actual executed checks are recorded separately in VERIFICATION.md.

## Two connection modes

1. Join phone and TV to the same private Wi-Fi. Start sharing, open TV app, Connect, verify the six-digit code and Allow on phone.
2. Turn off the router connection. Enable a password-protected hotspot on the phone. Join the TV to that hotspot. Open both apps. They must discover and reconnect without any entered address. Internet access is unnecessary for media already in the library; new metadata requires internet access from the phone only.
3. Disable multicast on the test network. Repeat hotspot discovery using the UDP discovery fallback. Gateway-directed discovery should find a hotspot phone even if broadcasts are filtered.
4. Switch between the router and hotspot while the TV app is open. Library stays visible and playback retry uses the new address.

## Parent flow

Choose a Movies folder and TV Shows folder through Android's folder picker. Add an SD-card folder and one deeply nested directory. Allow TV once. Close phone UI, lock phone. On TV use only D-pad, Select, Back, Play/Pause: choose poster, Play, seek forward 45 minutes, pause, leave and resume. Pick Season 2, select an episode, let next-episode countdown run, cancel it, then use Play Now. Turn subtitles on/off and select another audio language. Verify clearly visible focus on every control, restoration to selected poster, large text and TalkBack phone labels.

## Recovery

Restart router; temporarily disable phone hotspot; remove/reinsert SD card; destroy/reopen phone activity; restart TV app; disconnect phone during playback and reconnect. Previously paired devices reconnect without a setup screen. No error replaces cached Home. Revoking TV trust on phone requires a new approval. Stop action in notification stops sharing.

## Phone-owned metadata

Build both 1.0.2 apps with `NAVELO_TMDB_READ_TOKEN` configured for the phone/server build. Confirm the TV APK contains no credential field, personal-token UI or direct TMDB host request. Repeat with the setting omitted: both APKs must still build and filename metadata plus video-frame thumbnails must remain usable.

On a network where the phone can reach TMDB, keep direct internet access disabled on the TV. Pair once and verify:

1. `GET /api/v1/server` advertises `phone-metadata-v1`.
2. Home appears from the TV cache without waiting for metadata. `GET /api/v1/metadata` returns the current snapshot promptly while unmatched items continue in bounded background work.
3. A later metadata revision reaches the TV without a media-library revision change. Titles/details and authenticated `/api/v1/artwork/{opaqueId}` images update without reconnecting.
4. **Fix Match** searches through the phone, shows results, saves the selected movie/show type and ID, and updates every intended item. Clear metadata removes provider metadata/artwork without changing media, folders, pairing, video-frame thumbnails or watch state.
5. Restart the TV with the phone offline. The last complete metadata and artwork remain visible. When provider art is absent, corrupt or unavailable, the phone-generated video frame is used.
6. Block or interrupt the phone's provider connection during automatic matching, search, detail hydration, artwork download and metadata polling. Work stays bounded, cached snapshots remain readable, video playback stays online, and retry does not block library/media requests.
7. Exercise unknown/expired artwork IDs, unauthenticated requests and traversal-shaped paths. They must not expose provider URLs, arbitrary files or credentials.

Record phone-path request evidence, cache bounds, APK hashes and screenshots in `VERIFICATION.md` only after execution. If the test network's DNS cannot resolve TMDB, identify that separately from credential authentication; do not infer a product failure from the development network.

## Files/performance

Test real seekable 5 GB, 20 GB and >40 GB files on internal storage and SD card. Check first/middle/last bytes, open-ended and suffix ranges, HEAD, 416, multiple range rejection. Verify seek does not linearly read preceding bytes. Monitor `adb shell dumpsys meminfo app.navelo.server` during long playback, concurrent clients, background metadata matching and provider-artwork downloads. Test 1,000 movies / 10,000 episodes, interrupted metadata network, cold cached startup, bounded provider-artwork and video-frame caches, and metadata-revision churn independent of the library revision. Codec compatibility follows the TV's hardware support; server never transcodes.

## Known platform boundaries to check per device

Some OEM power savers stop foreground apps: allow background activity for Navelo and keep phone powered for long sessions. Android force-stop intentionally prevents automatic restart until the app is opened. Guest-network client isolation can prevent any device communication. SAF providers can return nonseekable streams or omit folder notifications; Navelo returns an explicit range error for unsupported seeks and exposes Find new videos as the manual scan fallback.
