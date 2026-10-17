rootProject.name = "multiprocess-datastore"

pluginManagement {
    repositories {
        google {
            content {
              	includeGroupByRegex("com\\.android.*")
              	includeGroupByRegex("com\\.google.*")
              	includeGroupByRegex("androidx.*")
              	includeGroupByRegex("android.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
              	includeGroupByRegex("com\\.android.*")
              	includeGroupByRegex("com\\.google.*")
              	includeGroupByRegex("androidx.*")
              	includeGroupByRegex("android.*")
            }
        }
        mavenCentral()
    }
}
include(":core")
include(":platform:platform-windows")
include(":platform:platform-macos")
include(":platform:platform-linux")
include(":coreTest:filelock")
include(":coreTest:sharedcounter")
include(":coreTest:observer")
include(":coreTest:datastore")
include(":coreTest:datastore:helper")
