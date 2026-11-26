## Contents

* [Account](#account)
* [Phone numbers](#phone-numbers)
* [Unknown numbers](#unknown-numbers)
* [Private threads](#private-threads)
* [Appearance](#appearance)
* [Notifications](#notifications)
* [Synchronization](#synchronization)
* [Network](#network)
* [Database](#database)
* [Reporting bugs](#reporting-bugs)

## Account

Open [API Configuration](https://voip.ms/m/api.php) in the VoIP.ms portal. Enable API access, create an API password, and allow the public IP addresses your phone will use to reach VoIP.ms. A fixed VPN exit address can be useful when moving between Wi-Fi and mobile data. Sign into the app with your VoIP.ms account email and the API password.

## Phone numbers

The phone numbers (DIDs) you wish to use with the app must have the SMS function enabled. To verify this, go to the VoIP.ms [Manage DID menu](https://voip.ms/m/managedid.php) and open the configuration options for the phone numbers you wish to use with the app. Ensure that SMS is enabled for each phone number.

This app can send and receive messages from multiple phone numbers in your account at a time.

You can hide messages from the conversations view, block the retrieval of messages, and block notifications on a per-phone number basis.

## Save a name for a number

Tap the number in Messages, at the top of a conversation, or in Home’s call history. Enter a name and tap **OK**. To rename it, tap the saved name in the same place. In the new-message picker, tap a number to name it or tap the rest of its row to write a message. Saved app contacts appear there even when Android Contacts permission is off. The encrypted portable backup includes saved app contacts by default.

## Encrypted chat and Nearby

For internet chat, open **Encrypted rooms** and sign in to your Matrix homeserver. Invite a Matrix contact or create a room for a group. To see a direct room alongside a phone conversation, open **Send using** and link the room after checking the Matrix identity with your contact. Each route keeps its own draft. If you switch to SMS, review the sending line before sending.

For a conversation without internet, choose **Nearby** on both phones. Tap **Host** on one and **Find devices** on the other. Select the host and compare the entire pairing code on both screens before tapping **Codes match**. Keep Nearby active while exchanging messages. Messages queued for a paired device can be delivered when the devices reconnect.

## Wi-Fi and carrier RCS

Enable SMS/MMS and API access for your VoIP.ms number to send and receive ordinary texts using an internet-connected Wi-Fi network. The handset can use this route without cellular service. During an internet outage, your SMS outbox keeps unsent messages until connectivity returns. VoIP.ms documents SMS and MMS for its messaging API. [VoIP.ms API overview](https://wiki.voip.ms/article/API_Overview)

For a carrier conversation, choose **Open carrier Messages…** in the conversation’s delivery options. Confirm the recipient and the external app. In that app, check the sending line and whether the composer shows RCS or SMS before you press Send. The carrier app manages its own history, delivery and read receipts. Attach any photos again there. The original draft remains available in VoIP.ms SMS.

Carrier RCS requires a line activated with a compatible RCS provider and a compatible recipient. An SMS-enabled VoIP.ms number would need separate RCS provisioning from an operator that supports it. Android reserves its native single-registration messaging APIs for a preinstalled privileged app selected as the default messenger. [Android IMS requirements](https://source.android.com/docs/core/connect/ims-single-registration) · [Google Messages RCS setup](https://support.google.com/messages/answer/7189714)

## Unknown numbers

Open an unknown sender’s conversation, or select one conversation in Messages, then choose **Look up number online → Check multiple sources**. Pick the sources and confirm the disclosure. The caller card fills in as each source answers. Close it to stop outstanding requests. **View recent caller card** reopens the results without another request.

A saved contact takes priority. The card attributes each listed name, numbering area, carrier, line type and risk signal to its source. Conflicting names stay on the card with a notice in the inbox. Carrier assignments can change when a number is ported. A listed owner or numbering area cannot establish who sent a message or where that person is now. Spam reports do not change your folder selection.

### Included directories

| Source | What the app can look up | What leaves the device |
| --- | --- | --- |
| libphonenumber | Assigned area or mobile numbering country | Nothing |
| [Canadian Numbering Administrator](https://www.cnac.ca/co_codes/co_code_status.htm) | Canadian exchange area and original range holder | Three-digit area code |
| [Local Calling Guide](https://localcallingguide.com/xmlquery.php) | North American rate centre | Area code and exchange |
| [Wikidata](https://www.wikidata.org/wiki/Wikidata:SPARQL_query_service) | Organizations with a public listing for the exact number | Number in common formats |
| [ShadyCall](https://shadycall.com/) | US complaints and community reports | Full US number |

### Connect additional providers

Choose **Manage lookup accounts**, enter your own provider credentials and select **Use for this session**. Connected services appear in the source picker for supported numbers. Select each paid service explicitly. Account access, regional entitlements, coverage and charges depend on the provider. Credentials can be disconnected from the same menu. The single-source Telnyx shortcut also accepts a key for one lookup.

| Provider | Results used by the app | Session credential |
| --- | --- | --- |
| [Telnyx](https://telnyx.com/release-notes/number-lookup-caller-identity-enhanced-caller-name) | Carrier or a separate US/Canada identity request with confidence. Canadian identity needs account approval. | API key |
| [Twilio](https://www.twilio.com/docs/lookup/v2-api/caller-name) | US caller name | API key SID and secret |
| [Hiya](https://developer.hiya.com/docs/ai-voice-platform/endpoints/v-1-phone-number-profile) | Caller profile name, carrier, connection type and area | Authorized account user ID and access token |
| [SignalWire](https://signalwire.com/docs/apis/rest/phone-number-lookup/lookup-phone-number) | Caller name and carrier packages | Space name, project ID and Numbers-scoped token |
| [Trestle](https://docs.trestleiq.com/api-reference/smart-cnam-api) | Smart CNAM listed name | API key |
| [TrueCNAM](https://www2.truecnam.com/developers_api) | North American caller name | API key and API password |
| [IPQualityScore](https://www.ipqualityscore.com/documentation/phone-number-validation-api/overview) | Listed name, carrier, area and provider fraud score | API key |
| [Call Control](https://www.callcontrol.com/swagger/) | Reported call type, name from complaints and provider confidence on a 0–10 scale | Identify API key |
| [PhoneBlock](https://github.com/haumacher/phoneblock/blob/master/phoneblock/src/main/webapp/api/phoneblock.json) | Community votes for an exact match. The service receives four hash characters. The app checks the returned bucket locally. | Personal API bearer token |
| [Plivo](https://www.plivo.com/docs/lookup/overview) | Carrier, country and line type | Auth ID and token |
| [Sinch](https://developers.sinch.com/docs/number-lookup-api-v2/api-reference/number-lookup-v2/numberlookupv2) | Line type and carrier | Project ID and active OAuth access token |
| [Vonage](https://developer.vonage.com/en/identity-insights/guides/original-carrier) | Original carrier for a fraud-prevention check, using the EU endpoint | Active application JWT for Identity Insights |
| [Veriphone](https://veriphone.io/docs/v3) | Standard number region, original carrier and line type | API key |
| [numlookupapi](https://numlookupapi.com/docs/validate/) | Numbering region, original carrier and line type | API key |
| [Numverify](https://docs.apilayer.com/apilayer/docs/numverify) | Numbering region, original carrier and line type | Numverify access key |
| [Neutrino API](https://www.neutrinoapi.com/api/phone-validate/) | Country or region, line type and available prefix carrier | User ID and API key |
| [Abstract API](https://www.abstractapi.com/api/phone-validation-api) | Numbering region, carrier, registered name and risk when the plan supplies them | Phone Intelligence API key |
| [APIVerve](https://docs.apiverve.com/ref/phonenumbervalidator) | Numbering country, line type and available risk score | API key |

**Truecaller website** copies the selected number and opens the official search form. You decide whether to paste and submit it. Truecaller offers Number Intelligence for businesses and its public mobile SDK verifies the app user’s own number. The website result is viewed in the browser. [Truecaller developer documentation](https://docs.truecaller.com/truecaller-sdk)

Two other services reviewed have access or usage constraints. tellows documents a licensed live-number partner feed. Bandwidth’s number lookup restricts certain carrier data to routing, rating, billing and network maintenance. Neither is queried by the caller card. [tellows partner API](https://www.tellows.tw/openapi/index.html) · [Bandwidth lookup guide](https://dev.bandwidth.com/docs/numbers/guides/lookupNumbers/)

## Inbox and verification codes

**Home** opens the call timeline. **Messages** shows your conversations. **Other** collects suspected junk using local message patterns. Long-press a conversation and choose **Move to Messages** or **Move to Other** to remember your choice. In Other, the return icon also restores one thread with a single tap. Archived conversations are available in the inbox menu.

MMS photos appear inside the conversation. Tap any photo to open it full screen. Tap the photo again to hide or show the controls. Use Previous and Next to browse the attachments in that message.

A stronger background and an unread dot mark conversations with unread messages, including conversations with an unsent draft. Red marks suspected junk. Amber marks message warnings and numbers with caller reports. The sender label identifies the evidence. Saved contacts and established conversations have a theme-coloured initial or number badge. The app checks a daily public FCC report list on the device and can also display reports from a caller lookup you requested.

**Settings → Notifications → Automatically copy verification codes** is on by default. A newly received code replaces the clipboard contents. The app copies only the code, preserves leading zeroes and skips private threads and older history. You can still tap **Copy code** in the conversation.

The app checks incoming messages locally for English, French and Portuguese requests, including attempts to obtain a sign-in code, misleading links and urgent payment demands. A warning appears on the individual message even for a saved sender. Ordinary verification texts keep the copy button. Established conversations stay in Messages unless you choose Other. Suspicious previews in Android notifications show a review prompt, and automatically filed junk stays quiet.

Folder choices apply to the sending number on the selected receiving line. Correcting a message with a recurring link campaign also helps recognize similar messages on that line. The app remembers a bounded set of fingerprints. Private threads and verification codes do not train those campaign rules.

To hide one conversation from Android sharing suggestions, open it and turn off **Suggest in Direct Share** in its menu. Other conversations with the same contact on a different line keep their own settings.

Open a message with a verification code and tap **Copy code** to copy the code, including leading zeros. Android marks the clipboard entry as sensitive.

## Private threads

Open a conversation and choose **Make thread private**. On the **Messages** tab, scroll to the very top and pull down past the usual refresh distance until the phone vibrates. Release, then unlock with your device credential. Repeat the long pull or choose **Return to messages** to leave. A shorter pull still refreshes messages. The overflow menu also offers **Private threads**. Switching tabs or leaving the app closes the private inbox.

## Appearance

The app includes Proxima Nova from GPT Mobile Improved and uses it by default. Open **Settings → Appearance** to change the theme. To use your own font, open **Chat font → Choose font file** and select a regular TTF or OTF file up to 8 MB. Android derives the other weights for custom fonts. To switch back to the bundled font, choose **Restore Proxima Nova**.

## Notifications

### Live delivery

**Settings → Synchronization → Start live listening** starts a session using your existing VoIP.ms account in either full build. Select numbers with message retrieval enabled. The app checks about every 15 seconds after the previous check finishes and backs off after errors. The settings screen shows the last successful check or retry. An ongoing notification lets you stop listening with the inbox closed.

Direct listening uses more battery. Android can postpone network access or end a session. On Android 15 and later, foreground data synchronization shares a six-hour background budget across the app. Force-stopping the app, restarting the phone or losing the app process ends a session. Reopen the screen to start another. Scheduled checks use the interval you select separately.

In a push-enabled primary build, VoIP.ms calls the operator’s HTTPS relay when a selected number receives a message. The relay sends a wake-up through Firebase. The app then downloads the message directly from VoIP.ms. The callback contains only the destination number.

Open **Settings → Synchronization → Push registration** to check each number. Tap the status to retry setup. Enable message retrieval for the number and allow Android notifications if you want alerts. If another service already uses the number’s callback, the app leaves it in place. You can keep that integration and use scheduled synchronization, or change the callback yourself in the [VoIP.ms portal](https://voip.ms/m/managedid.php). Changing it there affects every app using that number.

Direct listening and scheduled recovery also work on devices without Google Play services. If you force-stop the app in Android settings, reopen it to resume delivery. To view media or compare a message with the provider’s records, open **VoIP.ms Message Center** from the conversation list menu.

### Check delivery on your phone

Send an SMS from a different phone to a selected number with the app in the background and the screen locked. Open the alert and check that the conversation contains one copy. For a recovery check, take the phone offline, send another SMS, reconnect, and check that it arrives. The Push registration status confirms callback setup. Receiving the test message confirms the entire route.

## Outbox and scheduled sending

The inbox menu shows pending sends across your active account. A conversation’s **Outbox** shows its own sends. Hold **Send** or choose **Schedule message** to pick a local date and time. The app keeps that instant if you travel. After a restart or an offline period, Android sends due messages once it can run a connected worker. Tap a queued entry to change its text and time or cancel it. **Undo** cancels an immediate send during its eight-second hold.

**Send details** shows the sending line, recipient, actual transport, SMS parts or MMS attachment bytes. For long texts, you can choose a single text-only MMS or separate SMS parts. If you have confirmed your account rate, enter the USD price per part for this route and transport. An estimate excludes fees and taxes. **Accepted by VoIP.ms** identifies a provider acknowledgement. If an acknowledgement is lost, open **Outbox → Check provider history**. Review possible matches, or explicitly authorize a second attempt after checking. Restoring a database preserves messages and discards its pending send authorizations.

## Synchronization

The inbox and open conversations check for new messages every 30 seconds while visible. A push can start a check sooner. Swipe down on the conversation list to fetch history from your configured start date. Each number uses its own history when checking for new messages.

### Synchronization interval

Background recovery defaults to 15 minutes. You can change it or select **Never** in **Settings → Synchronization**. Existing interval selections are respected. WorkManager schedules checks when the phone has a network connection. Doze, battery restrictions, and connectivity can delay them. Push delivery on a configured primary build operates independently of this interval.

### Synchronization start date

When the app synchronizes with the VoIP.ms servers, it only retrieves messages on or after the synchronization start date.

By default, this date is set to the date you installed the app. It is recommended that you set this to the date on which you created your VoIP.ms account and then perform a synchronization, so that all of your messages will be downloaded.

### Retrieve only recent messages

Normally, synchronization retrieves all messages dated on or after the synchronization start date. You can configure synchronization to retrieve only those messages from the VoIP.ms servers that are dated after the date of the most recent message that is stored locally by enabling this option.

This option significantly reduces the amount of data that synchronization uses.

### Retrieve deleted messages

Normally, synchronization does not retrieve messages that have been deleted locally. You can choose to restore these messages during synchronization by enabling this option.

## Network

You can configure certain network settings such as the timeout for establishing connections and for reading data from established connections.

## Database

### Import database

This option imports the database from a specified file. The database must be in the standard SQLite binary database format and must have the database name and tables required by the app.

### Export database

This option exports the database to a specified file. This allows you to make backups of the database in case you delete messages from the VoIP.ms servers or simply want to avoid re-downloading them all from the servers after resetting your phone.

### Encrypted portable backup

Choose **Encrypted portable backup** and enter the same passphrase twice. Keep the passphrase to unlock the file on another phone. **Restore portable backup** checks the whole file and shows message counts before you choose which threads to merge. Private threads and unsent jobs stay on this device. Local outgoing attachments travel inside the file. For received MMS, keep links only, include downloaded files, or explicitly fetch missing attachments from VoIP.ms. The result reports unavailable files. Restoring a selected conversation makes its included attachments playable offline, even if the conversation has already synced.

Encrypted portable backups include your app address book by default, including names you saved for private conversations. Clear **Include all saved app contacts** to export messages without the address book. Android device contacts are separate. During restore, choose **App contacts only** to recover selected names without importing messages, or choose both, then review each list. When a number already has an app contact, its existing name is preserved. Older backups remain readable.

### Save one conversation

Open the conversation, tap **More options → Export → Save JSON to file**, and choose a destination. The file includes that thread’s SMS and MMS records in chronological order, even when a search or an older page is on screen. It includes attachment references. For a short thread, **Copy JSON** puts the same records on the clipboard. Private threads require screen-lock confirmation. Both options produce readable text.

### Diagnose a Messages crash or file export

1. Open **Settings → Database → Debug mode** and turn it on.
2. Reproduce the problem. For a loading issue, open **Messages** and the affected thread. For a file issue, repeat the export and select the same destination. If the app closes, reopen it.
3. Return to **Settings → Database → Export debug log** and choose a destination. The resulting `.jsonl` file opens in a text editor. Each line after the header is an event. Follow an `operation` ID to see its steps, timings, counts and any error location.

The recorder keeps up to 512 KiB of recent history on this device. A fatal app exception can leave a crash entry. Android force-stops and native process failures may end the app before it can write one. A saved report covers events through the moment it was written. Later verification and completion appear in the next report.

**Clear debug log** starts a fresh recording. Turning **Debug mode** off erases the local history. You control any copies saved through the file picker.

### Clean up database

This option allows you to remove certain data from the database that might be considered outdated or no longer useful, such as:
* metadata associated with deleted messages, which is stored to prevent the app from downloading deleted messages again; and
* messages and metadata associated with a phone number (DID) you no longer use, which is stored in case you start using this phone number again.

### Delete database

This option deletes the entire database.

## Reporting bugs

Use the [fork’s issue tracker](https://github.com/tailscale-signin/voipms-sms-client/issues). For delivery issues, include the app version, build flavor, Android version, Live delivery status, and whether the missing message appears in the VoIP.ms Message Center. Omit numbers, credentials, and message content.

## Calls, voicemail and browser access

**Home** lists missed calls and voicemails by the time VoIP.ms received them. A new event can post an Android notification after a provider check. The first successful check seeds older events quietly. **Messages** contains conversations, and **Other** contains suspected junk. A voicemail received on an identifiable line also appears at its original time in the caller’s conversation. Select **Voicemails only** on Home to filter the timeline. Play, pause, and stop work in either view. Playback finds the recording’s current mailbox folder and slot, including recordings moved to Old. The timeline retains entries after the provider removes their audio. The app uses the mailbox timezone when supplied. A mailbox assigned to multiple enabled lines appears in Home as shared. Select **Text back** for a known line and number to open the composer. Background notification timing depends on Android scheduling, the configured sync interval, connectivity, and the provider making its call records available.

For Windows and shared work numbers, run the [browser companion](companion/README.md) on your own computer or server. Open **Settings → Browser companion** on Android to access your HTTPS instance. Configure device access, line permissions, and excluded contacts on that server.
