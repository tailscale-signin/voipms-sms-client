#!/usr/bin/env bash
set -eu
dir="${RUNNER_TEMP:?}/matrix-ci"
mode="${1:?setup or test}"
if [[ "$mode" == setup ]]; then
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
  adb reverse tcp:18443 tcp:18443
  adb shell pm clear net.kourlas.voipms_sms
  adb install -r matrix-test-apk/*.apk
  ca=$(openssl x509 -in "$dir/ca.pem" -outform DER | base64 -w0)
  output=$(adb shell am instrument -w -r -e class net.kourlas.voipms_sms.rich.MatrixIntegrationTest \
    -e matrixCa "$ca" -e matrixPassword "$(cat "$dir/password")" \
    net.kourlas.voipms_sms.test/androidx.test.runner.AndroidJUnitRunner)
  echo "$output"
  grep -Fq 'OK (1 test)' <<<"$output"
else
  exit 2
fi
