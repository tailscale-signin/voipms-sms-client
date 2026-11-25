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
