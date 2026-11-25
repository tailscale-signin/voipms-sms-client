# VoIP.ms SMS for Android

[![Android CI (primary)](https://github.com/tailscale-signin/voipms-sms-client/actions/workflows/android-primary.yml/badge.svg)](https://github.com/tailscale-signin/voipms-sms-client/actions/workflows/android-primary.yml) [![Android CI (F-Droid)](https://github.com/tailscale-signin/voipms-sms-client/actions/workflows/android-fdroid.yml/badge.svg)](https://github.com/tailscale-signin/voipms-sms-client/actions/workflows/android-fdroid.yml)

Send and receive SMS from your VoIP.ms numbers, search conversations, and reply from notifications. The default dark theme uses the cyan accents and deep teal surfaces from GPT Mobile. Light and system themes are available under Appearance.

## Receiving messages

| While using the app | When the app is closed |
| --- | --- |
| The inbox and open conversations check every 30 seconds. | Start live listening for direct VoIP.ms checks about every 15 seconds. A configured push build also supports callback wake-ups. Scheduled catch-up defaults to 15 minutes and follows Android scheduling. |

1. Enable API access for your VoIP.ms account and SMS on the numbers you want to use.
2. Sign in with the account email and **API password**, select the numbers to retrieve, and allow Android notifications to see incoming alerts.
3. Open **Settings → Synchronization → Start live listening** for direct checks while the inbox is closed. Stop the session from its notification or this screen.

Both full builds support direct listening with your VoIP.ms account. Android may pause network access or stop the session. Android 15 and later limit background data-sync foreground services to a shared six-hour budget. Scheduled checks continue according to your selected interval. When Firebase and a matching relay are configured, **Push registration** shows the status per number. See the [setup and troubleshooting guide](HELP.md) and [push relay deployment instructions](relay/README.md).

## Unknown numbers and appearance

Open an unknown sender’s conversation and choose **Look up number online**. Wikidata searches public organization listings. ShadyCall provides US complaint and community reports. Truecaller opens an optional browser search. You choose which service receives the number. A blue number and a source label distinguish online information from a saved contact.

**Settings → Appearance → Chat font** lets you select a local TTF or OTF, including a licensed copy of GPT Mobile Improved’s Proxima Nova. The bundled family is DM Sans.

## Build and install

Use JDK 21 and the Android SDK version declared in `voipms-sms/build.gradle.kts`. Both commands produce a debug-signed APK.

```sh
./gradlew assembleFdroidFullDebug
./gradlew assemblePrimaryFullDebug
```

You can also download a debug APK from the artifacts in a successful [Android workflow run](https://github.com/tailscale-signin/voipms-sms-client/actions). Android requires the same signing key for in-place updates. Export your message database in Settings before switching between differently signed installations.

This project builds on [Michael Kourlas’s VoIP.ms SMS client](https://github.com/michaelkourlas/voipms-sms-client).

## License ##

VoIP.ms SMS is licensed under the [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0).

Google Play and the Google Play logo are trademarks of Google LLC.
