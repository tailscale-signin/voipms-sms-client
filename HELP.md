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

## Unknown numbers

Open a conversation with an unknown sender and choose **Look up number online** from the menu. You can also select a single conversation in the inbox and use its menu. Choose the service for that number:

* **Look up prefix origin** checks the area code and exchange with Local Calling Guide for North American Numbering Plan numbers. It shows the assigned rate centre and region when available. A sender can use that number from anywhere.
* Wikidata searches for organizations that publicly list the number. During a search and for a directory match, the number appears in blue and the app shows its source. Some organizations and most personal numbers have no listing.
* The Truecaller option copies the number and opens the official web search so you can paste it. Truecaller may ask you to sign in on its site.
* ShadyCall checks for US complaint and community reports. Report counts describe what others reported. Anyone can send messages using a spoofed number.

Saved contacts appear by name. For unknown numbers, the app shows the assigned area or mobile numbering country when the bundled offline data identifies one.

## Inbox and verification codes

**Home** opens the call timeline. **Messages** shows your conversations. **Other** collects suspected junk using local message patterns. Long-press a conversation and choose **Move to Messages** or **Move to Other** to remember your choice. Archived conversations are available in the inbox menu.

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

### Clean up database

This option allows you to remove certain data from the database that might be considered outdated or no longer useful, such as:
* metadata associated with deleted messages, which is stored to prevent the app from downloading deleted messages again; and
* messages and metadata associated with a phone number (DID) you no longer use, which is stored in case you start using this phone number again.

### Delete database

This option deletes the entire database.

## Reporting bugs

Use the [fork’s issue tracker](https://github.com/tailscale-signin/voipms-sms-client/issues). For delivery issues, include the app version, build flavor, Android version, Live delivery status, and whether the missing message appears in the VoIP.ms Message Center. Omit numbers, credentials, and message content.

## Calls, voicemail and browser access

**Home** lists missed calls and voicemails by the time VoIP.ms received them. A new event can post an Android notification after a provider check. The first successful check seeds older events quietly. **Messages** contains conversations, and **Other** contains suspected junk. A voicemail received on an identifiable line also appears at its original time in the caller’s conversation. Play, pause, and stop work in either view. The app uses the mailbox timezone when supplied. A mailbox assigned to multiple enabled lines appears in Home as shared. Select **Text back** for a known line and number to open the composer. Background notification timing depends on Android scheduling, the configured sync interval, connectivity, and the provider making its call records available.

For Windows and shared work numbers, run the [browser companion](companion/README.md) on your own computer or server. Open **Settings → Browser companion** on Android to access your HTTPS instance. Configure device access, line permissions, and excluded contacts on that server.
