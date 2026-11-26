#!/usr/bin/env bash
set -euo pipefail
umask 077

tag=v0.8.0
apk=VoIPms-SMS-0.8.0-release.apk
cert=023103fb5ca64728235d8786f2404f47050f2fb3fe7e8b1aab50e924d07bf43a
api="repos/$GH_REPO"
build_tools="${ANDROID_HOME:?}/build-tools/36.0.0"
work="$RUNNER_TEMP/voipms-sms-0.8.0"
mkdir -p "$work"
cd "$work"

tagged_commit() {
  local object
  object=$(gh api "$api/git/matching-refs/tags/$tag" | jq -c --arg ref "refs/tags/$tag" '.[] | select(.ref == $ref) | .object')
  if [[ -z "$object" ]]; then return; fi
  while [[ "$(jq -r .type <<<"$object")" == tag ]]; do
    object=$(gh api "$api/git/tags/$(jq -er .sha <<<"$object")" --jq .object)
  done
  jq -er 'select(.type == "commit") | .sha' <<<"$object"
}

check_target() {
  local tagged
  [[ "$(gh api "$api/git/ref/heads/master" --jq .object.sha)" == "$REV" ]]
  tagged=$(tagged_commit)
  [[ -z "$tagged" || "$tagged" == "$REV" ]]
}

check_run() {
  gh api "$api/actions/runs/$1" | jq -e --arg rev "$REV" --arg file ".github/workflows/$2" \
    '.event == "push" and .head_branch == "master" and .head_sha == $rev and
     .path == $file and .status == "completed" and .conclusion == "success"' >/dev/null
}

release_json() {
  gh api --paginate "$api/releases?per_page=100" | jq -c --arg tag "$tag" '.[] | select(.tag_name == $tag)'
}

asset_json() {
  gh api --paginate "$api/releases/$1/assets?per_page=100" | jq -c --arg apk "$apk" '.[] | select(.name == $apk)'
}

fetch_asset() {
  local metadata="$1" destination="$2" asset_id digest
  asset_id=$(jq -er 'select(.state == "uploaded") | .id' <<<"$metadata")
  digest=$(jq -er '.digest | select(test("^sha256:[0-9a-f]{64}$"))' <<<"$metadata")
  gh api -H 'Accept: application/octet-stream' "$api/releases/assets/$asset_id" > "$destination"
  printf '%s  %s\n' "${digest#sha256:}" "$destination" | sha256sum --check --status
}

check_package() {
  "$build_tools/zipalign" -c -P 16 4 "$1"
  "$build_tools/aapt2" dump badging "$1" > package-info.txt
  grep -Fq "package: name='net.kourlas.voipms_sms' versionCode='158' versionName='0.8.0'" package-info.txt
  grep -Fxq "targetSdkVersion:'36'" package-info.txt
  unzip -p "$1" META-INF/version-control-info.textproto | grep -Fx "  revision: \"$REV\""
}

verify_signed() {
  check_package "$1"
  "$build_tools/apksigner" verify --verbose --print-certs "$1" > signer.txt
  grep -Fx 'Number of signers: 1' signer.txt
  grep -Fix "Signer #1 certificate SHA-256 digest: $cert" signer.txt
  python3 - "$1" <<'PY'
import hashlib
import re
import sys
from zipfile import ZipFile

signatures = re.compile(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))", re.I)

def payload(path):
    digests = {}
    with ZipFile(path) as apk:
        for entry in apk.infolist():
            if entry.is_dir() or signatures.fullmatch(entry.filename):
                continue
            if entry.filename in digests:
                raise ValueError(f"Duplicate APK entry: {entry.filename}")
            with apk.open(entry) as data:
                digests[entry.filename] = hashlib.file_digest(data, "sha256").digest()
    return digests

if payload("unsigned.apk") != payload(sys.argv[1]):
    sys.exit("Signed APK payload differs from the verified master artifact")
PY
}

case "${1:?supply prepare, sign or publish}" in
  prepare)
    check_target
    check_run "$PRIMARY_RUN" android-primary.yml
    check_run "$FDROID_RUN" android-fdroid.yml
    check_run "$PAGES_RUN" static.yml
    gh api "$api/actions/artifacts/$ARTIFACT_ID" | jq -e --arg rev "$REV" --arg run "$PRIMARY_RUN" --arg digest "sha256:$ARTIFACT_SHA256" \
      '.name == "voipms-sms-primary-release-unsigned" and .expired == false and .digest == $digest and
       .workflow_run.head_sha == $rev and (.workflow_run.id | tostring) == $run' >/dev/null
    gh api "$api/actions/artifacts/$ARTIFACT_ID/zip" > artifact.zip
    printf '%s  artifact.zip\n' "$ARTIFACT_SHA256" | sha256sum --check
    mapfile -t files < <(unzip -Z1 artifact.zip)
    [[ "${#files[@]}" == 1 && "${files[0]}" == *-unsigned.apk ]]
    unzip -p artifact.zip "${files[0]}" > unsigned.apk
    printf '%s  unsigned.apk\n' "$UNSIGNED_SHA256" | sha256sum --check
    check_package unsigned.apk
    python3 - <<'PY'
import struct
from zipfile import ZipFile

with ZipFile("unsigned.apk") as apk:
    libraries = [n for n in apk.namelist() if n.startswith(("lib/arm64-v8a/", "lib/x86_64/")) and n.endswith(".so")]
    for name in libraries:
        with apk.open(name) as library:
            header = library.read(64)
            if header[:6] != b"\x7fELF\x02\x01":
                raise ValueError(f"Invalid 64-bit Android ELF header in {name}")
            offset = struct.unpack_from("<Q", header, 32)[0]
            size, count = struct.unpack_from("<HH", header, 54)
            library.seek(offset)
            for _ in range(count):
                entry = library.read(size)
                kind, _, file_offset, vaddr, _, _, _, alignment = struct.unpack_from("<IIQQQQQQ", entry)
                if kind == 1 and (alignment < 16384 or (vaddr - file_offset) % 16384):
                    raise ValueError(f"Unaligned 16 KB load segment in {name}")
    print(f"Checked native 16 KB alignment in {len(libraries)} packaged 64-bit libraries")
PY
    release=$(release_json)
    if [[ -n "$release" ]]; then
      existing=$(asset_json "$(jq -er .id <<<"$release")")
      if [[ -n "$existing" ]]; then
        fetch_asset "$existing" "$apk"
        verify_signed "$apk"
        echo 'has_signed_asset=true' >> "$GITHUB_OUTPUT"
        exit 0
      fi
    fi
    patch="$GITHUB_WORKSPACE/release-staging/official-apk.patch"
    if [[ -f "$patch" ]]; then
      mkdir -p maintainer-apk
      git init --quiet maintainer-apk
      cp unsigned.apk "maintainer-apk/$apk"
      git -C maintainer-apk apply --binary --include="$apk" "$patch"
      mv "maintainer-apk/$apk" "$apk"
      verify_signed "$apk"
      echo 'has_signed_asset=true' >> "$GITHUB_OUTPUT"
    fi
    ;;
  sign)
    if [[ -z "${SIGNING_STORE:-}" || -z "${SIGNING_PASSWORD:-}" ]]; then
      echo '::error::The original SMS signing identity is required. Configure VOIPMS_RELEASE_KEYSTORE_BASE64 and VOIPMS_RELEASE_STORE_PASSWORD or provide the maintainer-signed APK.'
      exit 1
    fi
    trap 'rm -f -- "$work/release.keystore"' EXIT
    printf '%s' "$SIGNING_STORE" | base64 --decode > release.keystore
    unset SIGNING_STORE
    options=()
    if [[ -n "${SIGNING_ALIAS:-}" ]]; then options+=(--ks-key-alias "$SIGNING_ALIAS"); fi
    if [[ -n "${SIGNING_KEY_PASSWORD:-}" ]]; then options+=(--key-pass env:SIGNING_KEY_PASSWORD); fi
    "$build_tools/apksigner" sign --ks release.keystore --ks-pass env:SIGNING_PASSWORD "${options[@]}" --v4-signing-enabled false --out "$apk" unsigned.apk
    verify_signed "$apk"
    ;;
  publish)
    verify_signed "$apk"
    check_target
    release=$(release_json)
    if [[ -z "$release" ]]; then
      cat > notes.md <<'NOTES'
**VoIP.ms SMS 0.8.0 (158)** is a signed Android update for installations of the fork’s 0.7.1 release.

* Messages displays loading and retry states, preserves the current conversation while refreshing, and keeps drafts tied to their recipients. This update fixes crashes when displaying certain avatars and highlighting search results.
* Open Settings → Database → Debug mode to record message loading and file operations. Export the local JSON Lines report to see timings, counts and error locations. Turning debug mode off clears its local history.
* From a conversation, choose Export → Save JSON to file for the complete SMS and MMS history, including messages outside the current search or page.
* Send ordinary SMS and MMS from your VoIP.ms number over internet-connected Wi-Fi. The outbox keeps queued messages when connectivity drops and lets you review uncertain sends.
* Link a Matrix account to use encrypted direct and group chat, attachments, typing indicators and receipts with participating accounts. Nearby lets two compatible Android devices exchange messages over Wi-Fi Direct after you confirm the pairing on both phones.
* Open a draft in the phone’s carrier messaging app to use your mobile line. The carrier app and provider determine whether RCS is available for that conversation.
* Review suspicious messages using on-device English, French and Portuguese analysis. The Other tab explains why a conversation was flagged, and you can correct its placement.
* Look up an unfamiliar number using the on-device numbering plan and optional directory, carrier and reputation sources. You choose which services receive the number.
* Save names in the encrypted app address book. Passphrase-protected portable backups include saved app contacts by default. You can exclude them on export or select individual contacts on restore.
* The refreshed Home, Messages and Other tabs use a simpler layout with new icons and buttons. Home lists missed calls and voicemails by receipt time after VoIP.ms synchronization. Play, pause or stop voicemail there or in the matching conversation. A long pull at the top of Messages opens private threads after device authentication. Repeat the gesture to return.

[Full change list](https://github.com/tailscale-signin/voipms-sms-client/blob/v0.8.0/CHANGES.md) · [Setup and usage](https://github.com/tailscale-signin/voipms-sms-client/blob/v0.8.0/HELP.md)

APK signing certificate SHA-256 `023103fb5ca64728235d8786f2404f47050f2fb3fe7e8b1aab50e924d07bf43a`.
NOTES
      release=$(gh api --method POST "$api/releases" -f tag_name="$tag" -f target_commitish="$REV" \
        -f name='VoIPms SMS 0.8.0' -F draft=true -F prerelease=false -F body=@notes.md)
    fi
    id=$(jq -er --arg tag "$tag" 'select(.tag_name == $tag and .prerelease == false) | .id' <<<"$release")
    if [[ "$(jq -r .draft <<<"$release")" == true ]]; then
      target=$(jq -er .target_commitish <<<"$release")
      [[ "$target" == "$REV" || "$target" == master ]]
      existing=$(asset_json "$id")
      if [[ -z "$existing" ]]; then
        curl --fail-with-body --silent --show-error --request POST \
          --header "Authorization: Bearer $GH_TOKEN" --header 'Content-Type: application/vnd.android.package-archive' \
          --data-binary "@$apk" "https://uploads.github.com/$api/releases/$id/assets?name=$apk" > uploaded.json
        existing=$(asset_json "$id")
        expected=$(sha256sum "$apk" | cut -d' ' -f1)
        [[ "$(jq -er .digest <<<"$existing")" == "sha256:$expected" ]]
      fi
      fetch_asset "$existing" downloaded.apk
      verify_signed downloaded.apk
      check_target
      gh api --method PATCH "$api/releases/$id" -f target_commitish="$REV" -F draft=false -f make_latest=true --jq .html_url
    fi
    [[ "$(tagged_commit)" == "$REV" ]]
    gh api "$api/releases/$id" | jq -er 'select(.draft == false and .prerelease == false) | .html_url'
    [[ "$(gh api "$api/releases/latest" --jq .tag_name)" == "$tag" ]]
    fetch_asset "$(asset_json "$id")" downloaded.apk
    verify_signed downloaded.apk
    sha256sum downloaded.apk
    ;;
  *) exit 2 ;;
esac
