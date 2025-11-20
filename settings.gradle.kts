pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "libpebbleroot"

include(":libpebble3")
include(":blobdbgen")
include(":blobannotations")

// Include PebbleKit library until
// it is stable enough to make a release
includeBuild("PebbleKitAndroid2") {
    dependencySubstitution {
        substitute(module("io.rebble.pebblekit2:server"))
            .using(project(":server"))
    }
}
