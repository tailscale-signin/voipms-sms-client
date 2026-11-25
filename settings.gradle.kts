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
            filter { includeModule("com.github.mukeshsolanki", "MarkdownView-Android") }
        }
    }
}

rootProject.name = "VoIP.ms SMS"
include(":voipms-sms")
