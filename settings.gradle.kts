pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "Navelo"
include(":shared", ":server-app", ":tv-app")
// Isolated storage provider for emulator acceptance testing; never included in normal builds.
if (providers.gradleProperty("includeQa").orNull == "true") include(":qa-fixture")
