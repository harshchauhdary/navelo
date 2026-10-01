# Test fixture only

This optional APK fills the document-picker gap in the Android ATD emulator. Normal Gradle builds do not include it. It exposes an Android DocumentsProvider with real persistable URI grants and seekable descriptors, and a clearly labeled picker. It is not part of Navelo and must not be distributed as the product.

Place a legally usable test MP4 at `.tools/qa-media/sample.mp4` (the local Android SDK emulator's `resources/default.mp4` was used for the recorded smoke run). Run `./gradlew -PincludeQa=true :qa-fixture:assembleDebug`, install in a disposable emulator, then use the normal Navelo Choose Folder action. No media asset is checked in.
