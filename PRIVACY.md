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

For questions about a fork build, use the [project issue tracker](https://github.com/tailscale-signin/voipms-sms-client/issues). Keep account details and message content private.
