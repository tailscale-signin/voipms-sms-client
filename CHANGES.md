### 0.8.0 ###

* Tap a phone number to save a name in the encrypted app address book. Reuse that name in conversations, call history and the new-message picker. Encrypted portable backups can restore individual contacts.
* Send VoIP.ms SMS over Wi-Fi and queue messages through a loss of connectivity. Link a Matrix account for encrypted direct or group chat, attachments, typing indicators and receipts. Use Nearby to exchange messages over Wi-Fi Direct after confirming both devices.
* Open a draft in the phone’s carrier messaging app when you want to use your mobile line. That app handles RCS availability.
* Review suspicious messages using on-device English, French and Portuguese detection. Open Other to see why a conversation was flagged and correct its placement.
* Check an unknown sender with multiple caller ID sources in one card. See attributed names, conflicting listings, Canadian exchange assignments, carriers and reputation reports. Connect additional lookup accounts for the session.

* Home shows missed calls and voicemail by receipt time. Listen from Home or the matching conversation with play, pause and stop.
* Messages and Other use refreshed layouts, buttons and icons. At the top of Messages, a deliberate long pull opens the device-protected private inbox. Repeat to return.
* Queue offline messages, schedule a send, undo it during the delay, and review an uncertain provider response. Send photos and voice notes in the conversation.
* Give each number its own profile and templates. Find a specific message, snooze a thread, translate a draft, and search text in an attachment.
* Inspect delivery for each phone number, choose UnifiedPush, or connect a self-hosted browser inbox. Selected devices can hand off a draft and share a work number with individual permissions.

### 0.7.2 ###

* Sort conversations into Main and Other, with local junk detection and remembered manual moves.
* Copy a verification code from a message with a themed button.
* Show offline numbering regions and look up North American rate centres using only a prefix.
* Speed up inbox loads and searches with indexed queries and selective row updates. Support Android 16 back navigation and display insets.

### 0.7.1 ###

* Start live listening from Synchronization settings. The phone checks VoIP.ms directly and shows an ongoing notification with a Stop action. Scheduled recovery remains available when the session ends.
* Look up an unknown sender from the inbox or conversation. Online directory matches display the number in blue and name the source. Saved contacts retain priority.
* Use the official Truecaller web search for a manual lookup or check US spam reports on demand.
* Refresh the inbox, message bubbles, composer and typography. Appearance settings allow a local font file.

### 0.7.0 ###

* Receive wake-ups for selected numbers with a configured Firebase relay and inspect per-number registration in Synchronization settings.
* Refresh visible conversations every 30 seconds and recover missed messages with scheduled background checks.
* Fetch history independently for each number. Avoid automatically repeating a send after a lost response.
* Use the GPT Mobile dark palette by default and open the VoIP.ms Message Center from the inbox.
* Update Android build tools and libraries. Both full flavors can build without operator credentials.

### 0.6.33 ###

* Update dependencies

### 0.6.32 ###

* Bug fixes

### 0.6.31 ###

* Bug fixes

### 0.6.30 ###

* Remove all Firebase libraries except for those required for messaging
* Add Firebase installation ID to "About" section of app
* Update privacy policy

### 0.6.29 ###

* Update dependencies
* Bug fixes
* Target API 35
* Fix lint issues
* Remove legacy code

### 0.6.28 ###

* Remove in-app purchase mechanism/"buy me a coffee" link
* Update dependencies

### 0.6.27 ###

* Support E164 format in VoIP.ms API
* Update dependencies
* Bug fixes

### 0.6.26 ###

* Target API 34
* Update dependencies
* Fix lint issues
* Bug fixes

### 0.6.25 ###

* Bug fixes

### 0.6.24 ###

* Bug fixes
* Limited initial support for receiving MMS
* Restructure "about" section of help

### 0.6.23 ###

* Bug fixes

### 0.6.22 ###

* Bug fixes
* Rename in-app purchase mechanism to "buy me a coffee"

### 0.6.21 ###

* Restored donation in-app purchase mechanism

### 0.6.20 ###

* Bug fixes

### 0.6.19 ###

* Bug fixes and performance improvements

### 0.6.18 ###

* Bug fixes

### 0.6.17 ###

* Bug fixes

### 0.6.16 ###

* Bug fixes

### 0.6.15 ###

* Bug fixes

### 0.6.14 ###

* Bug fixes
* Support for notification bubbles in Android 11
* Support for direct share
* Improvements to notification direct reply
* Allow swiping to archive or delete items
* Use undo snackbars instead of dialogs where possible
* Restore Android Auto support
* Switch cloud functions provider to IBM

### 0.6.13 ###

* Bug fixes
* Removed Android Auto support, due to issues receiving approval from Google

### 0.6.12 ###

* Bug fixes

### 0.6.11 ###

* Bug fixes

### 0.6.10 ###

* Bug fixes and performance improvements

### 0.6.9 ###

* Bug fixes

### 0.6.8 ###

* Bug fixes

### 0.6.7 ###

* Bug fixes

### 0.6.6 ###

* Restore pre-0.6.2 font kerning
* Bug fixes

### 0.6.5 ###

* Dark theme
* Allow viewing all DIDs in conversations view
* Allow backing up a single conversation
* Allow changing the maximum size of the message text box
* Allow copying the DID and contact phone numbers from the conversation view
* Use Android keystore to store VoIP.ms email and password
* Bug fixes

### 0.6.4 ###

* Bug fixes

### 0.6.3 ###

* Bug fixes

### 0.6.2 ###

* New login screen to simplify authentication process
* New app icon
* New conversations and conversation view UI
* Updated color scheme and fonts
* Add app shortcuts
* Added Android Auto support
* Bug fixes

### 0.6.1 ###

* Bug fixes

### 0.6.0 ###

* Add support for Android O notification features
* Show notification while synchronizing
* Add support for database transactions to prevent data corruption
* New settings interface
* Minimum API version is now 21 (Lollipop)
* Bug fixes

### 0.5.3 ###

* Add DID to conversation view
* Bug fixes

### 0.5.2 ###

* Bug fixes

### 0.5.1 ###

* Bug fixes

### 0.5.0 ###

* Partial re-write
* Language change from Java to Kotlin
* Multiple DID support
* Conversation archival support
* Notification inline reply support
* Android Wear support
* Firebase App Indexing support
* Support for changing connection and read timeouts
* Simplified push notification system
* Miscellaneous UI improvements
* Bug fixes and performance improvements
* Removal of some synchronization features

### 0.4.6 ###

* Bug fixes

### 0.4.5 ###

* Bug fixes

### 0.4.4 ###

* Add phone number type to new conversation view
* Add emoji button to message keyboard
* Migration from Google Cloud Messaging to Firebase Cloud Messaging
* Bug fixes

### 0.4.3 ###

* Bug fixes

### 0.4.2 ###

* Added support for message drafts
* Added characters remaining counter to conversation view
* Added support for additional sync intervals
* Phone number of contact always shown in conversation view
* "Sending" and "Not sent" indicators now visible in conversations view
* Added PayPal donation link
* Bug fixes and performance improvements

### 0.4.1 ###

* Automatically set up URL callback for notifications
* Bug fixes

### 0.4.0 ###

* New synchronization options
* Access to messages older than 90 days
* Quick reply screen
* Automatic splitting of messages greater than 160 characters
* Action buttons for notifications
* Search term highlighting
* Database editing mechanism
* Added donation in-app purchase mechanism
* New icon
* Other UI changes
* Bug fixes

### 0.3.1 ###

* Fix error in push notification instructions
* Bug fixes

### 0.3.0 ###

* Support for push notifications
* Additional notification settings
* License change to Apache License 2.0
* Minor UI changes
* Bug fixes

### 0.2.2 ###

* Added compatibility with API version 16 (Jelly Bean)
* Bug fixes

### 0.2.1 ###

* First run setup dialog added
* Bug fixes

### 0.2.0 ###

* Complete re-write
* Platform change from Apache Cordova to native Android

### 0.1.0 ###

* Initial public release
