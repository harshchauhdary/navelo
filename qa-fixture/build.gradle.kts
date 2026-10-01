plugins { id("com.android.application") }
android {
    namespace = "app.navelo.fixture"
    compileSdk = 36
    defaultConfig { applicationId = "app.navelo.fixture"; minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "test-only" }
    sourceSets["main"].assets.srcDir(rootProject.file(".tools/qa-media"))
}
