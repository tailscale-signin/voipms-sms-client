#!/usr/bin/env bash
set -eu
dir="${RUNNER_TEMP:?}/matrix-ci"
mode="${1:?prepare, setup or test}"
if [[ "$mode" == prepare ]]; then
  # Only the CI debug APK trusts the disposable user CA on localhost. Preserve the official
  # verifier's HTTPS-only default and its existing certificate-revocation-list exceptions.
  mkdir -p "$dir" voipms-sms/src/debug/res/xml
  archive="$dir/verifier.aar"
  curl -fsSL --retry 3 -o "$archive" \
    https://raw.githubusercontent.com/rustls/rustls-platform-verifier/1aa691352a5e0c215210dfc9a3c2f2078639dc91/android-release-support/maven/org/rustls/rustls-platform-verifier/0.2.0/rustls-platform-verifier-0.2.0.aar
  echo "aa021794230fbc2f0be355999e2cf67398dd563de066a2e17001ffbd0b69101b  $archive" | sha256sum -c
  python3 - "$archive" <<'PYCODE'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
from zipfile import ZipFile
with ZipFile(sys.argv[1]) as archive:
    policy = ET.fromstring(archive.read("res/xml/network_security_config.xml"))
domain = ET.SubElement(policy, "domain-config")
ET.SubElement(domain, "domain").text = "localhost"
anchors = ET.SubElement(domain, "trust-anchors")
for source in ("system", "user"):
    ET.SubElement(anchors, "certificates", src=source)
ET.ElementTree(policy).write(Path("voipms-sms/src/debug/res/xml/network_security_config.xml"),
                             encoding="utf-8", xml_declaration=True)
PYCODE
elif [[ "$mode" == setup ]]; then
  mkdir -p "$dir"
  cd "$dir"
  umask 077
  openssl req -x509 -newkey rsa:2048 -nodes -days 2 -keyout ca.key -out ca.pem -subj '/CN=SMS CI Matrix CA' >/dev/null 2>&1
  openssl req -newkey rsa:2048 -nodes -keyout tls.key -out req -subj '/CN=localhost' >/dev/null 2>&1
  echo 'subjectAltName=DNS:localhost,IP:127.0.0.1' > extensions
  openssl x509 -req -in req -CA ca.pem -CAkey ca.key -CAcreateserial -days 2 -out tls.pem -extfile extensions >/dev/null 2>&1
  openssl rand -hex 24 > password
  secret=$(openssl rand -hex 24)
  cat > homeserver.yaml <<YAML
server_name: matrix.local
public_baseurl: https://localhost:18443/
report_stats: false
registration_shared_secret: "$secret"
macaroon_secret_key: "$secret"
signing_key_path: /tmp/ci.signing.key
media_store_path: /tmp/media
pid_file: /tmp/synapse.pid
federation_domain_whitelist: []
trusted_key_servers: []
database:
  name: sqlite3
  args:
    database: /tmp/homeserver.db
tls_certificate_path: /data/tls.pem
tls_private_key_path: /data/tls.key
listeners:
  - {port: 18443, type: http, tls: true, bind_addresses: ['0.0.0.0'], resources: [{names: [client]}]}
  - {port: 8008, type: http, tls: false, bind_addresses: ['127.0.0.1'], resources: [{names: [client]}]}
YAML
  # v1.166.0. Only the loopback TLS port is exposed to the emulator through adb reverse.
  docker run -d --name sms-matrix --user "$(id -u):$(id -g)" \
      -v "$dir:/data:ro" -p 127.0.0.1:18443:18443 \
      ghcr.io/element-hq/synapse@sha256:2cdc84f1de4687af89e4b7b1fe7b5f93e0ee7913243b63f79b05319cb6a8f525 \
      run --config-path /data/homeserver.yaml
  for i in {1..45}; do
    if curl --silent --fail --cacert ca.pem https://localhost:18443/_matrix/client/versions > /dev/null; then break; fi
    if [[ "$i" == 45 ]]; then docker logs sms-matrix; exit 1; fi
    sleep 2
  done
  for account in alice bob charlie; do
    docker exec sms-matrix register_new_matrix_user -c /data/homeserver.yaml -u "$account" -p "$(cat password)" --no-admin http://127.0.0.1:8008
  done
elif [[ "$mode" == test ]]; then
  # SMS smoke intentionally takes the emulator offline. adb reverse only exposes our own local test service.
  # NetworkSecurityConfig in the debug app accepts user CAs exclusively on localhost. Restore
  # the KeyChain directory ownership and SELinux labels before the app starts reading trust.
  # adbd closes its transport while switching UID. Its restart can be reported as a client
  # error even when it succeeded, so verify the UID on the new connection before proceeding.
  for attempt in 1 2 3; do
    timeout 15 adb wait-for-device || continue
    if [[ "$(adb shell id -u | tr -d '\r')" == 0 ]]; then break; fi
    adb root || true
    sleep 2
  done
  timeout 20 adb wait-for-device
  [[ "$(adb shell id -u | tr -d '\r')" == 0 ]] || { echo 'Emulator adbd did not restart as root' >&2; exit 1; }
  user_certs=/data/misc/user/0/cacerts-added
  cert_name=$(openssl x509 -in "$dir/ca.pem" -noout -subject_hash_old).0
  adb shell mkdir -p "$user_certs"
  adb shell chown system:system "$user_certs"
  adb shell chmod 775 "$user_certs"
  adb push "$dir/ca.pem" "$user_certs/$cert_name"
  adb shell chown system:system "$user_certs/$cert_name"
  adb shell chmod 644 "$user_certs/$cert_name"
  adb shell restorecon -RF "$user_certs"
  adb reverse tcp:18443 tcp:18443
  adb shell pm clear net.kourlas.voipms_sms
  adb install -r matrix-test-apk/*.apk
  output=$(adb shell am instrument -w -r -e class net.kourlas.voipms_sms.rich.MatrixIntegrationTest \
    -e matrixPassword "$(cat "$dir/password")" \
    net.kourlas.voipms_sms.test/androidx.test.runner.AndroidJUnitRunner)
  echo "$output"
  if ! grep -Fq 'OK (1 test)' <<<"$output"; then
    adb logcat -d -s MatrixCI:I AndroidRuntime:E rustls-platform-verifier-android:W
    exit 1
  fi
else
  exit 2
fi
