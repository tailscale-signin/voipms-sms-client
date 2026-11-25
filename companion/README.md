# Browser companion

Read and reply to VoIP.ms conversations from a Windows browser or a paired phone. Each person has a separate identity and specific phone lines. Drafts follow that identity across devices. Teammates on a shared line can claim a conversation, see who sent a reply and leave notes visible only inside the companion.

## Start on Windows

Install Node.js 24 or later. Run these commands in PowerShell from the repository root. Enter the VoIP.ms API password at the secure prompt. If the account restricts API access by IP address, permit the companion host’s public IP.

```powershell
$env:VOIPMS_EMAIL = Read-Host 'VoIP.ms account email'
$env:VOIPMS_PASSWORD = [Net.NetworkCredential]::new('', (Read-Host 'VoIP.ms API password' -AsSecureString)).Password
node companion/server.mjs grant Melo manage 4165550100
node companion/server.mjs share 4165550100 6475550110
node companion/server.mjs pair Melo
node companion/server.mjs serve
```

Use your actual line and contact numbers. Open **http://127.0.0.1:8765** on the host, enter the code printed by the pairing command and name the device. Codes expire in ten minutes and work once. The browser session lasts seven days. Select **Devices** to pair another device with the same identity or revoke a browser. Use a separately issued identity for each teammate.

To connect other devices, put an HTTPS reverse proxy in front of the loopback service. Set `SMS_COMPANION_ORIGIN` to its exact public origin before starting the service, for example `https://inbox.example.org`. Preserve the original Host header, and forward to `127.0.0.1:8765`. Open the same HTTPS address on the phone and PC. The Android app’s companion setting accepts this HTTPS address. A thread link can include `?did=4165550100&peer=6475550110`. Pairing and permissions still apply.

## Give a work line to a team

| Role | Access within the assigned line |
| --- | --- |
| `read` | Read shared history and team notes. |
| `send` | Also save personal drafts, send SMS, claim or release an unclaimed or personally owned conversation, and add notes. |
| `manage` | Also include and exclude individual contacts for everyone on that line. |

```powershell
node companion/server.mjs grant Alex send 4165550100
node companion/server.mjs pair Alex
node companion/server.mjs grant Sam read 4165550100
node companion/server.mjs pair Sam
```

Selected-contact sharing is the default. **Open a contact** lets a manager add a specific number. To share every conversation on a dedicated work line, run `node companion/server.mjs line-wide 4165550100 on`. Every identity granted that DID can now see its provider history, including future contacts. Use `off` to return to the selected contact list. Individual exclusions apply in both modes.

Use **Exclude this contact** or `node companion/server.mjs unshare 4165550100 6475550110` to hide the thread from all companion devices on that line and delete its drafts, claim and notes. When the native composer is paired, locking a thread on Android also queues an exclusion for that companion identity. If that identity manages the line, the exclusion applies to every teammate. A paired handset must reach its host to apply an exclusion. Unpaired phones apply locks locally. Previously viewed or copied material remains with the recipient.

Remove a person's line access with `node companion/server.mjs ungrant Alex 4165550100`. Run `node companion/server.mjs revoke-user Alex` to remove all of their sessions, future pairing codes, grants, drafts and claims. Team notes and send attribution stay with their work conversations.

To deliberately restore a contact after unlocking it, the host operator can run `node companion/server.mjs restore-contact Alex 4165550100 6475550110` for that identity. Grant selected-contact or whole-line access separately if the contact was also excluded for the team.

## Messages and storage

The inbox checks the last 30 days of provider history, up to 5,000 returned messages per line. It refreshes while visible every 30 seconds. Select **Refresh** to check on demand. Message times use the provider’s Eastern time. Recognized verification-code messages are masked. Some formats can escape detection. Exclude sensitive contacts if their messages should stay off other devices.

**Save draft** makes a reply available on other paired devices for the same person. In the Android conversation choose **Composer tools → Desktop draft**. To pair the native app, generate a fresh code in the companion’s **Devices** panel. **Save phone draft** hands the composer text to the paired identity. **Use desktop draft** brings it back to Android. Replacing a nonempty draft requires confirmation, and version checks protect simultaneous edits. The native flow checks the provider account and line, excludes private threads, and transfers draft text in HTTPS request bodies.

**Send SMS** sends one GSM or Unicode segment. Accepted means VoIP.ms returned a message ID. If connectivity fails during submission, the companion records an unconfirmed attempt. Review the thread and provider portal before initiating another send. MMS items show their attachment count in this text companion. Sending media uses the Android app.

Provider credentials stay in the host process. The database contains sessions, access grants, drafts, team notes and outbound send records. The default path is `.voipms-sms-companion/inbox.sqlite` in the host user’s home directory. Protect that folder using the host account’s permissions and disk encryption. The database is bound to one VoIP.ms account. Override its path with `SMS_COMPANION_DB` and the local port with `SMS_COMPANION_PORT`.

Run `node --test companion/server.test.mjs` for the HTTP, access-control, handoff and simulated VoIP.ms tests.
