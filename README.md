# VoIP.ms SMS for Android

[![Android CI (primary)](https://github.com/tailscale-signin/voipms-sms-client/actions/workflows/android-primary.yml/badge.svg)](https://github.com/tailscale-signin/voipms-sms-client/actions/workflows/android-primary.yml) [![Android CI (F-Droid)](https://github.com/tailscale-signin/voipms-sms-client/actions/workflows/android-fdroid.yml/badge.svg)](https://github.com/tailscale-signin/voipms-sms-client/actions/workflows/android-fdroid.yml)

Send and receive SMS from your VoIP.ms numbers, search conversations, and reply from notifications. The default dark theme pairs GPT Mobile Improved’s cyan accents and deep teal surfaces with its Proxima Nova typeface. Light and system themes are available under Appearance.

## Home, Messages and Other

**Home** shows missed calls and voicemail for your selected VoIP.ms numbers, newest first. Each recording has Play, Pause and Stop controls. Choose **Text back** to open the caller’s conversation. A voicemail with an identifiable receiving line also appears alongside texts from that caller. Recordings from a mailbox shared by several selected lines appear on Home.

Call alerts arrive when the app retrieves the provider’s records. Enable notifications for the receiving number and use **Refresh** on Home to check immediately. The first check adds existing history quietly. Call and voicemail retrieval require permission to read call records, line routing and voicemail using your VoIP.ms API account.

**Messages** contains your regular conversations. In a conversation, choose **Make thread private** to protect it with your device screen lock. To open private threads, scroll to the top of Messages, then pull down past the usual refresh distance and unlock. Repeat that gesture or press Back to return. **Other** holds conversations filtered as junk and threads you move there yourself.

## Messaging over Wi-Fi

| What you want to send | What the recipient needs |
| --- | --- |
| SMS from your VoIP.ms number over an internet-connected Wi-Fi network | An ordinary SMS number. Your sending line needs VoIP.ms SMS service. |
| An encrypted direct message, file or group message | A Matrix account and an invitation to the encrypted room. In a phone conversation, use **Send using** to link the room after confirming the Matrix identity. |
| A message between nearby phones without internet | Nearby open on both devices, Wi-Fi Direct support, and matching pairing codes confirmed on both screens. |
| An RCS message from your mobile line | Open the draft in your carrier messaging app. That app determines RCS availability for the line and recipient. |

Tap a number in the inbox, a conversation header or Home to save a contact name. The app keeps its encrypted address book on this device. Saved names appear in its new-message picker and can travel with your passphrase-protected portable backup. **Other** explains junk classifications and lets you move a conversation back to Messages.

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

Proxima Nova is bundled and active on first launch. To use a different font, open **Settings → Appearance → Chat font** and choose a local TTF or OTF file. You can restore Proxima Nova from the same menu.

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
