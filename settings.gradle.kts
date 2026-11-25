pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                // Official rustls Android release archive, pinned to the immutable 0.2.0 publication.
                maven(url = "https://raw.githubusercontent.com/rustls/rustls-platform-verifier/1aa691352a5e0c215210dfc9a3c2f2078639dc91/android-release-support/maven")
            }
            filter { includeModule("org.rustls", "rustls-platform-verifier") }
        }
        exclusiveContent {
            forRepository { maven(url = "https://jitpack.io") }
            filter {
                includeModule("com.github.mukeshsolanki", "MarkdownView-Android")
                includeModule("cz.adaptech.tesseract4android", "tesseract4android")
            }
        }
    }
}

rootProject.name = "VoIP.ms SMS"
include(":voipms-sms")
