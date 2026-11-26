# Privacy Policy

This document describes how this [VoIP.ms SMS fork](https://github.com/tailscale-signin/voipms-sms-client) handles your data.

## Data collected to implement core app functionality

The app collects and uses:

* your VoIP.ms credentials;
* the DIDs in your VoIP.ms account;
* SMS/MMS messages sent or received using VoIP.ms; and
* metadata associated with each such SMS/MMS message, including the ID
  assigned to each message by VoIP.ms, the date and time the message was sent,
  and the source and destination phone numbers.

The app stores this data on your device using the standard mechanisms provided
by Android for storing app data. You can delete all data stored by the app on
your device at any time by simply deleting the app.

By using the app, you direct the app to share this data with
[VoIP.ms](https://voip.ms/), which accesses, collects, uses, and shares this
data in accordance with its
own [privacy policy](https://voip.ms/privacy-policy).

The collection, usage, storage, and sharing of this data as described in this
section is essential to implement the app's core functionality.

## Data collected to implement push notifications

Primary builds can include a Firebase project and a matching callback relay selected by the build operator. When configured, the app subscribes to wake-ups for numbers selected for message retrieval. It reads SMS callback settings from VoIP.ms and can configure the relay on a selected number. Removing a number from retrieval triggers topic subscription cleanup.

VoIP.ms sends the destination number to the relay in the callback URL. The relay forwards a wake-up to a Firebase topic derived from the number. The topic tells subscribers that the number had activity. Message text, attachments, sender information, and VoIP.ms API credentials are fetched or used directly by the app with VoIP.ms. They are excluded from the relay request and push payload.

Google processes the topic subscription and associates it with a Firebase installation. Relay infrastructure can process request metadata, including the callback URL. The build operator is responsible for its logging and retention settings. The F-Droid build retrieves messages directly from VoIP.ms using synchronization.

## Live listening

When you start a listening session, the app repeatedly contacts VoIP.ms using your configured account. Android displays the session as a foreground service. You can stop it from the notification or Synchronization settings.

## App contacts, encrypted chat and nearby devices

A name saved by tapping a number stays in the app’s encrypted address book. An Android Keystore key protects the file. Saving a name does not create an Android Contacts entry or send the name to a directory service. The encrypted portable backup uses its own passphrase so you can restore app contacts on another device.

If you sign in to a Matrix homeserver, it handles your account, room membership, encrypted events and connection metadata. Your linked room is a choice you make on this device. Choosing to open a decrypted attachment in an external viewer gives that app access to the selected file.

Nearby discovers devices through Android Wi-Fi Direct and exchanges messages with the peer you confirm. Paired devices use mutual TLS. Queued message bodies and history are encrypted in the app’s private storage. Opening an external carrier draft gives the chosen messaging app the destination and draft text after confirmation.

## Optional number lookup

Opening the inbox or receiving a message does not send numbers to any lookup provider. Each online lookup explains what it sends before connecting. The service can receive network information such as your IP address. SMS content, your address book, and VoIP.ms credentials are excluded from these requests.

The caller card shows which sources you selected and what they receive before you confirm. The Canadian Numbering Administrator receives an area code to download its public assignments. Local Calling Guide receives the area code and exchange. The offline lookup uses bundled numbering data. PhoneBlock receives a four-character SHA-1 prefix and a provider token. The app compares the returned candidates locally and does not submit a report.

Wikidata receives the selected number in several common formats. ShadyCall receives the selected US number. Each other selected account-based provider receives the full number and its own credentials. The app reads the fields displayed on the caller card and discards raw responses. Truecaller opens its official search form and puts the number on the Android clipboard with the sensitive flag for you to paste. [Provider list](HELP.md#connect-additional-providers) · [Wikimedia privacy policy](https://foundation.wikimedia.org/wiki/Policy:Privacy_policy) · [ShadyCall privacy policy](https://shadycall.com/privacy) · [Truecaller privacy policy](https://www.truecaller.com/privacy-policy)

Provider credentials are held in process memory for the current SMS account and can be disconnected. The Telnyx single-source shortcut uses its key for one lookup. The app keeps successful results for up to 15 minutes and empty results for two minutes. Changing SMS accounts or closing the app process clears the caller cards and provider credentials. Lookups do not write names to contacts, messages, backups or diagnostic logs.

The app downloads a shared list of repeatedly reported caller-ID numbers from the FCC public complaint database daily. The query covers the previous 90 days and sends the same parameters for every user. The inbox checks sender numbers against this list on the device. Public complaints are unverified. The inbox ignores results older than seven days. [FCC consumer complaint data](https://opendata.fcc.gov/Consumer/CGB-Consumer-Complaints-Data/3xyp-aqkj)

Spam classification and code detection run on the device. Manual folder choices are stored with your conversations. New verification codes from non-private conversations are copied to the Android clipboard with its sensitive-content flag. You can disable automatic copying in **Settings → Notifications**. The app also provides a manual **Copy code** button.

Notification masking replaces detected verification codes with a generic message. Optional retention removes older, clearly marked verification messages from this device. Provider history and previously exported files have independent retention.

Private threads require a device credential to open. The app hides these threads from regular inbox searches, message notifications, sharing shortcuts and portable backups. The legacy SQLite export requires device authentication when the app has private threads and includes them in the resulting database.

Portable backups encrypt message history and locally stored outgoing attachments with your passphrase. A preview lets you select conversations to merge. Opening a conversation loads inline photo previews from app-private files or the attachment’s HTTPS VoIP.ms URL. Received attachments keep their provider links. In the export dialog you can include already-downloaded files or authorize fetching missing media over HTTPS from VoIP.ms. The downloader sends no account credentials. Restored attachment bytes are stored privately on the device for offline viewing. API keys, pending send jobs and private threads stay out of the portable archive. Encrypted portable backups include your app-saved numbers and names by default, including entries for private conversations. You can deselect the address book before exporting. The address book is sealed with your passphrase before being written to temporary storage. Contact backup and restore access only the app address book.

A selected custom font stays on the device.

The Home call trail retrieves call records and voicemail metadata from the signed-in VoIP.ms account. The phone stores the trail by account. Playing a recording requests the audio from VoIP.ms. A device that receives a notification can display the call or voicemail according to the line, conversation and Android lock-screen settings.

With UnifiedPush enabled, the relay forwards an encrypted wake-up containing the event and callback time to the distributor endpoint you configure. The endpoint and Web Push keys are private subscription information. The browser companion is a separately configured service. Its operator controls which users and devices can see messages, notes and drafts for each line. Native draft handoff transfers the selected draft to the paired companion. Review the [companion setup and access controls](companion/README.md).

For questions about a fork build, use the [project issue tracker](https://github.com/tailscale-signin/voipms-sms-client/issues). Keep account details and message content private.

The SMS junk analyzer runs on the device without sending messages or opening links. It uses your manual folder decisions and stores up to 128 campaign fingerprints per account and receiving line. Fingerprints omit the URL query. The app excludes private conversations and recognized verification-code messages when learning corrections.

The per-thread JSON export includes SMS and MMS contents and attachment references. It is readable by the destination you choose. Copying JSON places it on the Android clipboard as sensitive content. Private threads require device authentication for either action.

Debug mode is off by default. When enabled, it records recent screen transitions, inbox and thread loading, synchronization, sending, attachment sharing and file transfers in app-private storage excluded from backups. Events can contain timestamps, elapsed time, operation identifiers, counts, error types and app code locations. The recorder omits phone numbers, contact names, message text, credentials, URLs, paths and exception messages. Reports also identify the app build and Android API level. The app does not upload debug logs. Choosing **Export debug log** writes a copy to your chosen document provider, which may be cloud-backed. Disabling debug mode erases the local recording. Exported copies remain at the destination.
