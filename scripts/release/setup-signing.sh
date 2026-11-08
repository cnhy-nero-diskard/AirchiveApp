#!/usr/bin/env bash

# Store the existing Airchive release-key passwords in the macOS Keychain.
set -e

AIRCHIVE_SIGNING_CERT_SHA256="2cf5480e933008a24e469d7936cb4abd76cc2352e26ddf3c2a8b4effd4b54202"
KEY_ALIAS=airchive-release
STORE_SERVICE=airchive-release-store-password
KEY_SERVICE=airchive-release-key-password

usage() {
  echo "Usage: setup-signing.sh [--keystore <path>]" >&2
}

keystore="$HOME/.airchive/airchive-release.jks"
custom_keystore=false
while [ "$#" -gt 0 ]; do
  case "$1" in
    --keystore)
      [ "$#" -ge 2 ] || { usage; exit 2; }
      keystore=$2
      custom_keystore=true
      shift 2
      ;;
    *) usage; exit 2 ;;
  esac
done

if [ ! -f "$keystore" ]; then
  echo "Keystore not found: $keystore" >&2
  exit 1
fi
if [ "$custom_keystore" = false ]; then
  mkdir -p "$HOME/.airchive"
  chmod 700 "$HOME/.airchive"
fi
chmod 600 "$keystore"

command -v javac >/dev/null 2>&1 || { echo "javac is required to inspect the public certificate without prompting." >&2; exit 1; }
command -v java >/dev/null 2>&1 || { echo "java is required to inspect the public certificate without prompting." >&2; exit 1; }
command -v keytool >/dev/null 2>&1 || { echo "keytool is required." >&2; exit 1; }
command -v security >/dev/null 2>&1 || { echo "macOS security is required." >&2; exit 1; }

temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airchive-signing-setup.XXXXXX")
cleanup() { rm -r "$temp_dir"; }
trap cleanup EXIT HUP INT TERM
cat > "$temp_dir/CertificateFingerprint.java" <<'JAVA'
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.MessageDigest;

public class CertificateFingerprint {
    public static void main(String[] args) throws Exception {
        KeyStore store = KeyStore.getInstance("JKS");
        try (FileInputStream input = new FileInputStream(args[0])) {
            store.load(input, null); // Read the public certificate; do not verify store integrity here.
        }
        java.security.cert.Certificate certificate = store.getCertificate(args[1]);
        if (certificate == null) throw new IllegalArgumentException("Alias not found: " + args[1]);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) hex.append(String.format("%02x", value & 0xff));
        System.out.println(hex);
    }
}
JAVA
javac -d "$temp_dir" "$temp_dir/CertificateFingerprint.java"
certificate_sha256=$(java -cp "$temp_dir" CertificateFingerprint "$keystore" "$KEY_ALIAS" | tr '[:upper:]' '[:lower:]')
if [ "$certificate_sha256" != "$AIRCHIVE_SIGNING_CERT_SHA256" ]; then
  echo "Keystore certificate SHA-256 does not match the pinned Airchive release identity." >&2
  echo "Expected: $AIRCHIVE_SIGNING_CERT_SHA256" >&2
  echo "Found:    $certificate_sha256" >&2
  exit 1
fi
printf 'Verified release certificate SHA-256: %s\n' "$certificate_sha256"

: "${USER:?USER must be set for Keychain account selection}"
echo "Enter the existing keystore password for the macOS Keychain item '$STORE_SERVICE'."
security add-generic-password -U -s "$STORE_SERVICE" -a "$USER" -w
echo "Enter the existing private-key password for the macOS Keychain item '$KEY_SERVICE'."
security add-generic-password -U -s "$KEY_SERVICE" -a "$USER" -w

export AIRCHIVE_RELEASE_STORE_PASSWORD
export AIRCHIVE_RELEASE_KEY_PASSWORD
AIRCHIVE_RELEASE_STORE_PASSWORD=$(security find-generic-password -s "$STORE_SERVICE" -a "$USER" -w)
AIRCHIVE_RELEASE_KEY_PASSWORD=$(security find-generic-password -s "$KEY_SERVICE" -a "$USER" -w)
if [ -z "$AIRCHIVE_RELEASE_STORE_PASSWORD" ] || [ -z "$AIRCHIVE_RELEASE_KEY_PASSWORD" ]; then
  echo "Keychain password lookup returned an empty value." >&2
  exit 1
fi
keytool -certreq -keystore "$keystore" -alias "$KEY_ALIAS" \
  -storepass:env AIRCHIVE_RELEASE_STORE_PASSWORD \
  -keypass:env AIRCHIVE_RELEASE_KEY_PASSWORD \
  -file "$temp_dir/validation.csr" >/dev/null 2>&1
unset AIRCHIVE_RELEASE_STORE_PASSWORD AIRCHIVE_RELEASE_KEY_PASSWORD
printf 'Signing setup verified. Keychain services: %s and %s.\n' "$STORE_SERVICE" "$KEY_SERVICE"
