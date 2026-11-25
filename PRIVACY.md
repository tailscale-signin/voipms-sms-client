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

## Optional number lookup

Opening the inbox or receiving a message does not send numbers to any lookup provider. Each online lookup explains what it sends before connecting. The service can receive network information such as your IP address. SMS content, your address book, and VoIP.ms credentials are excluded from these requests.

* Local Calling Guide receives the six-digit North American area code and exchange when you choose to look up prefix origin. Offline region labels use numbering data bundled in the app.
* Wikidata receives a query for the number in several common formats and may return a public organization name. The app keeps directory results in memory for the current account session. [Wikimedia privacy policy](https://foundation.wikimedia.org/wiki/Policy:Privacy_policy)
* Choosing Truecaller copies the selected number to the Android clipboard and opens its official search page. You choose whether to paste and submit it in the browser. [Truecaller privacy policy](https://www.truecaller.com/privacy-policy)
* Choosing ShadyCall sends the number to its US reputation service. Its reports are shown on request. [ShadyCall privacy policy](https://shadycall.com/privacy)

* Telnyx receives the full number and the Telnyx API key you enter when you confirm a paid carrier or caller-identity lookup. Canadian data depends on account access. The lookup result shows the provider and time. For caller identity, the app displays the returned name and confidence. It discards any postal address. The key and results stay in memory for the request and current session, respectively.

Spam classification and code detection run on the device. Manual folder choices are stored with your conversations. Tapping **Copy code** places the selected code on the Android clipboard with the sensitive flag.

Notification masking replaces detected verification codes with a generic message. Optional retention removes older, clearly marked verification messages from this device. Provider history and previously exported files have independent retention.

Private threads require a device credential to open. The app hides these threads from regular inbox searches, message notifications, sharing shortcuts and portable backups. The legacy SQLite export requires device authentication when the app has private threads and includes them in the resulting database.

Portable backups encrypt message history and locally stored outgoing attachments with your passphrase. A preview lets you select conversations to merge. Remote attachment references remain links to the original provider. API keys, pending send jobs and private threads stay out of the portable archive.

A selected custom font stays on the device.

For questions about a fork build, use the [project issue tracker](https://github.com/tailscale-signin/voipms-sms-client/issues). Keep account details and message content private.
