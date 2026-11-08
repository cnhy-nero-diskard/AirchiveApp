#!/usr/bin/env bash

# Generate the public schema-version-1 updater manifest for a verified APK.
set -e

script_dir=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=scripts/release/check-apk.sh
. "$script_dir/check-apk.sh"
expected_certificate=2cf5480e933008a24e469d7936cb4abd76cc2352e26ddf3c2a8b4effd4b54202
apk=
tag=
commit=
published_at=
release_body=
output_path=update.json

usage() {
  echo "Usage: make-manifest.sh --apk <path> --tag <tag> --commit <sha> --published-at <ISO-8601> --release-body <path> [--output <path>]" >&2
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --apk) [ "$#" -ge 2 ] || { usage; exit 2; }; apk=$2; shift 2 ;;
    --tag) [ "$#" -ge 2 ] || { usage; exit 2; }; tag=$2; shift 2 ;;
    --commit) [ "$#" -ge 2 ] || { usage; exit 2; }; commit=$2; shift 2 ;;
    --published-at) [ "$#" -ge 2 ] || { usage; exit 2; }; published_at=$2; shift 2 ;;
    --release-body) [ "$#" -ge 2 ] || { usage; exit 2; }; release_body=$2; shift 2 ;;
    --output) [ "$#" -ge 2 ] || { usage; exit 2; }; output_path=$2; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; usage; exit 2 ;;
  esac
done

if [ -z "$apk" ] || [ -z "$tag" ] || [ -z "$commit" ] || [ -z "$published_at" ] || [ -z "$release_body" ] || [ ! -f "$apk" ] || [ ! -f "$release_body" ]; then
  usage
  exit 2
fi
if ! [[ "$commit" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Commit must be a full 40-character lowercase hexadecimal hash." >&2
  exit 1
fi
build_tools=$(find_build_tools)
badging=$("$build_tools/aapt" dump badging "$apk")
version_name=$(printf '%s\n' "$badging" | sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" | head -1)
version_code=$(printf '%s\n' "$badging" | sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p" | head -1)
package_name=$(printf '%s\n' "$badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1)
if [ "$package_name" != "cloud.airchive.personal" ] || [ -z "$version_name" ] || [ -z "$version_code" ]; then
  echo "APK badging must identify cloud.airchive.personal and include versionName and versionCode." >&2
  exit 1
fi
if [ "$tag" != "v$version_name" ]; then
  echo "Tag '$tag' does not match APK versionName '$version_name'." >&2
  exit 1
fi

python3 - "$apk" "$tag" "$commit" "$published_at" "$release_body" "$output_path" "$version_name" "$version_code" "$expected_certificate" <<'PY'
import hashlib
import json
import re
import sys
from datetime import datetime

apk_path, tag, commit, published_at, body_path, output_path, version_name, version_code, certificate = sys.argv[1:]
try:
    datetime.fromisoformat(published_at.replace("Z", "+00:00"))
except ValueError as exc:
    raise SystemExit("publishedAt must be an ISO-8601 timestamp.") from exc

with open(body_path, encoding="utf-8") as body_file:
    notes = body_file.read()
notes = re.sub(r"(?m)^[ \t]{0,3}#{1,6}[ \t]*", "", notes)
notes = re.sub(r"!?\[([^\]]*)\]\([^)]*\)", r"\1", notes)
notes = re.sub(r"(?m)^[ \t]{0,3}([-+*]|[0-9]+[.)])[ \t]+", r"\1 ", notes)
notes = re.sub(r"<[^>]*>", "", notes)
notes = notes.replace("\r\n", "\n").replace("\r", "\n")
notes = re.sub(r"(?m)^[ \t]*\x60\x60\x60[^\n]*\n|^[ \t]*\x60\x60\x60[ \t]*$", "", notes)
for marker in ("*", "_", "~", chr(96)):
    notes = notes.replace(marker, "")
notes = re.sub(r"[ \t]+\n", "\n", notes).strip()
if len(notes) > 4000:
    raise SystemExit("Plain-text release notes exceed 4000 characters.")

with open(apk_path, "rb") as apk_file:
    apk_bytes = apk_file.read()
asset_name = "airchive-{}-{}.apk".format(version_name, version_code)
manifest = {
    "schemaVersion": 1,
    "channel": "stable",
    "packageName": "cloud.airchive.personal",
    "versionCode": int(version_code),
    "versionName": version_name,
    "commit": commit,
    "publishedAt": published_at,
    "releaseNotes": notes,
    "apk": {
        "url": "https://github.com/cnhy-nero-diskard/AirchiveApp/releases/download/{}/{}".format(tag, asset_name),
        "sha256": hashlib.sha256(apk_bytes).hexdigest(),
        "size": len(apk_bytes),
        "signingCertSha256": certificate,
    },
}
with open(output_path, "w", encoding="utf-8") as output_file:
    json.dump(manifest, output_file, indent=2, ensure_ascii=False)
    output_file.write("\n")
print("Wrote {}".format(output_path))
PY
