pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "RFLab"
include(":app")

/* Fetch pinned GPLv3 OpenVINS sources with tools/bootstrap_embedded_openvins.py
   before Gradle sync. All modules are bundled into RF Lab's ONE APK. */
include(":openvins_embed", ":openvins_opencv", ":openvins_boost")
project(":openvins_embed").projectDir = file("third_party/openvins_mobile/app")
project(":openvins_opencv").projectDir = file("third_party/openvins_mobile/thirdparty/opencv")
project(":openvins_boost").projectDir = file("third_party/openvins_mobile/thirdparty/boost")
