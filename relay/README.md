# VoIP.ms callback relay

Deploy this relay in the Firebase project used by the primary Android build. When VoIP.ms reports an inbound SMS, the relay sends a high-priority FCM data message to the phone number’s topic. The phone then retrieves the conversation directly from VoIP.ms with its own API credentials. Only the destination DID goes to the relay. The FCM data payload contains only the event name.

## Deploy on Cloud Run

Use a billing-enabled Firebase project and a Google Cloud account with permission to enable APIs, configure service accounts and deploy public Cloud Run services. The example uses two dedicated service accounts. The builder packages the code. The runtime uses Google-managed credentials to send FCM messages. Run the commands from the repository root in Bash.

```bash
project='your-firebase-project-id'
region='us-central1'
dids='4165550100,4165550101'   # Replace with your own SMS-enabled DIDs.
callback_path=$(openssl rand -hex 24)

gcloud services enable run.googleapis.com cloudbuild.googleapis.com \
  artifactregistry.googleapis.com fcm.googleapis.com iam.googleapis.com --project "$project"

gcloud iam service-accounts create sms-relay --project "$project"
gcloud iam service-accounts create sms-relay-builder --project "$project"
runtime_sa="sms-relay@${project}.iam.gserviceaccount.com"
build_sa="sms-relay-builder@${project}.iam.gserviceaccount.com"

gcloud projects add-iam-policy-binding "$project" \
  --member="serviceAccount:${runtime_sa}" --role=roles/firebasecloudmessaging.admin
gcloud projects add-iam-policy-binding "$project" \
  --member="serviceAccount:${build_sa}" --role=roles/run.builder

gcloud run deploy sms-relay --source ./relay --project "$project" --region "$region" \
  --build-service-account="projects/${project}/serviceAccounts/${build_sa}" \
  --service-account="$runtime_sa" --allow-unauthenticated --max-instances=3 \
  --set-env-vars="^@^FIREBASE_PROJECT_ID=${project}@ALLOWED_DIDS=${dids}@CALLBACK_TOKEN=${callback_path}"

relay_url=$(gcloud run services describe sms-relay --region "$region" --project "$project" \
  --format='value(status.url)')
export VOIPMS_PUSH_CALLBACK_URL="${relay_url}/${callback_path}?did={TO}"
```

The allowlist accepts ten digits, eleven digits starting with 1, or an E.164 number starting with +1. All three map to the Android topic `did-<ten digits>`. The callback path is optional. If you deploy with an empty `CALLBACK_TOKEN`, use `${relay_url}/?did={TO}`. Anyone with the callback URL and an allowed number can trigger a sync hint. A path bundled into a distributed APK is visible to its users. FCM topics also allow subscribers to observe the timing of hints.

The relay writes no application request logs. Cloud Run infrastructure request logs can record complete callback URLs including DIDs and paths. Before enabling callbacks, configure your project's log sinks and retention for this service. The service request log filter is `resource.type="cloud_run_revision" AND resource.labels.service_name="sms-relay" AND log_id("run.googleapis.com/requests")`.

## Connect the Android app and verify receipt

1. Register the Android application ID `net.kourlas.voipms_sms` in the same Firebase project. Save its client configuration at `voipms-sms/src/primary/google-services.json`.
2. Build the primary app with `VOIPMS_PUSH_CALLBACK_URL` exported as above, for example `./gradlew assemblePrimaryFullDebug`. For the GitHub build, set the repository variable `VOIPMS_PUSH_CALLBACK_URL` to the full URL with the literal `{TO}`, and the secret `GOOGLE_SERVICES_JSON` to the Base64-encoded client configuration. After the workflow is on your default branch, run **Actions → Android CI (primary) → Run workflow** to build with those settings.
3. Sign into the app, enable message retrieval for your DID, and allow notifications. Check its push registration in Settings. In the VoIP.ms portal under **DID Numbers → Manage DIDs**, confirm the SMS/MMS callback matches that URL. To retry failed relay calls, turn on **URL Callback Retry** there. VoIP.ms retries if it does not receive the exact text `ok`.
4. Send a new SMS from another number while the app is in the background with the screen locked. Verify the notification, open it, and check that the correct thread contains one copy. A relay response of `ok` confirms FCM accepted the hint. The SMS in the Android thread confirms device receipt.

To run the local HTTP and FCM contract tests using Node.js 24, run `node --test relay/server.test.mjs`. They use dummy numbers and simulated Google responses.

[VoIP.ms SMS/MMS callbacks](https://wiki.voip.ms/article/SMS-MMS) · [Cloud Run source deployments](https://cloud.google.com/run/docs/deploying-source-code) · [Cloud Run service identity](https://cloud.google.com/run/docs/securing/service-identity) · [Firebase HTTP v1 messaging](https://firebase.google.com/docs/cloud-messaging/send/v1-api)
