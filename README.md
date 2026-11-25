# VoIP.ms SMS for Android

[![Android CI (primary)](https://github.com/tailscale-signin/voipms-sms-client/actions/workflows/android-primary.yml/badge.svg)](https://github.com/tailscale-signin/voipms-sms-client/actions/workflows/android-primary.yml) [![Android CI (F-Droid)](https://github.com/tailscale-signin/voipms-sms-client/actions/workflows/android-fdroid.yml/badge.svg)](https://github.com/tailscale-signin/voipms-sms-client/actions/workflows/android-fdroid.yml)

Send and receive SMS from your VoIP.ms numbers, search conversations, and reply from notifications. The default dark theme uses the cyan accents and deep teal surfaces from GPT Mobile. Light and system themes are available under Appearance.

## Receiving messages

| While using the app | When the app is closed |
| --- | --- |
| The conversation list and open threads check for messages every 30 seconds. A configured push can trigger an earlier check. | A push-enabled primary build wakes on a VoIP.ms callback. Both builds also schedule background catch-up, every 15 minutes by default. Android controls when scheduled work runs. |

1. Enable API access for your VoIP.ms account and SMS on the numbers you want to use.
2. Sign in with the account email and **API password**, select the numbers to retrieve, and allow Android notifications to see incoming alerts.
3. Open **Settings → Synchronization → Live delivery** to see how this installation receives messages and check registration for each number.

The F-Droid flavor uses scheduled synchronization. Primary builds use the same fallback until the operator supplies a Firebase project and a matching relay. See the [setup and troubleshooting guide](HELP.md) and [push relay deployment instructions](relay/README.md).

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
