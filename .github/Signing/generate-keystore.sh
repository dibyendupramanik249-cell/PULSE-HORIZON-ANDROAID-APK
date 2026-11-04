#!/usr/bin/env bash
set -euo pipefail

ALIAS="${1:-pulsehorizon}"
OUT="release.keystore"

if [ -f "$OUT" ]; then
  echo "$OUT already exists. Move it away first - refusing to overwrite your signing key."
  exit 1
fi

read -rsp "Keystore password (min 6 chars): " STORE_PASS; echo
read -rsp "Key password     (min 6 chars): " KEY_PASS;   echo

keytool -genkeypair -v \
  -keystore "$OUT" \
  -storetype JKS \
  -storepass "$STORE_PASS" \
  -keypass "$KEY_PASS" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 \
  -validity 2500 \
  -dname "CN=Pulse Horizon, OU=Mobile, O=Pulse Horizon, L=India, C=IN"

base64 -w0 "$OUT" > "$OUT.base64"

cat <<EOF

Created:
  $OUT            ($(wc -c < "$OUT") bytes)
  $OUT.base64     ($(wc -c < "$OUT.base64") characters)

Add these four secrets in Settings -> Secrets and variables -> Actions:

  RELEASE_KEYSTORE_BASE64   <- contents of $OUT.base64
  RELEASE_STORE_PASSWORD    <- the store password you just typed
  RELEASE_KEY_ALIAS         <- $ALIAS
  RELEASE_KEY_PASSWORD      <- the key password you just typed

Now back both files up somewhere off this machine, and delete them from here.
EOF
